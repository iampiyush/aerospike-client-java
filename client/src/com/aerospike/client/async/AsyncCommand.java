/*
 * Copyright 2012-2015 Aerospike, Inc.
 *
 * Portions may be licensed to Aerospike, Inc. under one or more contributor
 * license agreements WHICH ARE COMPATIBLE WITH THE APACHE LICENSE, VERSION 2.0.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.aerospike.client.async;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import com.aerospike.client.AerospikeException;
import com.aerospike.client.admin.AdminCommand;
import com.aerospike.client.command.Command;
import com.aerospike.client.policy.Policy;
import com.aerospike.client.util.ThreadLocalData;

/**
 * Asynchronous command handler.
 */
public abstract class AsyncCommand extends Command implements Runnable {
	
	protected AsyncConnection conn;
	protected ByteBuffer byteBuffer;
	protected final AsyncCluster cluster;
	protected AsyncNode node;
	private final AtomicBoolean complete = new AtomicBoolean();
	private long limit;
	protected int timeout;
	private int iteration;
	protected boolean inAuthenticate;
	protected boolean inHeader = true;
	
	public AsyncCommand(AsyncCluster cluster) {
		this.cluster = cluster;
	}
	
	public void execute() throws AerospikeException {
		Policy policy = getPolicy();
		timeout = policy.timeout;
		
		if (timeout > 0) {	
			limit = System.currentTimeMillis() + timeout;
		}
		
		byteBuffer = cluster.getByteBuffer();
		executeCommand();
	}
		
	public void executeCommand() throws AerospikeException {
		if (complete.get()) {
			return;
		}

		try {
			node = getNode();
			conn = node.getAsyncConnection();
			
			if (conn == null) {
				conn = new AsyncConnection(node.getAddress(), cluster);
			
				if (cluster.getUser() != null) {
					inAuthenticate = true;
					dataBuffer = ThreadLocalData.getBuffer();
					AdminCommand command = new AdminCommand(dataBuffer);
					dataOffset = command.setAuthenticate(cluster.getUser(), cluster.getPassword());
					byteBuffer.clear();
					byteBuffer.put(dataBuffer, 0, dataOffset);
					byteBuffer.flip();
					conn.execute(this);
					return;
				}
			}
			sendCommand();
		}
		catch (AerospikeException.InvalidNode ai) {
			if (!retryOnInit()) {				
				throw ai;
			}
		}
		catch (AerospikeException.Connection ce) {
			// Socket connection error has occurred.
			if (!retryOnInit()) {				
				throw ce;
			}
		}
		catch (Exception e) {
			if (!failOnApplicationInit()) {
				throw new AerospikeException(e);
			}
		}
	}
	
	protected void sendCommand() throws AerospikeException {	
		writeBuffer();
		
		if (dataOffset > byteBuffer.capacity()) {
			byteBuffer = ByteBuffer.allocateDirect(dataOffset);
		}
		
		byteBuffer.clear();
		byteBuffer.put(dataBuffer, 0, dataOffset);
		byteBuffer.flip();

		conn.execute(this);
	}
	
	protected void processAuthenticate() throws AerospikeException {	
		inAuthenticate = false;
		inHeader = true;
		
		int resultCode = byteBuffer.get(1) & 0xFF;
	
		if (resultCode != 0) {
			throw new AerospikeException(resultCode);
		}
		sendCommand();
	}

	private boolean retryOnInit() throws AerospikeException {
		if (complete.get()) {
			return true;
		}

		Policy policy = getPolicy();
		
		if (++iteration > policy.maxRetries) {
			return failOnNetworkInit();
		}

		// Disable sleep between retries.  Sleeping in asynchronous mode makes no sense.
		// if (limit > 0 && System.currentTimeMillis() + policy.sleepBetweenRetries > limit) {
		
		if (limit > 0 && System.currentTimeMillis() > limit) {
			// Might as well stop here because the transaction will
			// timeout after sleep completed.
			return failOnNetworkInit();
		}

		// Prepare for retry.
		resetConnection();

		// Disable sleep between retries.  Sleeping in asynchronous mode makes no sense.
		//if (policy.sleepBetweenRetries > 0) {
		//	Util.sleep(policy.sleepBetweenRetries);
		//}

		// Retry command recursively.
		executeCommand();
		return true;
	}

	protected final void write() throws IOException {
		conn.write(byteBuffer);
	}

	protected final void retryAfterInit(AerospikeException ae) {
		if (complete.get()) {
			return;
		}

		Policy policy = getPolicy();

		if (++iteration > policy.maxRetries) {
			failOnNetworkError(ae);
			return;
		}

		// Disable sleep between retries.  Sleeping in asynchronous mode makes no sense.
		// if (limit > 0 && System.currentTimeMillis() + policy.sleepBetweenRetries > limit) {

		if (limit > 0 && System.currentTimeMillis() > limit) {
			// Might as well stop here because the transaction will
			// timeout after sleep completed.
			failOnNetworkError(ae);
			return;
		}

		// Prepare for retry.
		resetConnection();

		// Disable sleep between retries.  Sleeping in asynchronous mode makes no sense.
		//if (policy.sleepBetweenRetries > 0) {
		//	Util.sleep(policy.sleepBetweenRetries);
		//}

		try {
			// Retry command recursively.
			executeCommand();
		}
		catch (Exception e) {
			// Command has already been cleaned up.
			// Notify user of original exception.
			onFailure(ae);
		}
	}

	private void resetConnection() {
		if (limit > 0) {
			// A lock on reset is required when a client timeout is specified.
			synchronized (this) {
				if (conn != null) {
					conn.close();
					conn = null;
				}
			}
		}
		else {
			if (conn != null) {
				conn.close();
				conn = null;
			}
		}
	}

	protected final boolean checkTimeout() {
		if (complete.get()) {
			return false;
		}
		
		if (limit > 0 && System.currentTimeMillis() > limit) {
			// If command is currently reading data in an offloaded task thread,
			// the interestOps is set to zero.  Make sure non-zero because we can't 
			// timeout while data is actively being read because the byteBuffer 
			// would be put back into the pool before the read is finished.
			if (conn.interestOps() != 0) {
				// Command has timed out in timeout queue thread.
				// Ensure that command succeeds or fails, but not both.
				if (complete.compareAndSet(false, true)) {
					// Timeout thread may contend with retry thread.
					// Lock before closing.
					synchronized (this) {
						if (conn != null) {
							conn.close();
						}
					}
					failOnClientTimeout();
				}
				return false;  // Do not put back on timeout queue.
			}
		}
		return true;
	}
	
	public void run() {
		try {
			read();
			
			if (! complete.get()) {
				conn.setReadable();
			}
		}
        catch (AerospikeException.Connection ac) {
        	retryAfterInit(ac);
        }
        catch (AerospikeException ae) {
			// Fail without retry on non-network errors.
			failOnApplicationError(ae);
        }
        catch (IOException ioe) {
        	retryAfterInit(new AerospikeException(ioe));
        }
        catch (Exception e) {
			// Fail without retry on unknown errors.
			failOnApplicationError(new AerospikeException(e));
        }
	}
	
	protected final void finish() {
		// Finish could be called from a separate asyncTaskThreadPool thread.
		// Make sure SelectorManager thread has not already caused a transaction timeout.
		if (complete.compareAndSet(false, true)) {
			conn.unregister();
			conn.updateLastUsed();
			node.putAsyncConnection(conn);
			cluster.putByteBuffer(byteBuffer);
			
			try {
				onSuccess();
			}
			catch (AerospikeException ae) {
				// The user's onSuccess() may have already been called which in turn generates this
				// exception.  It's important to call onFailure() anyhow because the user's code 
				// may be waiting for completion notification which wasn't yet called in
				// onSuccess().  This is the only case where both onSuccess() and onFailure()
				// gets called for the same command.
				onFailure(ae);
			}
			catch (Exception e) {
				onFailure(new AerospikeException(e));
			}
		} 
	}

	private boolean failOnNetworkInit() {
		// Ensure that command succeeds or fails, but not both.
		if (complete.compareAndSet(false, true)) {			
			closeOnNetworkError();
			return false;
		}
		else {
			return true;
		}
	}

	private boolean failOnApplicationInit() {
		// Ensure that command succeeds or fails, but not both.
		if (complete.compareAndSet(false, true)) {			
			close();
			return false;
		}
		else {
			return true;
		}
	}

	private void failOnNetworkError(AerospikeException ae) {
		// Ensure that command succeeds or fails, but not both.
		if (complete.compareAndSet(false, true)) {			
			closeOnNetworkError();
			onFailure(ae);
		}
	}

	protected final void failOnApplicationError(AerospikeException ae) {
		// Ensure that command succeeds or fails, but not both.
		if (complete.compareAndSet(false, true)) {
			if (ae.keepConnection()) {
				// Put connection back in pool.
				conn.unregister();
				conn.updateLastUsed();
				node.putAsyncConnection(conn);
				cluster.putByteBuffer(byteBuffer);
			}
			else {
				// Close socket to flush out possible garbage.
				close();
			}
			onFailure(ae);
		}
	}

	private void failOnClientTimeout() {
		// Free up resources and notify.
		closeOnNetworkError();
		onFailure(new AerospikeException.Timeout());
	}

	private void closeOnNetworkError() {
		close();
	}

	private void close() {
		if (conn != null) {
			conn.close();
		}
		cluster.putByteBuffer(byteBuffer);
	}

	protected abstract AsyncNode getNode() throws AerospikeException.InvalidNode;
	protected abstract void read() throws AerospikeException, IOException;
	protected abstract void onSuccess();
	protected abstract void onFailure(AerospikeException ae);
}

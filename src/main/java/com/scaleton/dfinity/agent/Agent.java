/*
 * Copyright 2021 Exilor Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package com.scaleton.dfinity.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.lang3.ArrayUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.scaleton.dfinity.agent.identity.Identity;
import com.scaleton.dfinity.agent.identity.Signature;
import com.scaleton.dfinity.agent.replicaapi.CallRequestContent;
import com.scaleton.dfinity.agent.replicaapi.Certificate;
import com.scaleton.dfinity.agent.replicaapi.Envelope;
import com.scaleton.dfinity.agent.replicaapi.QueryContent;
import com.scaleton.dfinity.agent.replicaapi.ReadStateContent;
import com.scaleton.dfinity.agent.replicaapi.ReadStateResponse;
import com.scaleton.dfinity.agent.replicaapi.QueryResponse;
import com.scaleton.dfinity.agent.replicaapi.ReadStateContent;
import com.scaleton.dfinity.agent.requestid.RequestId;
import com.scaleton.dfinity.agent.requestid.RequestIdError;
import com.scaleton.dfinity.agent.requestid.RequestIdSerializer;
import com.scaleton.dfinity.types.Principal;

public final class Agent {
	static final byte[] IC_REQUEST_DOMAIN_SEPARATOR = "\nic-request".getBytes(StandardCharsets.UTF_8);
	static final byte[] IC_STATE_ROOT_DOMAIN_SEPARATOR = "\ric-state-root".getBytes(StandardCharsets.UTF_8);
	static final byte[] IC_ROOT_KEY = "\\x30\\x81\\x82\\x30\\x1d\\x06\\x0d\\x2b\\x06\\x01\\x04\\x01\\x82\\xdc\\x7c\\x05\\x03\\x01\\x02\\x01\\x06\\x0c\\x2b\\x06\\x01\\x04\\x01\\x82\\xdc\\x7c\\x05\\x03\\x02\\x01\\x03\\x61\\x00\\x81\\x4c\\x0e\\x6e\\xc7\\x1f\\xab\\x58\\x3b\\x08\\xbd\\x81\\x37\\x3c\\x25\\x5c\\x3c\\x37\\x1b\\x2e\\x84\\x86\\x3c\\x98\\xa4\\xf1\\xe0\\x8b\\x74\\x23\\x5d\\x14\\xfb\\x5d\\x9c\\x0c\\xd5\\x46\\xd9\\x68\\x5f\\x91\\x3a\\x0c\\x0b\\x2c\\xc5\\x34\\x15\\x83\\xbf\\x4b\\x43\\x92\\xe4\\x67\\xdb\\x96\\xd6\\x5b\\x9b\\xb4\\xcb\\x71\\x71\\x12\\xf8\\x47\\x2e\\x0d\\x5a\\x4d\\x14\\x50\\x5f\\xfd\\x74\\x84\\xb0\\x12\\x91\\x09\\x1c\\x5f\\x87\\xb9\\x88\\x83\\x46\\x3f\\x98\\x09\\x1a\\x0b\\xaa\\xae"
			.getBytes(StandardCharsets.UTF_8);

	static final Integer DEFAULT_INGRESS_EXPIRY_DURATION = 300;
	static final Integer DEFAULT_PERMITTED_DRIFT = 60;

	static final Logger LOG = LoggerFactory.getLogger(Agent.class);

	ReplicaTransport transport;
	Duration ingressExpiryDuration;
	Identity identity;
	NonceFactory nonceFactory;
	Optional<byte[]> rootKey;

	Agent(AgentBuilder builder) {
		this.transport = builder.config.transport.get();

		if (builder.config.ingressExpiryDuration.isPresent())
			ingressExpiryDuration = builder.config.ingressExpiryDuration.get();
		else
			ingressExpiryDuration = Duration.ofSeconds(DEFAULT_INGRESS_EXPIRY_DURATION);

		this.identity = builder.config.identity;
		
		this.nonceFactory = builder.config.nonceFactory;
	}

	Long getExpiryDate() {
		// TODO: evaluate if we need this on the agent side 
		Duration permittedDrift = Duration.ofSeconds(DEFAULT_PERMITTED_DRIFT);

		return ((this.ingressExpiryDuration.plus(Duration.ofMillis(System.currentTimeMillis()))).minus(permittedDrift))
				.toNanos();
	}

	/*
	 * By default, the agent is configured to talk to the main Internet Computer,
	 * and verifies responses using a hard-coded public key.
	 * 
	 * This function will instruct the agent to ask the endpoint for its public key,
	 * and use that instead. This is required when talking to a local test instance,
	 * for example.
	 * 
	 * Only use this when you are _not_ talking to the main Internet Computer,
	 * otherwise you are prone to man-in-the-middle attacks! Do not call this
	 * function by default.*
	 */

	public void fetchRootKey() throws AgentError {
		Status status;
		try {
			status = this.status().get();

			if (status.rootKey.isPresent())
				this.setRootKey(status.rootKey.get());
			else
				AgentError.create(AgentError.AgentErrorCode.NO_ROOT_KEY_IN_STATUS, status);
		} catch (InterruptedException | ExecutionException e) {
			LOG.error(e.getLocalizedMessage(), e);
			AgentError.create(AgentError.AgentErrorCode.TRANSPORT_ERROR, e);
		}

	}

	/*
	 * By default, the agent is configured to talk to the main Internet Computer,
	 * and verifies responses using a hard-coded public key.
	 * 
	 * Using this function you can set the root key to a known one if you know if
	 * beforehand.
	 */

	public synchronized void setRootKey(byte[] rootKey) throws AgentError {
		this.rootKey = Optional.of(rootKey);
	}

	byte[] getRootKey() throws AgentError {
		if (rootKey.isPresent())
			return rootKey.get();
		else
			throw AgentError.create(AgentError.AgentErrorCode.COULD_NOT_READ_ROOT_KEY);
	}

	byte[] constructMessage(RequestId requestId) {
		return ArrayUtils.addAll(IC_REQUEST_DOMAIN_SEPARATOR, requestId.get());
	}

	/*
	 * Calls and returns the information returned by the status endpoint of a
	 * replica.
	 */

	public CompletableFuture<Status> status() throws AgentError {
		ObjectMapper objectMapper = new ObjectMapper(new CBORFactory());
		objectMapper.registerModule(new Jdk8Module());

		CompletableFuture<Status> response = new CompletableFuture<Status>();

		transport.status().whenComplete((input, ex) -> {
			if (ex == null) {
				if (input != null) {
					try {
						Status status = objectMapper.readValue(input, Status.class);
						response.complete(status);
					} catch (Exception e) {
						LOG.debug(e.getLocalizedMessage());
						response.completeExceptionally(
								AgentError.create(AgentError.AgentErrorCode.INVALID_CBOR_DATA, e, input));
					}

				} else {
					response.completeExceptionally(
							AgentError.create(AgentError.AgentErrorCode.INVALID_CBOR_DATA, input));
				}
			} else {
				response.completeExceptionally(ex);
			}

		});

		return response;
	}

	public CompletableFuture<byte[]> queryRaw(Principal canisterId, Principal effectiveCanisterId, String method,
			byte[] arg, Optional<Long> ingressExpiryDatetime) throws AgentError {
		QueryContent queryContent = new QueryContent();

		queryContent.queryRequest.methodName = method;
		queryContent.queryRequest.canisterId = canisterId;
		queryContent.queryRequest.arg = arg;
		queryContent.queryRequest.sender = this.identity.sender();

		if (ingressExpiryDatetime.isPresent())
			queryContent.queryRequest.ingressExpiry = ingressExpiryDatetime.get();
		else
			queryContent.queryRequest.ingressExpiry = this.getExpiryDate();

		CompletableFuture<byte[]> response = new CompletableFuture<byte[]>();

		this.queryEndpoint(effectiveCanisterId, queryContent).whenComplete((input, ex) -> {
			if (ex == null) {
				if (input != null) {
					if (input.replied.isPresent()) {
						byte[] out = input.replied.get().arg;
						response.complete(out);
					} else if (input.rejected.isPresent()) {
						response.completeExceptionally(AgentError.create(AgentError.AgentErrorCode.REPLICA_ERROR,
								input.rejected.get().rejectCode, input.rejected.get().rejectMessage));
					} else
						response.completeExceptionally(
								AgentError.create(AgentError.AgentErrorCode.INVALID_REPLICA_STATUS));

				} else {
					response.completeExceptionally(AgentError.create(AgentError.AgentErrorCode.INVALID_REPLICA_STATUS));
				}
			} else {
				response.completeExceptionally(ex);
			}
		});
		return response;
	}

	public CompletableFuture<QueryResponse> queryEndpoint(Principal effectiveCanisterId, QueryContent request)
			throws AgentError {

		RequestId requestId = RequestId.toRequestId(request);
		
		LOG.debug("Query Request Id:" + Arrays.toString(ByteUtils.toUnsignedIntegerArray(requestId.get())));
		
		byte[] msg = this.constructMessage(requestId);
		
		LOG.debug("Query Message Id:" + Arrays.toString(ByteUtils.toUnsignedIntegerArray(msg)));

		Signature signature = this.identity.sign(msg);

		ObjectMapper objectMapper = new ObjectMapper(new CBORFactory()).registerModule(new Jdk8Module());

		ObjectWriter objectWriter = objectMapper.writerFor(Envelope.class).withAttribute("request_type", "query");

		Envelope<QueryContent> envelope = new Envelope<QueryContent>();

		envelope.content = request;
		envelope.senderPubkey = signature.publicKey;
		envelope.senderSig = signature.signature;	
		
		byte[] bytes = null;
		try {
			bytes = objectWriter.writeValueAsBytes(envelope);
		} catch (JsonProcessingException e) {
			throw AgentError.create(AgentError.AgentErrorCode.INVALID_CBOR_DATA, e, envelope);
			// normally, rethrow exception here - or don't catch it at all.
		}

		CompletableFuture<QueryResponse> response = new CompletableFuture<QueryResponse>();

		transport.query(effectiveCanisterId, bytes).whenComplete((input, ex) -> {
			if (ex == null) {
				if (input != null) {
					try {
						QueryResponse queryResponse = objectMapper.readValue(input, QueryResponse.class);
						response.complete(queryResponse);
					} catch (Exception e) {
						LOG.debug(e.getLocalizedMessage(), e);
						response.completeExceptionally(AgentError.create(AgentError.AgentErrorCode.MESSAGE_ERROR, e,
								new String(input, StandardCharsets.UTF_8)));
					}

				} else {
					response.completeExceptionally(
							AgentError.create(AgentError.AgentErrorCode.TRANSPORT_ERROR, "Payload is empty"));
				}
			} else {
				response.completeExceptionally(ex);
			}

		});

		return response;
	}

	/*
	 * The simplest way to do an update call; sends a byte array and will return a
	 * RequestId. The RequestId should then be used for request_status (most likely
	 * in a loop).
	 */

	public CompletableFuture<RequestId> updateRaw(Principal canisterId, Principal effectiveCanisterId, String method,
			byte[] arg, Optional<Long> ingressExpiryDatetime) throws AgentError {
		CallRequestContent callRequestContent = new CallRequestContent();

		callRequestContent.callRequest.methodName = method;
		callRequestContent.callRequest.canisterId = canisterId;
		callRequestContent.callRequest.arg = arg;
		callRequestContent.callRequest.sender = this.identity.sender();
		
		if(this.nonceFactory != null)
			callRequestContent.callRequest.nonce = Optional.of(nonceFactory.generate());

		if (ingressExpiryDatetime.isPresent())
			callRequestContent.callRequest.ingressExpiry = ingressExpiryDatetime.get();
		else
			callRequestContent.callRequest.ingressExpiry = this.getExpiryDate();

		CompletableFuture<RequestId> response = new CompletableFuture<RequestId>();

		this.callEndpoint(effectiveCanisterId, callRequestContent).whenComplete((input, ex) -> {
			if (ex == null) {
				if (input != null) {
					response.complete(input);
				} else {
					response.completeExceptionally(AgentError.create(AgentError.AgentErrorCode.INVALID_REPLICA_STATUS));
				}
			} else {
				response.completeExceptionally(ex);
			}
		});
		return response;
	}

	public CompletableFuture<RequestId> callEndpoint(Principal effectiveCanisterId, CallRequestContent request)
			throws AgentError {
		RequestId requestId = RequestId.toRequestId(request);
		byte[] msg = this.constructMessage(requestId);
		
		Signature signature = this.identity.sign(msg);

		ObjectMapper objectMapper = new ObjectMapper(new CBORFactory()).registerModule(new Jdk8Module());

		ObjectWriter objectWriter = objectMapper.writerFor(Envelope.class).withAttribute("request_type", "call");

		Envelope<CallRequestContent> envelope = new Envelope<CallRequestContent>();

		envelope.content = request;
		envelope.senderPubkey = signature.publicKey;
		envelope.senderSig = signature.signature;

		byte[] bytes = null;
		try {
			bytes = objectWriter.writeValueAsBytes(envelope);
		} catch (JsonProcessingException e) {
			throw AgentError.create(AgentError.AgentErrorCode.INVALID_CBOR_DATA, e, envelope);
			// normally, rethrow exception here - or don't catch it at all.
		}

		CompletableFuture<RequestId> response = new CompletableFuture<RequestId>();

		transport.call(effectiveCanisterId, bytes, requestId).whenComplete((input, ex) -> {
			if (ex == null) {
				if (input != null) {
					response.complete(requestId);
				} else {
					response.completeExceptionally(
							AgentError.create(AgentError.AgentErrorCode.TRANSPORT_ERROR, "Payload is empty"));
				}
			} else {
				response.completeExceptionally(ex);
			}

		});

		return response;
	}

	public CompletableFuture<RequestStatusResponse> requestStatusRaw(RequestId requestId, Principal effectiveCanisterId)
			throws AgentError {
		List<List<byte[]>> paths = new ArrayList<List<byte[]>>();

		List<byte[]> path = new ArrayList<byte[]>();
		path.add("request_status".getBytes());
		path.add(requestId.get());
		
		paths.add(path);

		CompletableFuture<RequestStatusResponse> response = new CompletableFuture<RequestStatusResponse>();

		this.readStateRaw(effectiveCanisterId, paths).whenComplete((input, ex) -> {
			if (ex == null) {
				if (input != null) {
					
					try
					{
						RequestStatusResponse requestStatusResponse = ResponseAuthentication.lookupRequestStatus(input, requestId);
						response.complete(requestStatusResponse);
					}catch(AgentError e)
					{
						response.completeExceptionally(e);
					}
				} else {
					response.completeExceptionally(
							AgentError.create(AgentError.AgentErrorCode.INVALID_CBOR_DATA, input));
				}
			} else {
				response.completeExceptionally(ex);
			}
		});

		return response;
	}

	public CompletableFuture<Certificate> readStateRaw(Principal effectiveCanisterId, List<List<byte[]>> paths)
			throws AgentError {
		ObjectMapper objectMapper = new ObjectMapper(new CBORFactory());
		objectMapper.registerModule(new Jdk8Module());

		ReadStateContent readStateContent = new ReadStateContent();

		readStateContent.readStateRequest.paths = paths;
		readStateContent.readStateRequest.sender = this.identity.sender();
		readStateContent.readStateRequest.ingressExpiry = this.getExpiryDate();

		CompletableFuture<Certificate> response = new CompletableFuture<Certificate>();

		this.readStateEndpoint(effectiveCanisterId, readStateContent, ReadStateResponse.class)
				.whenComplete((input, ex) -> {
					if (ex == null) {
						if (input != null) {
							try {
								Certificate cert = objectMapper.readValue(input.certificate, Certificate.class);
								response.complete(cert);
							} catch (Exception e) {
								LOG.debug(e.getLocalizedMessage());
								response.completeExceptionally(
										AgentError.create(AgentError.AgentErrorCode.INVALID_CBOR_DATA, e, input));
							}

						} else {
							response.completeExceptionally(
									AgentError.create(AgentError.AgentErrorCode.INVALID_CBOR_DATA, input));
						}
					} else {
						response.completeExceptionally(ex);
					}
				});
		return response;
	}

	public <T> CompletableFuture<T> readStateEndpoint(Principal effectiveCanisterId, ReadStateContent request,
			Class<T> clazz) throws AgentError {

		RequestId requestId = RequestId.toRequestId(request);
		
		LOG.debug("Read State Request Id:" + Arrays.toString(ByteUtils.toUnsignedIntegerArray(requestId.get())));
		
		byte[] msg = this.constructMessage(requestId);
		
		LOG.debug("Read State Message Id:" + Arrays.toString(ByteUtils.toUnsignedIntegerArray(msg)));
		
		Signature signature = this.identity.sign(msg);	
		

		ObjectMapper objectMapper = new ObjectMapper(new CBORFactory()).registerModule(new Jdk8Module());

		ObjectWriter objectWriter = objectMapper.writerFor(Envelope.class).withAttribute("request_type", "read_state");

		Envelope<ReadStateContent> envelope = new Envelope<ReadStateContent>();

		envelope.content = request;
		envelope.senderPubkey = signature.publicKey;
		envelope.senderSig = signature.signature;		
				
		byte[] bytes = null;
		try {
			bytes = objectWriter.writeValueAsBytes(envelope);
		} catch (JsonProcessingException e) {
			throw AgentError.create(AgentError.AgentErrorCode.INVALID_CBOR_DATA, e, envelope);
			// normally, rethrow exception here - or don't catch it at all.
		}

		CompletableFuture<T> response = new CompletableFuture<T>();

		transport.readState(effectiveCanisterId, bytes).whenComplete((input, ex) -> {
			if (ex == null) {
				if (input != null) {
					try {
						T readStateResponse = objectMapper.readValue(input, clazz);
						response.complete(readStateResponse);
					} catch (IOException e) {
						LOG.debug(e.getLocalizedMessage(), e);
						response.completeExceptionally(AgentError.create(AgentError.AgentErrorCode.MESSAGE_ERROR, e,
								new String(input, StandardCharsets.UTF_8)));
					}

				} else {
					response.completeExceptionally(
							AgentError.create(AgentError.AgentErrorCode.TRANSPORT_ERROR, "Payload is empty"));
				}
			} else {
				response.completeExceptionally(ex);
			}

		});

		return response;
	}

}

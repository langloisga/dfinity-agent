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

package com.scaleton.dfinity.agent.replicaapi;

import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.databind.annotation.JsonAppend;
import com.scaleton.dfinity.agent.Serialize;
import com.scaleton.dfinity.agent.Serializer;
import com.scaleton.dfinity.types.Principal;

public final class CallRequestContent implements Serialize {

	@JsonUnwrapped
	public CallRequest callRequest = new CallRequest();
	
	@JsonAppend(
		    prepend = true,
		    attrs = {
		            @JsonAppend.Attr( value = "request_type")
		    })
	public final class CallRequest
	{
		@JsonProperty("nonce")
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		public Optional<byte[]> nonce;
		@JsonProperty("ingress_expiry")
		public Long ingressExpiry;		
		@JsonProperty("sender")
		public Principal sender;		
		@JsonProperty("canister_id")
		public Principal canisterId;		
		@JsonProperty("method_name")
		public String methodName;
		@JsonProperty("arg")
		public byte[] arg;
	}
	
	@Override
	public void serialize(Serializer serializer) {
		serializer.serializeField("request_type", "call");
		if(callRequest.nonce.isPresent())
			serializer.serializeField("nonce", callRequest.nonce.get());
		serializer.serializeField("ingress_expiry", callRequest.ingressExpiry);
		serializer.serializeField("sender",callRequest.sender);
		serializer.serializeField("canister_id",callRequest.canisterId);
		serializer.serializeField("method_name",callRequest.methodName);
		serializer.serializeField("arg",callRequest.arg);
		
	}
}

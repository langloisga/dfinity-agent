# dfinity-agent
Dfinity Java Agent is a set of native Java libriaries to connect remotely to the Internet Computer applications.

<a href="https://dfinity.org/">
https://dfinity.org/
</a>

The code is implementation of the Internet Computer Interface protocol 

<a href="https://sdk.dfinity.org/docs/interface-spec/index.html">
https://sdk.dfinity.org/docs/interface-spec/index.html
</a>

and it's using Dfinity Rust agent as an inspiration, using similar package structures and naming conventions.

<a href="https://github.com/dfinity/agent-rs">
https://github.com/dfinity/agent-rs
</a>

Currently we support query and update (call) operations with primitive types, arrays, option and principal type. Record and Variant types support is in development.

# License

Dfinity Java Agent is available under Apache License 2.0.

# Documentation

##Supported type mapping between Java and Candid

| Candid      | Java    |
| :---------- | :---------- | 
| bool   | Boolean | 
|  int| Integer   | 
| int8   | Byte | 
| int16   | Short | 
| int32   | Integer | 
| int64   | Long | 
| float32   | Float | 
| float64   | Double | 
| text   | String | 
| opt   | Optional | 
| principal   | Principal | 
| vec   | array | 
| null   |Null | 

##Supported Identities

Anonymous
Implicit by default

###Basic Identity (Ed25519)

Either generate Key Pair

```
KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();

Identity identity = BasicIdentity.fromKeyPair(keyPair);
```

Or use PEM file

```
Identity identity = BasicIdentity.fromPEMFile(path);
```

### Secp256k1 Identity

Get Key Pair from PEM file

```
Identity identity = Secp256k1Identity.fromPEMFile(path);
```

For Basic and Identities we are using Bouncy Castle open source libraries. To add it to your Java code you can use this code

```
Security.addProvider(new BouncyCastleProvider());
```

##Supported Transports

###HTTP Transport

```
Transport transport = ReplicaHttpTransport.create("http://localhost:8000");
```

## Agent Class

Create Agent object

```
Agent agent = new AgentBuilder().transport(transport).identity(identity).build();
```

##IDLArgs

To pass arguments to the Internet Computer Canister methods

```
List<IDLValue> args = new ArrayList<IDLValue>();

Integer intValue =new Integer(10000);
			
args.add(IDLValue.create(intValue));			
			
IDLArgs idlArgs = IDLArgs.create(args);

byte[] buf = idlArgs.toBytes();
```

## Supported Raw Methods

###Query

To call query method

```
CompletableFuture<byte[]> response = agent.queryRaw(Principal.fromString(canister_id),
					Principal.fromString(canister_id), "echoInt", buf, ingressExpiryDatetime);

byte[] output = response.get();
IDLArgs outArgs = IDLArgs.fromBytes(output);
```

###Update

To call update method

```
CompletableFuture<RequestId> response = agent.updateRaw(Principal.fromString(canister_id),
					Principal.fromString(canister_id), "greet", buf, ingressExpiryDatetime);
```

###GetState

To call getState method to retrieve result of update method

```
RequestId requestId = response.get();

CompletableFuture<RequestStatusResponse> statusResponse = agent.requestStatusRaw(requestId,
		Principal.fromString(canister_id));
```

## Supported Builders

###QueryBuilder

```
CompletableFuture<byte[]> response = QueryBuilder.create(agent, Principal.fromString(canister_id), "echoInt").expireAfter(Duration.ofMinutes(3)).arg(buf).call();

byte[] output = response.get();
IDLArgs outArgs = IDLArgs.fromBytes(output);
```

###UpdateBuilder

```
UpdateBuilder updateBuilder = UpdateBuilder
	.create(agent, Principal.fromString(canister_id), "greet").arg(buf);
	
					CompletableFuture<byte[]> builderResponse = updateBuilder.callAndWait(com.scaleton.dfinity.agent.Waiter.create(60, 5));
					
byte[] output = builderResponse.get();
IDLArgs outArgs = IDLArgs.fromBytes(output);	
```

## Dynamic Proxy with annotated facade interface

Additionally you can also use Dynamic Proxy Class with facade Java interface that maps methods in the Internet Computer Canister. Agent values can be replaced with Agent Java object  

```
@Agent(identity = @Identity(type = IdentityType.BASIC, pem_file = "./src/test/resources/Ed25519_identity.pem"), transport = @Transport(url = "http://localhost:8001"))
@Canister("rrkah-fqaaa-aaaaa-aaaaq-cai")
@EffectiveCanister("rrkah-fqaaa-aaaaa-aaaaq-cai")
public interface HelloProxy {
	
	@QUERY
	public String peek(@Argument(Type.TEXT)String name, @Argument(Type.INT) Integer value);
	
	@QUERY
	@Name("echoInt")
	public Integer getInt(Integer value);	
	
	@QUERY
	public CompletableFuture<Double> getFloat(Double value);
	
	@UPDATE
	@Name("greet")
	@Waiter(timeout = 30)
	public CompletableFuture<String> greet(@Argument(Type.TEXT)String name);

}
```

Then create Dynamic Proxy object and call the method

```
HelloProxy hello = ProxyBuilder.create().getProxy(HelloProxy.class);
			
String result = hello.peek(value, intValue);
```


# Downloads / Accessing Binaries

To add Java Dfinity Agent library to your Java project use Maven or Gradle import from Maven Central.

<a href="https://search.maven.org/artifact/com.scaleton.dfinity/dfinity-agent/0.5.2/jar">
https://search.maven.org/artifact/com.scaleton.dfinity/dfinity-agent/0.5.2/jar
</href>

```
<dependency>
  <groupId>com.scaleton.dfinity</groupId>
  <artifactId>dfinity-agent</artifactId>
  <version>0.5.2</version>
</dependency>
```

```
implementation 'com.scaleton.dfinity:dfinity-agent:0.5.2'
```

# Build

You need JDK 8+ to build Dfinity Agent.


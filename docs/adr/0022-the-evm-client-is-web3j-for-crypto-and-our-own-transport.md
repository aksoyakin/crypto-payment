# 0022. web3j for signing and ABI, a JSON-RPC transport of our own

- Status: Accepted
- Date: 2026-10-16

## Context
Two very different things are needed to talk to an EVM chain. One is cryptography and encoding: secp256k1
signatures, RLP, ABI encoding and decoding, keccak, address derivation. Getting those wrong is how funds are
lost, and nobody should write them by hand. The other is a transport: JSON-RPC 2.0 over HTTP, where numbers
arrive as hexadecimal strings and `null` means "not yet".

web3j is the established Java library for both. Its `core` artifact was measured on this project's classpath:
**89 jars, 36.8 MB**, bringing the AWS SDK's KMS client, Netty, Vert.x, RxJava, OkHttp with the Kotlin standard
library, a WebSocket implementation, JNR with ASM for Unix-socket IPC, and Jackson 2 — in a service that makes
HTTP calls to one node and signs transactions. Spring Boot 4 manages Jackson 3, so Jackson 2 arrives as a second
JSON library nothing in this project asks for.

The project's own conventions also require every RPC call to carry timeouts and retries and to allow a second
provider. That logic belongs to whoever owns the call path.

## Decision
- Depend on **`org.web3j:crypto` and `org.web3j:abi`** for signing, RLP and ABI work, excluding their transitive
  tuweni adapters (which pull Vert.x and Netty) and Jackson 2. web3j's `core` artifact is not used.
- The **JSON-RPC transport is written here**: `JsonRpc` over Spring's `RestClient` with Boot's Jackson 3, and
  `EvmNode` on top of it exposing the calls the watcher and the treasury need, in numbers rather than hex. The
  wire shapes are separate records, mapped by hand (ADR 0021).
- Connect and read timeouts are explicit at construction. A node that answers with an error is a
  `JsonRpcException`, told apart from a node that cannot be reached, which arrives as an IO failure.
- The client is tested against a real node, not a mock: Anvil in a container, the same image the local stack
  runs (`EvmNodeTest`).

Result: **14 jars, 14.2 MB** for the chain libraries, one JSON library in the image, and no AWS, Netty, Vert.x,
RxJava, OkHttp or JNR on the runtime classpath.

## Consequences
- The one call path to a third party the platform does not control is ours to shape: timeouts, retries, failover
  between providers and what is logged.
- web3j's keystore files (`WalletUtils`) and its WebSocket and IPC transports are unavailable. Neither is used:
  key material is encrypted with AES-GCM by this project (ADR 0017), and polling over HTTP is what ADR 0003
  describes.
- A JSON-RPC client is now this project's code, and a thin one is easy to get subtly wrong. It is covered by
  tests against a real node, and every method it gains arrives with one.
- Should a second chain family (Tron, Solana) be added, it brings its own client behind the same adapter; this
  decision binds EVM chains only.

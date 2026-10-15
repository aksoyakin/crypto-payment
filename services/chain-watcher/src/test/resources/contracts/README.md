# Test token

`TestToken.sol` is the token the watcher's tests transfer, and `TestToken.bin` is its creation bytecode,
deployed by the tests to the local chain.

The bytecode is checked in rather than compiled by the build: the Java build does not have a Solidity
compiler, and the tests should not need one. It was produced with the same Foundry version the tests run
their node with:

```bash
docker run --rm -v "$PWD":/work -w /work ghcr.io/foundry-rs/foundry:v1.8.4 "forge build"
# out/TestToken.sol/TestToken.json → .bytecode.object
```

Regenerate it the same way if the source changes. The platform's own contracts live in `contracts/` at the
repository root and are built by Foundry there; this one is only a counterparty for the tests.

# 0004. The first network and assets, and how transfers are detected

- Status: Accepted
- Date: 2026-10-15

## Context
The gateway has to start on one network. The choice decides the client library, how transfers are detected and
how long a payment takes to become final. The project runs on testnets only.

## Decision
- **Network:** Ethereum Sepolia. Locally and in tests, Anvil with a test ERC-20 deployed by the tests.
- **Assets:** USDC (Circle's testnet token) first; ETH second. The walking skeleton is built on USDC.
- **Detecting token transfers:** for each block range, the watcher fetches every `Transfer` log of each supported
  token contract and matches recipients against the set of watched addresses in memory. Recipients are not put
  into the RPC filter, so the cost does not grow with the number of deposit addresses.
- **Detecting ETH transfers:** top-level transactions whose recipient is a watched address. ETH sent by a
  contract (an internal transfer, such as a payment from a smart contract wallet) is not detected without trace
  APIs, which most RPC providers do not offer. This limitation is documented to merchants.
- **Finality:** confirmation is decided by the `safe` and `finalized` block tags rather than a fixed block count;
  the threshold a payment needs depends on its amount.
- **Reorganisations** are rare on Sepolia, so they are produced in tests with Anvil's reorg support.
- Further EVM networks (Base Sepolia first) are added through configuration behind the same adapter. Other chain
  families (Tron, Solana) come later behind the same `ChainAdapter` interface.

## Consequences
- Stablecoins, the product's main use, have the simpler and more complete detection path.
- Native-coin payments carry a documented blind spot until trace support is added.
- An L2 brings a second notion of finality (sequencer acceptance versus L1 settlement), which the confirmation
  policy will have to express when Base is added.

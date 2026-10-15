# 0014. Deposit addresses are CREATE2 forwarders

- Status: Accepted
- Date: 2026-10-16
- Amends: 0005, 0006

## Context
ADR 0005 gave each deposit address its own private key, derived from the platform's seed, with the api deriving
addresses from an extended public key. That design has three costs:

- every token sweep needs two transactions, because the deposit address holds no ETH for gas;
- every deposit address is a key the signer has to hold and protect;
- with non-hardened derivation, an extended public key plus any one leaked child key reveals all of them.

## Decision
- Deposit addresses are **counterfactual contract addresses**: `CREATE2(factory, salt, forwarderInitCodeHash)`,
  with the salt derived from the payment intent. The api computes the address itself; no key, no extended public
  key and no derivation index exists for it.
- The forwarder is a minimal proxy (EIP-1167) whose only function moves its whole balance of a given token, or
  of ETH, to the hot wallet. The hot wallet's address is fixed in the factory, so anyone may trigger a flush; it
  can only ever send funds to the platform.
- A **flush** is one transaction from the hot wallet: deploy the forwarder if it is not deployed yet, then move
  the funds. Gas funding disappears.
- The contracts are written in Solidity, tested with Foundry, and use OpenZeppelin's `Clones` (MIT).
- The signer holds only the hot wallet's key. Its intents become: flush a forwarder, move funds to the cold
  wallet, pay a merchant out.
- Addresses are still never reused: one salt per payment intent.

## Consequences
- One key to protect instead of one per payment; the extended-public-key leak scenario no longer exists.
- Sweeping a token costs one transaction instead of two, paid by the hot wallet.
- The project now has a smart contract, so it needs contract tests and a deployment step for the factory.
- ETH detection is unchanged (top-level transactions to the address, ADR 0004).

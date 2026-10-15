# 0005. Key custody

- Status: Accepted, amended by 0014
- Date: 2026-10-15

## Context
The platform holds funds (ADR 0002), so it holds keys: one per deposit address and those of the hot wallet. It
runs on a single VPS with no cloud KMS. Ethereum signs with secp256k1, which HashiCorp Vault's Transit engine
and its fork OpenBao do not support; either could only store a secret, not sign with it, and Vault is no longer
open-source licensed.

## Decision
- **Hot wallet and deposit keys** are derived from one BIP-39 seed held only by the signer. The seed is stored
  encrypted with AES-GCM (envelope encryption); the key-encryption key is given to the signer alone, as a Docker
  secret. The seed is decrypted only in the signer's memory.
- Key access sits behind a `KeyProvider` port in the signer. In production it would be backed by an HSM or an
  MPC custody provider; this project does not use one.
- **Derivation paths:** deposit addresses at `m/44'/60'/0'/0/i`; the hot wallet on a separate account. The api
  receives only the extended public key (xpub) of the deposit account and derives addresses from it.
- No child private key ever leaves the signer. With non-hardened derivation, an xpub together with any one child
  private key reveals the parent private key, so a single leaked deposit key would expose every deposit address.
- **Cold wallet:** a Safe multisig (2-of-3) whose owners are keys the platform does not hold. Moving funds from
  hot to cold is an ordinary transfer to the Safe; moving them back is signed by hand in the Safe interface.

## Consequences
- A compromise of the api exposes addresses but no keys.
- A compromise of the signer host exposes the hot wallet and the deposit keys, but not the cold wallet.
- The signer is not needed until funds move (sweeps and payouts), so the walking skeleton does without it.

# 0017. Encrypting application secrets

- Status: Accepted
- Date: 2026-10-16

## Context
Some secrets must be stored in a form the platform can read back: a webhook signing secret is needed to sign
every delivery, and the signer's seed is needed to sign transactions. Secret API keys are not among them; they
are stored as hashes. Storing readable secrets needs a key, a place for that key, and a way to change it.

## Decision
- Readable secrets are encrypted with AES-GCM. Each ciphertext is stored together with the identifier of the
  key that encrypted it.
- Keys come from Docker secrets, one set for the api (webhook secrets) and a separate one for the signer
  (the seed). Neither service can read the other's keys.
- Rotation adds a new key and makes it current; old ciphertexts stay readable through their key identifier and
  are re-encrypted in the background. A key is removed only once nothing refers to it.

## Consequences
- A database dump alone does not reveal webhook secrets or the seed.
- Losing a key makes what it encrypted unreadable, so keys are part of the backup plan (ADR 0018).

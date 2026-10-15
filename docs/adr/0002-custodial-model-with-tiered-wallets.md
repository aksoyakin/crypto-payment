# 0002. A custodial model with tiered wallets and a ledger as the source of truth

- Status: Accepted, amended by 0014
- Date: 2026-10-15

## Context
A payment gateway either holds customer payments before paying merchants out (custodial, as card processors do)
or routes each payment straight to the merchant's own wallet, taking its fee on the way (non-custodial).

## Decision
The platform is custodial.

- Each payment gets its own deposit address, derived from the platform's HD wallet.
- Funds are swept from deposit addresses into a **hot wallet**, which holds only what payouts need; the surplus
  moves to a **cold wallet** that requires several signatures.
- On chain, merchants' funds are pooled (an omnibus wallet). Which share belongs to whom is known only to a
  **double-entry ledger**, which is the source of truth for every balance.
- A scheduled reconciliation checks that the sum of the platform's wallets equals the merchants' balances plus
  the platform's fee income, and raises an alert when it does not.

## Consequences
- Sweeping, gas funding for token transfers, nonce management and reconciliation become the platform's problems.
  These are the problems the project means to show.
- Commission is taken in the ledger at payment time rather than on chain.
- A non-custodial mode, with a contract that splits each payment, stays possible as a later option.
- Holding funds for others is regulated; the project runs on testnets only.

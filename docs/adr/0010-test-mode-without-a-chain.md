# 0010. Test mode without a chain

- Status: Accepted
- Date: 2026-10-15

## Context
Merchants expect a test mode in which they can build their integration without moving money. In this project
even live mode runs on a testnet, so "test mode on a testnet" would distinguish nothing, and it would still make
merchants wait for faucets and block confirmations.

## Decision
- Live mode follows the real chain flow.
- Test mode never touches a chain. Test-mode keys can call test helpers, such as
  `POST /v1/test_helpers/payment_intents/{id}/simulate_payment`, which produce the same observation a chain
  would and pass it through the same application code.
- Keys, payments, balances and events are separated by mode in every table.

## Consequences
- An integration can be tested end to end, webhooks included, in seconds.
- The code path from observation to ledger and webhook is shared, so test mode exercises what live mode runs.
- Test helpers must be unreachable with live keys; a test asserts it.

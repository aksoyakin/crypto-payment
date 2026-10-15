# 0019. Account security and an audit log

- Status: Accepted
- Date: 2026-10-16

## Context
The most direct way to steal a merchant's money is not to break the platform but to take over the merchant's
dashboard account and change where payouts go. A waiting period on new payout addresses slows that down; it
does not tell anyone it is happening.

## Decision
- Dashboard users can enable TOTP two-factor authentication, and it is **required** for sensitive actions:
  adding a payout address, creating a secret API key, and changing webhook endpoints.
- Every sensitive action sends an e-mail to the account's owners.
- Sensitive actions by merchants and by administrators (approving a merchant, changing fees, adding a payout
  address) are written to an append-only audit log in its own schema, in the same transaction as the action.

## Consequences
- Taking over a password alone is not enough to redirect payouts, and the owner hears about any attempt.
- Who did what, and when, can be answered for every action that moves or redirects money.

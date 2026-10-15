-- The ledger: the only record of which share of the pooled funds belongs to whom (ADR 0008, ADR 0013).
--
-- Three of its rules are enforced here rather than in application code, because the ledger has to be right even
-- when the code around it is not:
--   * debits equal credits, per transaction and per asset, checked at commit;
--   * entries and transactions are never updated or deleted, enforced by taking the privilege away from the
--     role the application connects with, which is not the owner of these tables;
--   * a balance that must not go negative carries the constraint that says so.

CREATE TABLE ledger.assets (
    id               uuid        PRIMARY KEY,
    chain            text        NOT NULL,
    contract_address text,                    -- null is the chain's native coin
    symbol           text        NOT NULL,
    decimals         smallint    NOT NULL CHECK (decimals BETWEEN 0 AND 36),
    created_at       timestamptz NOT NULL DEFAULT now(),
    UNIQUE NULLS NOT DISTINCT (chain, contract_address)
);

COMMENT ON TABLE ledger.assets IS 'A chain and token pair. The same token on another chain is another asset.';

CREATE TABLE ledger.accounts (
    id          uuid        PRIMARY KEY,
    code        text        NOT NULL UNIQUE,
    type        text        NOT NULL CHECK (type IN ('asset', 'liability', 'revenue', 'expense')),
    asset_id    uuid        NOT NULL REFERENCES ledger.assets (id),
    merchant_id uuid,                         -- null is a platform account
    mode        text        NOT NULL CHECK (mode IN ('test', 'live')),
    created_at  timestamptz NOT NULL DEFAULT now()
);

COMMENT ON COLUMN ledger.accounts.code IS
    'platform:custody:{asset}, merchant:{id}:{mode}:pending:{asset}, merchant:{id}:{mode}:available:{asset}, '
    'platform:fee_revenue:{asset}, platform:network_fees:{asset}';

CREATE INDEX accounts_by_merchant ON ledger.accounts (merchant_id, mode) WHERE merchant_id IS NOT NULL;

CREATE TABLE ledger.transactions (
    id             uuid        PRIMARY KEY,
    kind           text        NOT NULL,
    reference_type text        NOT NULL,
    reference_id   uuid        NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    -- One payment cannot be credited twice, however many times its observation is applied.
    UNIQUE (reference_type, reference_id, kind)
);

CREATE TABLE ledger.entries (
    id             uuid          PRIMARY KEY,
    transaction_id uuid          NOT NULL REFERENCES ledger.transactions (id),
    account_id     uuid          NOT NULL REFERENCES ledger.accounts (id),
    -- A uint256 has up to 78 digits, in the asset's smallest unit. Never a floating-point type.
    direction      text          NOT NULL CHECK (direction IN ('debit', 'credit')),
    amount         numeric(78,0) NOT NULL CHECK (amount > 0),
    created_at     timestamptz   NOT NULL DEFAULT now()
);

CREATE INDEX entries_by_transaction ON ledger.entries (transaction_id);
CREATE INDEX entries_by_account ON ledger.entries (account_id);

-- A cache of SUM(entries), written in the same transaction as the entries. The entries are the truth; a test
-- compares the two.
CREATE TABLE ledger.balances (
    account_id   uuid          PRIMARY KEY REFERENCES ledger.accounts (id),
    amount       numeric(78,0) NOT NULL DEFAULT 0,
    version      bigint        NOT NULL DEFAULT 0,
    -- Set for balances that may never go negative: a merchant's available balance, which is what can be paid
    -- out. A pending balance is reversible by a reorganisation, so it is not marked (ADR 0013).
    non_negative boolean       NOT NULL DEFAULT false,
    CONSTRAINT balances_non_negative CHECK (NOT non_negative OR amount >= 0)
);

-- Checked at commit, not per row: the entries of one transaction are written one after another, so they only
-- balance once the last one is in.
CREATE FUNCTION ledger.assert_transaction_balances() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    unbalanced record;
BEGIN
    SELECT accounts.asset_id AS asset_id,
           sum(CASE WHEN entries.direction = 'debit' THEN entries.amount ELSE -entries.amount END) AS difference
      INTO unbalanced
      FROM ledger.entries entries
      JOIN ledger.accounts accounts ON accounts.id = entries.account_id
     WHERE entries.transaction_id = NEW.transaction_id
     GROUP BY accounts.asset_id
    HAVING sum(CASE WHEN entries.direction = 'debit' THEN entries.amount ELSE -entries.amount END) <> 0
     LIMIT 1;

    IF FOUND THEN
        RAISE EXCEPTION 'ledger transaction % does not balance for asset %: debits minus credits is %',
            NEW.transaction_id, unbalanced.asset_id, unbalanced.difference;
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER entries_balance
    AFTER INSERT ON ledger.entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger.assert_transaction_balances();

-- Append-only, enforced by privilege. A correction is a reversing transaction, never an edit. The balances
-- cache stays writable; the entries behind it do not.
REVOKE UPDATE, DELETE ON ledger.entries FROM cryptopayment_api;
REVOKE UPDATE, DELETE ON ledger.transactions FROM cryptopayment_api;

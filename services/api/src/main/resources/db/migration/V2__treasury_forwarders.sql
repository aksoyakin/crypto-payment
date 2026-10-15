-- Deposit addresses, and what the watcher is allowed to see of them (ADR 0014).
--
-- An address is the counterfactual address of a forwarder: CREATE2(factory, salt, initCodeHash), with the salt
-- derived from the payment intent. There is no key, no extended public key and no derivation index, and an
-- address is used by exactly one intent, so a late payment still belongs to the payment it was meant for.
--
-- The chain-watcher reads what to watch through views and nothing else: the two services share a database
-- server, not tables.

CREATE TABLE treasury.forwarders (
    id          uuid        PRIMARY KEY,
    chain       text        NOT NULL,
    intent_id   uuid        NOT NULL UNIQUE,
    salt        text        NOT NULL,
    address     text        NOT NULL,
    -- Null until the forwarder is deployed, which a flush does when it moves the funds (ADR 0014).
    deployed_at timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (chain, salt),
    UNIQUE (chain, address),
    -- Addresses are compared, so they are stored in one form. Lower case, everywhere, in both services.
    CONSTRAINT forwarders_address_is_lower_case CHECK (address = lower(address)),
    CONSTRAINT forwarders_salt_is_lower_case CHECK (salt = lower(salt))
);

CREATE VIEW treasury.watcher_addresses AS
SELECT chain, address
  FROM treasury.forwarders;

-- Which tokens to read, and the identity each amount is reported with. The watcher reports an asset_id rather
-- than a contract address, so the api never has to map one back.
CREATE VIEW treasury.watcher_assets AS
SELECT chain, contract_address, id AS asset_id, decimals
  FROM ledger.assets;

GRANT SELECT ON treasury.watcher_addresses TO cryptopayment_chain_watcher;
GRANT SELECT ON treasury.watcher_assets TO cryptopayment_chain_watcher;

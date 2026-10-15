#!/bin/sh
# Runs once, on first initialisation of the data volume.
#
# Two roles per service: a migrator that owns the service's schemas and runs its migrations, and a runtime role
# that only gets the privileges it is granted. Owners can always change their own tables, so a rule such as
# "ledger entries are never updated" only holds if the running application is not the owner.
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<SQL
CREATE ROLE cryptopayment_api_migrator           LOGIN PASSWORD '${API_MIGRATOR_DB_PASSWORD}';
CREATE ROLE cryptopayment_api                    LOGIN PASSWORD '${API_DB_PASSWORD}';
CREATE ROLE cryptopayment_chain_watcher_migrator LOGIN PASSWORD '${CHAIN_WATCHER_MIGRATOR_DB_PASSWORD}';
CREATE ROLE cryptopayment_chain_watcher          LOGIN PASSWORD '${CHAIN_WATCHER_DB_PASSWORD}';
CREATE ROLE cryptopayment_signer_migrator        LOGIN PASSWORD '${SIGNER_MIGRATOR_DB_PASSWORD}';
CREATE ROLE cryptopayment_signer                 LOGIN PASSWORD '${SIGNER_DB_PASSWORD}';

REVOKE ALL ON DATABASE ${POSTGRES_DB} FROM PUBLIC;
GRANT CONNECT ON DATABASE ${POSTGRES_DB} TO
    cryptopayment_api_migrator, cryptopayment_api,
    cryptopayment_chain_watcher_migrator, cryptopayment_chain_watcher,
    cryptopayment_signer_migrator, cryptopayment_signer;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

CREATE EXTENSION IF NOT EXISTS citext SCHEMA public;
SQL

# owns <migrator> <runtime> <schema>...: the migrator owns each schema, and whatever it creates there is readable
# and writable by the runtime role unless a migration revokes it.
owns() {
    migrator=$1; runtime=$2; shift 2
    for schema in "$@"; do
        psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<SQL
CREATE SCHEMA ${schema} AUTHORIZATION ${migrator};
GRANT USAGE ON SCHEMA ${schema} TO ${runtime};
ALTER DEFAULT PRIVILEGES FOR ROLE ${migrator} IN SCHEMA ${schema}
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${runtime};
ALTER DEFAULT PRIVILEGES FOR ROLE ${migrator} IN SCHEMA ${schema}
    GRANT USAGE, SELECT ON SEQUENCES TO ${runtime};
SQL
    done
}

owns cryptopayment_api_migrator cryptopayment_api \
    identity merchant payments ledger treasury settlement webhooks audit
owns cryptopayment_chain_watcher_migrator cryptopayment_chain_watcher chain
owns cryptopayment_signer_migrator cryptopayment_signer signer

# The two services read each other only through views; SELECT on a view is granted by the owning service's
# migration. The signer's schema is not readable by anyone else.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<SQL
GRANT USAGE ON SCHEMA chain    TO cryptopayment_api;
GRANT USAGE ON SCHEMA treasury TO cryptopayment_chain_watcher;
SQL

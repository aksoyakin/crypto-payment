package com.aksoyakin.cryptopayment.chainwatcher.support;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * The database the tests run against: a real PostgreSQL, set up by the same script the local and production
 * stacks use, so that the roles, the schemas and their owners are the ones this service will actually meet.
 * <p>
 * Started once per JVM and migrated once. Both roles are reachable, because half of what the schema promises is
 * a privilege the application role does not have.
 */
public final class CryptoPaymentDatabase {

    private static final String IMAGE = "postgres:18.6-alpine";
    private static final String DATABASE = "cryptopayment";
    private static final String MIGRATOR = "cryptopayment_chain_watcher_migrator";
    private static final String RUNTIME = "cryptopayment_chain_watcher";
    private static final String HISTORY_SCHEMA = "chain_watcher_migrations";

    private static final String API_MIGRATOR = "cryptopayment_api_migrator";

    private static final PostgreSQLContainer POSTGRES = started();
    private static boolean migrated;
    private static boolean apiMigrated;

    private CryptoPaymentDatabase() {
    }

    public static String url() {
        return POSTGRES.getJdbcUrl();
    }

    /** A connection as the application: it owns nothing and has only what a migration granted it. */
    public static Connection asRuntime() throws SQLException {
        return DriverManager.getConnection(url(), RUNTIME, RUNTIME);
    }

    /** A connection as the owner of the schemas, which is how migrations run. */
    public static Connection asMigrator() throws SQLException {
        return DriverManager.getConnection(url(), MIGRATOR, MIGRATOR);
    }

    /** A connection as another service's role, to check what it can and cannot see. */
    public static Connection as(String role) throws SQLException {
        return DriverManager.getConnection(url(), role, role);
    }

    public static synchronized void migrate() {
        if (migrated) {
            return;
        }
        Flyway.configure()
                .dataSource(url(), MIGRATOR, MIGRATOR)
                .schemas(HISTORY_SCHEMA)
                .createSchemas(false)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        migrated = true;
    }

    /**
     * Runs the api's migrations too, from its source folder, because what the watcher reads is defined there:
     * the views over its deposit addresses and its assets. Copying their definitions into this module would
     * mean testing against a schema that drifts from the one the api actually creates.
     */
    public static synchronized void migrateApiSchemas() {
        if (apiMigrated) {
            return;
        }
        Path migrations = Path.of("..", "api", "src", "main", "resources", "db", "migration")
                .toAbsolutePath()
                .normalize();
        if (!Files.isDirectory(migrations)) {
            throw new IllegalStateException("the api's migrations are not where they were expected: " + migrations);
        }
        Flyway.configure()
                .dataSource(url(), API_MIGRATOR, API_MIGRATOR)
                .schemas("api_migrations")
                .createSchemas(false)
                .locations("filesystem:" + migrations)
                .load()
                .migrate();
        apiMigrated = true;
    }

    @SuppressWarnings("resource") // the container lives as long as the JVM running the tests
    private static PostgreSQLContainer started() {
        PostgreSQLContainer postgres = new PostgreSQLContainer(IMAGE)
                .withDatabaseName(DATABASE)
                .withUsername("postgres")
                .withPassword("postgres")
                .withCopyFileToContainer(
                        MountableFile.forHostPath(initScript()),
                        "/docker-entrypoint-initdb.d/01-roles-and-schemas.sh");
        // Every role's password is its own name here; these are only ever seen by this container.
        Map.of(
                        "API_MIGRATOR_DB_PASSWORD", "cryptopayment_api_migrator",
                        "API_DB_PASSWORD", "cryptopayment_api",
                        "CHAIN_WATCHER_MIGRATOR_DB_PASSWORD", "cryptopayment_chain_watcher_migrator",
                        "CHAIN_WATCHER_DB_PASSWORD", "cryptopayment_chain_watcher",
                        "SIGNER_MIGRATOR_DB_PASSWORD", "cryptopayment_signer_migrator",
                        "SIGNER_DB_PASSWORD", "cryptopayment_signer")
                .forEach(postgres::withEnv);

        postgres.start();
        return postgres;
    }

    private static Path initScript() {
        Path script = Path.of("..", "..", "infra", "postgres", "init", "01-roles-and-schemas.sh")
                .toAbsolutePath()
                .normalize();
        if (!Files.isRegularFile(script)) {
            throw new IllegalStateException("the database init script is not where it was expected: " + script);
        }
        return script;
    }
}

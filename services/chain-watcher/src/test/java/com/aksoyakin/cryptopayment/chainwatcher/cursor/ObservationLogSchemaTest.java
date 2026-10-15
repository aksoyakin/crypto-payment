package com.aksoyakin.cryptopayment.chainwatcher.cursor;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import com.aksoyakin.cryptopayment.chainwatcher.support.CryptoPaymentDatabase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the observation log promises: an order per chain, rows that are never rewritten, and exactly one way for
 * the api to read it (ADR 0003, ADR 0009).
 */
class ObservationLogSchemaTest {

    private static final String CHAIN = "ethereum-sepolia";
    private static final String API_ROLE = "cryptopayment_api";

    @BeforeAll
    static void migrate() {
        CryptoPaymentDatabase.migrate();
    }

    @Test
    void keepsOneOrderPerChain() throws SQLException {
        try (Connection connection = CryptoPaymentDatabase.asRuntime()) {
            long seq = nextSeq(connection);
            observe(connection, CHAIN, seq, "transfer_observed");

            assertThatThrownBy(() -> observe(connection, CHAIN, seq, "finality_changed"))
                    .as("a sequence number is taken once per chain")
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("observations_pkey");

            // The same number on another chain is a different position, which is the point of the key.
            observe(connection, "base-sepolia", seq, "transfer_observed");
        }
    }

    /**
     * A transfer removed by a reorganisation is a new row, not an edit, and a transfer seen again on another
     * branch is another observation: nothing here is ever rewritten (ADR 0003).
     */
    @Test
    void refusesToRewriteOrRemoveAnObservation() throws SQLException {
        try (Connection connection = CryptoPaymentDatabase.asRuntime()) {
            observe(connection, CHAIN, nextSeq(connection), "transfer_observed");

            assertThatThrownBy(() -> execute(connection, "UPDATE chain.observations SET finality = 'finalized'"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> execute(connection, "DELETE FROM chain.observations"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");
        }
    }

    /** A cursor is a position, so it is the one thing here that is meant to move. */
    @Test
    void letsACursorMove() throws SQLException {
        try (Connection connection = CryptoPaymentDatabase.asRuntime()) {
            execute(connection, """
                    INSERT INTO chain.cursors (chain, block_number, block_hash)
                    VALUES ('cursor-test', 1, '0xaa')
                    ON CONFLICT (chain) DO NOTHING
                    """);
            execute(connection, "UPDATE chain.cursors SET block_number = 2, block_hash = '0xbb' "
                    + "WHERE chain = 'cursor-test'");
        }
    }

    /** The contract between the two services is the view, and only the view (ADR 0003). */
    @Test
    void letsTheApiReadTheViewAndNothingElse() throws SQLException {
        try (Connection watcher = CryptoPaymentDatabase.asRuntime()) {
            observe(watcher, CHAIN, nextSeq(watcher), "transfer_observed");
        }

        try (Connection api = CryptoPaymentDatabase.as(API_ROLE)) {
            try (Statement statement = api.createStatement();
                    ResultSet rows = statement.executeQuery("SELECT count(*) FROM chain.api_observations")) {
                rows.next();
                assertThat(rows.getLong(1)).isPositive();
            }

            assertThatThrownBy(() -> execute(api, "SELECT count(*) FROM chain.observations"))
                    .as("the table behind the view is not part of the contract")
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> execute(api, """
                    INSERT INTO chain.api_observations (chain, seq, kind, tx_hash, log_index, block_number,
                                                        block_hash, to_address, asset_id, amount, finality)
                    VALUES ('x', 1, 'transfer_observed', '0x1', 0, 1, '0x2', '0x3',
                            '00000000-0000-0000-0000-000000000000', 1, 'seen')
                    """))
                    .as("the api reads observations; it does not write them")
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");
        }
    }

    /** An observation without an amount, or with a finality nobody defined, is not an observation. */
    @Test
    void refusesNonsense() throws SQLException {
        try (Connection connection = CryptoPaymentDatabase.asRuntime()) {
            assertThatThrownBy(() -> observe(connection, CHAIN, nextSeq(connection), "something_else"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("observations_kind_check");
        }
    }

    private static long nextSeq(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT coalesce(max(seq), 0) + 1 FROM chain.observations")) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static void observe(Connection connection, String chain, long seq, String kind) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chain.observations (chain, seq, kind, tx_hash, log_index, block_number, block_hash,
                                                to_address, asset_id, amount, finality)
                VALUES (?, ?, ?, ?, 0, 100, '0xblock', '0xdeposit', ?, ?, 'seen')
                """)) {
            statement.setString(1, chain);
            statement.setLong(2, seq);
            statement.setString(3, kind);
            statement.setString(4, "0x" + UUID.randomUUID().toString().replace("-", ""));
            statement.setObject(5, UUID.randomUUID());
            statement.setBigDecimal(6, new BigDecimal("100000000"));
            statement.executeUpdate();
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}

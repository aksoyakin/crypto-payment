package com.aksoyakin.cryptopayment.api.ledger;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import com.aksoyakin.cryptopayment.api.support.CryptoPaymentDatabase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the ledger promises, checked where it is enforced: in the database (ADR 0008, ADR 0013).
 * <p>
 * Every test here connects as the application's role, because that is the role a bug would be running as.
 */
class LedgerSchemaTest {

    private static final UUID USDC = UUID.randomUUID();
    private static final UUID CUSTODY = UUID.randomUUID();
    private static final UUID MERCHANT_PENDING = UUID.randomUUID();
    private static final UUID MERCHANT_AVAILABLE = UUID.randomUUID();
    private static final UUID FEE_REVENUE = UUID.randomUUID();

    @BeforeAll
    static void migrateAndSeedAccounts() throws SQLException {
        CryptoPaymentDatabase.migrate();

        UUID merchant = UUID.randomUUID();
        try (Connection connection = CryptoPaymentDatabase.asRuntime()) {
            insert(connection, """
                    INSERT INTO ledger.assets (id, chain, contract_address, symbol, decimals)
                    VALUES (?, 'ethereum-sepolia', '0x1c7d4b196cb0c7b01d743fbc6116a902379c7238', 'USDC', 6)
                    """, USDC);

            account(connection, CUSTODY, "platform:custody:USDC", "asset", null);
            account(connection, FEE_REVENUE, "platform:fee_revenue:USDC", "revenue", null);
            account(connection, MERCHANT_PENDING, "merchant:%s:live:pending:USDC".formatted(merchant), "liability",
                    merchant);
            account(connection, MERCHANT_AVAILABLE, "merchant:%s:live:available:USDC".formatted(merchant), "liability",
                    merchant);

            // Only the available balance may never go negative: a pending one is reversible (ADR 0013).
            balance(connection, MERCHANT_PENDING, false);
            balance(connection, MERCHANT_AVAILABLE, true);
        }
    }

    /** A hundred USDC paid, one of them the platform's fee: the entries of a succeeded payment. */
    @Test
    void acceptsATransactionWhoseDebitsEqualItsCredits() throws SQLException {
        UUID payment = UUID.randomUUID();

        try (Connection connection = CryptoPaymentDatabase.asRuntime()) {
            connection.setAutoCommit(false);
            UUID transaction = transaction(connection, payment, "payment_succeeded");
            entry(connection, transaction, CUSTODY, "debit", "100000000");
            entry(connection, transaction, MERCHANT_PENDING, "credit", "99000000");
            entry(connection, transaction, FEE_REVENUE, "credit", "1000000");
            connection.commit();

            assertThat(sumOf(connection, transaction)).isEqualByComparingTo("0");
        }
    }

    /**
     * The balance is checked at commit rather than per row, which is the only way entries written one after
     * another can ever balance.
     */
    @Test
    void refusesATransactionThatDoesNotBalance() throws SQLException {
        UUID payment = UUID.randomUUID();

        try (Connection connection = CryptoPaymentDatabase.asRuntime()) {
            connection.setAutoCommit(false);
            UUID transaction = transaction(connection, payment, "payment_succeeded");
            entry(connection, transaction, CUSTODY, "debit", "100000000");
            entry(connection, transaction, MERCHANT_PENDING, "credit", "98000000");

            assertThatThrownBy(connection::commit)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("does not balance");
        }
    }

    /** The same payment cannot be credited twice, however many times its observation is applied. */
    @Test
    void refusesASecondTransactionForTheSamePaymentAndKind() throws SQLException {
        UUID payment = UUID.randomUUID();

        try (Connection connection = CryptoPaymentDatabase.asRuntime()) {
            connection.setAutoCommit(false);
            UUID first = transaction(connection, payment, "payment_succeeded");
            entry(connection, first, CUSTODY, "debit", "100000000");
            entry(connection, first, MERCHANT_PENDING, "credit", "100000000");
            connection.commit();

            assertThatThrownBy(() -> transaction(connection, payment, "payment_succeeded"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("transactions_reference_type_reference_id_kind_key");
            connection.rollback();
        }
    }

    /** Append-only is a privilege, not a convention: a correction is a reversing transaction. */
    @Test
    void refusesToRewriteOrRemoveWhatWasPosted() throws SQLException {
        UUID payment = UUID.randomUUID();

        try (Connection connection = CryptoPaymentDatabase.asRuntime()) {
            connection.setAutoCommit(false);
            UUID transaction = transaction(connection, payment, "payment_succeeded");
            entry(connection, transaction, CUSTODY, "debit", "500000");
            entry(connection, transaction, MERCHANT_PENDING, "credit", "500000");
            connection.commit();

            assertThatThrownBy(() -> execute(connection, "UPDATE ledger.entries SET amount = 1"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");
            connection.rollback();

            assertThatThrownBy(() -> execute(connection, "DELETE FROM ledger.entries"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");
            connection.rollback();

            assertThatThrownBy(() -> execute(connection, "DELETE FROM ledger.transactions"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");
            connection.rollback();
        }
    }

    /** A payout can never take more than a merchant has, even if the code asks it to. */
    @Test
    void refusesToDriveAnAvailableBalanceBelowZero() throws SQLException {
        try (Connection connection = CryptoPaymentDatabase.asRuntime()) {
            assertThatThrownBy(() -> execute(connection,
                    "UPDATE ledger.balances SET amount = -1 WHERE account_id = '%s'".formatted(MERCHANT_AVAILABLE)))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("balances_non_negative");
        }
    }

    /** A pending balance is allowed to be reversed to zero, and that is the difference between the two. */
    @Test
    void letsAPendingBalanceBeReversed() throws SQLException {
        try (Connection connection = CryptoPaymentDatabase.asRuntime()) {
            execute(connection, "UPDATE ledger.balances SET amount = 99000000 WHERE account_id = '%s'"
                    .formatted(MERCHANT_PENDING));
            execute(connection, "UPDATE ledger.balances SET amount = 0 WHERE account_id = '%s'"
                    .formatted(MERCHANT_PENDING));
        }
    }

    /** The application's role is not the owner, which is what gives the privileges above their meaning. */
    @Test
    void cannotChangeTheShapeOfTheLedger() throws SQLException {
        try (Connection connection = CryptoPaymentDatabase.asRuntime()) {
            assertThatThrownBy(() -> execute(connection, "CREATE TABLE ledger.sneaky (id uuid)"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> execute(connection,
                    "ALTER TABLE ledger.entries DROP CONSTRAINT entries_amount_check"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("must be owner");
        }
    }

    private static UUID transaction(Connection connection, UUID paymentId, String kind) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ledger.transactions (id, kind, reference_type, reference_id)
                VALUES (?, ?, 'payment_intent', ?)
                """)) {
            statement.setObject(1, id);
            statement.setString(2, kind);
            statement.setObject(3, paymentId);
            statement.executeUpdate();
        }
        return id;
    }

    private static void entry(Connection connection, UUID transaction, UUID account, String direction, String amount)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ledger.entries (id, transaction_id, account_id, direction, amount)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, transaction);
            statement.setObject(3, account);
            statement.setString(4, direction);
            statement.setBigDecimal(5, new BigDecimal(amount));
            statement.executeUpdate();
        }
    }

    private static BigDecimal sumOf(Connection connection, UUID transaction) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT coalesce(sum(CASE WHEN direction = 'debit' THEN amount ELSE -amount END), 0)
                  FROM ledger.entries
                 WHERE transaction_id = ?
                """)) {
            statement.setObject(1, transaction);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getBigDecimal(1);
            }
        }
    }

    private static void account(Connection connection, UUID id, String code, String type, UUID merchant)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ledger.accounts (id, code, type, asset_id, merchant_id, mode)
                VALUES (?, ?, ?, ?, ?, 'live')
                """)) {
            statement.setObject(1, id);
            statement.setString(2, code);
            statement.setString(3, type);
            statement.setObject(4, USDC);
            statement.setObject(5, merchant);
            statement.executeUpdate();
        }
    }

    private static void balance(Connection connection, UUID account, boolean nonNegative) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ledger.balances (account_id, non_negative) VALUES (?, ?)
                """)) {
            statement.setObject(1, account);
            statement.setBoolean(2, nonNegative);
            statement.executeUpdate();
        }
    }

    private static void insert(Connection connection, String sql, UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            statement.executeUpdate();
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}

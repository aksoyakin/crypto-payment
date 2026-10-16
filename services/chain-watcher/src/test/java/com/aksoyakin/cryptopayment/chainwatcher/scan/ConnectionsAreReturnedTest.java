package com.aksoyakin.cryptopayment.chainwatcher.scan;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.aksoyakin.cryptopayment.chainwatcher.adapter.EvmNode;
import com.aksoyakin.cryptopayment.chainwatcher.adapter.JsonRpc;
import com.aksoyakin.cryptopayment.chainwatcher.adapter.LocalChain;
import com.aksoyakin.cryptopayment.chainwatcher.cursor.ChainCursors;
import com.aksoyakin.cryptopayment.chainwatcher.publish.ChainWriteLease;
import com.aksoyakin.cryptopayment.chainwatcher.publish.ObservationLog;
import com.aksoyakin.cryptopayment.chainwatcher.reorg.BlockHistory;
import com.aksoyakin.cryptopayment.chainwatcher.reorg.ReorgDetector;
import com.aksoyakin.cryptopayment.chainwatcher.support.CryptoPaymentDatabase;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A cycle gives back every connection it borrowed.
 * <p>
 * The watcher runs a cycle every couple of seconds forever, so a single connection held by mistake empties the
 * pool within a minute: the chain stops being followed and the service's own health check starts timing out
 * waiting for a connection. The pool here is deliberately small, so a leak fails this test rather than a
 * deployment.
 */
class ConnectionsAreReturnedTest {

    /** One for the write lease, which is held on purpose, and a couple for the work of a cycle. */
    private static final int POOL_SIZE = 3;
    private static final int CYCLES = 10;

    private static LocalChain chain;
    private static HikariDataSource pool;
    private static ChainFollower follower;

    @BeforeAll
    static void startEverything() throws SQLException {
        CryptoPaymentDatabase.migrate();
        CryptoPaymentDatabase.migrateApiSchemas();
        chain = new LocalChain();

        HikariConfig configuration = new HikariConfig();
        configuration.setJdbcUrl(CryptoPaymentDatabase.url());
        configuration.setUsername("cryptopayment_chain_watcher");
        configuration.setPassword("cryptopayment_chain_watcher");
        configuration.setMaximumPoolSize(POOL_SIZE);
        // A leak should fail fast here rather than hold the test for half a minute.
        configuration.setConnectionTimeout(Duration.ofSeconds(5).toMillis());
        pool = new HikariDataSource(configuration);

        watchAnAddress();
        follower = followerOn("returns-connections");
    }

    @AfterAll
    static void stopEverything() {
        if (pool != null) {
            pool.close();
        }
        if (chain != null) {
            chain.close();
        }
    }

    @Test
    void aCycleBorrowsNothingItDoesNotGiveBack() {
        // The first cycle only decides where to start following from.
        follower.cycle();

        for (int cycle = 0; cycle < CYCLES; cycle++) {
            // A block each time, so every cycle takes the path that reads the api's views and writes a
            // transaction. A cycle with nothing to do would not exercise the queries that could leak.
            chain.mine();
            assertThat(follower.cycle().state())
                    .isEqualTo(ChainFollower.Progress.State.SCANNED);
        }

        assertThat(pool.getHikariPoolMXBean().getActiveConnections())
                .as("only the write lease stays checked out after %d cycles", CYCLES)
                .isEqualTo(1);
    }

    private static ChainFollower followerOn(String name) {
        EvmNode node = new EvmNode(JsonRpc.over(
                List.of(chain.url()), Duration.ofSeconds(2), Duration.ofSeconds(10), 2, Duration.ofMillis(50)));
        JdbcClient db = JdbcClient.create(pool);
        BlockHistory history = new BlockHistory(db);
        return new ChainFollower(
                name,
                node,
                new ChainWriteLease(pool, name),
                new ChainCursors(db),
                history,
                new ObservationLog(db),
                new ReorgDetector(node, history),
                new WatchedTargets(db),
                new TransactionTemplate(new DataSourceTransactionManager(pool)),
                50,
                128);
    }

    /** A chain with something to watch, so a cycle takes the path that reads the api's views. */
    private static void watchAnAddress() throws SQLException {
        try (Connection api = CryptoPaymentDatabase.as("cryptopayment_api")) {
            try (PreparedStatement statement = api.prepareStatement("""
                    INSERT INTO ledger.assets (id, chain, contract_address, symbol, decimals)
                    VALUES (?, 'returns-connections', '0x1c7d4b196cb0c7b01d743fbc6116a902379c7238', 'TUSDC', 6)
                    """)) {
                statement.setObject(1, UUID.randomUUID());
                statement.executeUpdate();
            }
            try (PreparedStatement statement = api.prepareStatement("""
                    INSERT INTO treasury.forwarders (id, chain, intent_id, salt, address)
                    VALUES (?, 'returns-connections', ?, ?, ?)
                    """)) {
                statement.setObject(1, UUID.randomUUID());
                statement.setObject(2, UUID.randomUUID());
                statement.setString(3, "0x" + UUID.randomUUID().toString().replace("-", ""));
                statement.setString(4, LocalChain.newAccount().getAddress().toLowerCase());
                statement.executeUpdate();
            }
        }
    }
}

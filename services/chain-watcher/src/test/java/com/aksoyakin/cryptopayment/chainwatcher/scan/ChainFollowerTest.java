package com.aksoyakin.cryptopayment.chainwatcher.scan;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.aksoyakin.cryptopayment.chainwatcher.adapter.BlockTag;
import com.aksoyakin.cryptopayment.chainwatcher.adapter.EvmNode;
import com.aksoyakin.cryptopayment.chainwatcher.adapter.JsonRpc;
import com.aksoyakin.cryptopayment.chainwatcher.adapter.LocalChain;
import com.aksoyakin.cryptopayment.chainwatcher.cursor.ChainCursor;
import com.aksoyakin.cryptopayment.chainwatcher.cursor.ChainCursors;
import com.aksoyakin.cryptopayment.chainwatcher.publish.ChainWriteLease;
import com.aksoyakin.cryptopayment.chainwatcher.publish.ObservationLog;
import com.aksoyakin.cryptopayment.chainwatcher.reorg.BlockHistory;
import com.aksoyakin.cryptopayment.chainwatcher.reorg.ReorgDetector;
import com.aksoyakin.cryptopayment.chainwatcher.support.CryptoPaymentDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.web3j.crypto.Credentials;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The watcher against a real chain and a real database: what it reports, what it ignores, what it reports only
 * once, what it withdraws after a reorganisation, and what a second instance does.
 * <p>
 * Each test follows a chain of its own name, so they share the node and the database without sharing a cursor.
 */
class ChainFollowerTest {

    private static final BigInteger SUPPLY = BigInteger.valueOf(1_000_000_000L);
    private static final BigInteger AMOUNT = BigInteger.valueOf(100_000_000L); // 100 TUSDC, six decimals

    private static LocalChain chain;
    private static SimpleDriverDataSource watcherDataSource;
    private static JdbcClient db;
    private static TransactionTemplate transactions;
    private static Credentials deployer;
    private static String token;
    private static final List<ChainWriteLease> LEASES = new ArrayList<>();

    @BeforeAll
    static void startEverything() {
        CryptoPaymentDatabase.migrate();
        CryptoPaymentDatabase.migrateApiSchemas();
        chain = new LocalChain();

        watcherDataSource = new SimpleDriverDataSource(
                new Driver(), CryptoPaymentDatabase.url(), "cryptopayment_chain_watcher", "cryptopayment_chain_watcher");
        db = JdbcClient.create(watcherDataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(watcherDataSource));

        deployer = chain.fundedAccount();
        token = chain.deployTestToken(deployer, SUPPLY);
    }

    @AfterAll
    static void stopEverything() {
        LEASES.forEach(ChainWriteLease::release);
        if (chain != null) {
            chain.close();
        }
    }

    /** Nothing before the watcher started is reported: those addresses had not been handed out yet. */
    @Test
    void startsAtTheHeadOfTheChain() throws SQLException {
        String name = "start-at-head";
        watch(name, LocalChain.newAccount().getAddress());
        BigInteger head = chain.node().blockAt(BlockTag.LATEST).orElseThrow().number();

        ChainFollower.Progress first = follower(name).cycle();

        assertThat(first.state()).isEqualTo(ChainFollower.Progress.State.UP_TO_DATE);
        assertThat(cursorOf(name).blockNumber()).isEqualTo(head);
        assertThat(observationsOn(name)).isEmpty();
    }

    @Test
    void reportsATransferThatReachedAWatchedAddress() throws SQLException {
        String name = "reports-transfer";
        String deposit = LocalChain.newAccount().getAddress().toLowerCase();
        UUID asset = watch(name, deposit);
        ChainFollower follower = follower(name);
        follower.cycle();

        chain.transferToken(deployer, token, deposit, AMOUNT);
        ChainFollower.Progress progress = follower.cycle();

        assertThat(progress.state()).isEqualTo(ChainFollower.Progress.State.SCANNED);
        assertThat(progress.observations()).isEqualTo(1);

        List<Reported> reported = observationsOn(name);
        assertThat(reported).hasSize(1);
        Reported transfer = reported.get(0);
        assertThat(transfer.kind()).isEqualTo("transfer_observed");
        assertThat(transfer.toAddress()).isEqualTo(deposit);
        assertThat(transfer.amount()).isEqualByComparingTo(new BigDecimal(AMOUNT));
        assertThat(transfer.assetId()).isEqualTo(asset);
        assertThat(transfer.finality()).isEqualTo("seen");
        assertThat(transfer.seq()).isEqualTo(1L);
    }

    /** The watcher reads a token's transfers, not only the ones meant for this platform. */
    @Test
    void ignoresATransferNobodyIsWatching() throws SQLException {
        String name = "ignores-stranger";
        watch(name, LocalChain.newAccount().getAddress());
        ChainFollower follower = follower(name);
        follower.cycle();

        chain.transferToken(deployer, token, LocalChain.newAccount().getAddress(), AMOUNT);
        follower.cycle();

        assertThat(observationsOn(name)).isEmpty();
    }

    /** The cursor moves in the same transaction as the report, so a second pass has nothing left to see. */
    @Test
    void reportsATransferOnlyOnce() throws SQLException {
        String name = "reports-once";
        String deposit = LocalChain.newAccount().getAddress().toLowerCase();
        watch(name, deposit);
        ChainFollower follower = follower(name);
        follower.cycle();

        chain.transferToken(deployer, token, deposit, AMOUNT);
        follower.cycle();
        ChainFollower.Progress second = follower.cycle();

        assertThat(second.observations()).isZero();
        assertThat(observationsOn(name)).hasSize(1);
    }

    /**
     * The proof the ledger depends on: a transfer that a reorganisation removed is withdrawn, as its own
     * observation, and the cursor goes back to the last block the chain and the watcher agree on (ADR 0013).
     * <p>
     * On a chain of its own, because rewinding a chain takes back the blocks every other test built on, the
     * token's deployment included.
     */
    @Test
    void withdrawsATransferAReorganisationRemoved() throws SQLException {
        String name = "withdraws-reorg";
        String deposit = LocalChain.newAccount().getAddress().toLowerCase();

        try (LocalChain ownChain = new LocalChain()) {
            Credentials sender = ownChain.fundedAccount();
            String ownToken = ownChain.deployTestToken(sender, SUPPLY);
            watch(name, ownToken, deposit);

            ChainFollower follower = follower(name, ownChain.url());
            follower.cycle();
            // A reorganisation can only be resolved as far back as the recorded hashes reach, so the test gives
            // the watcher a history deeper than the reorganisation it then produces.
            for (int block = 0; block < 5; block++) {
                ownChain.mine();
            }
            follower.cycle();

            ownChain.transferToken(sender, ownToken, deposit, AMOUNT);
            follower.cycle();
            BigInteger reportedAt = observationsOn(name).get(0).blockNumber().toBigInteger();

            ownChain.reorganise(2);
            ChainFollower.Progress progress = follower.cycle();

            assertThat(progress.state()).isEqualTo(ChainFollower.Progress.State.WITHDRAWN);
            List<Reported> reported = observationsOn(name);
            assertThat(reported).hasSize(2);
            assertThat(reported.get(1).kind()).isEqualTo("transfer_retracted");
            assertThat(reported.get(1).toAddress()).isEqualTo(deposit);
            assertThat(reported.get(1).amount()).isEqualByComparingTo(reported.get(0).amount());
            assertThat(cursorOf(name).blockNumber()).isLessThan(reportedAt);

            // The withdrawal is reported once, however many cycles follow.
            follower.cycle();
            assertThat(observationsOn(name).stream().filter(o -> o.kind().equals("transfer_retracted")))
                    .hasSize(1);
        }
    }

    /**
     * Two instances follow the same chain during a deploy. Only one may write, or the order of the log would
     * not be an order at all (ADR 0009, ADR 0015).
     */
    @Test
    void onlyOneInstanceWritesAChain() throws SQLException {
        String name = "one-writer";
        watch(name, LocalChain.newAccount().getAddress());

        ChainFollower first = follower(name);
        ChainFollower second = follower(name);

        assertThat(first.cycle().state()).isNotEqualTo(ChainFollower.Progress.State.NOT_WRITING);
        assertThat(second.cycle().state()).isEqualTo(ChainFollower.Progress.State.NOT_WRITING);

        assertThat(cursorOf(name)).isNotNull();
    }

    private static ChainFollower follower(String name) {
        return follower(name, chain.url());
    }

    /** A follower of its own, so that one test's lease does not decide another's. */
    private static ChainFollower follower(String name, String rpcUrl) {
        ChainWriteLease lease = new ChainWriteLease(watcherDataSource, name);
        LEASES.add(lease);
        EvmNode node = new EvmNode(JsonRpc.over(
                List.of(rpcUrl),
                java.time.Duration.ofSeconds(2),
                java.time.Duration.ofSeconds(10),
                2,
                java.time.Duration.ofMillis(50)));
        BlockHistory history = new BlockHistory(db);
        return new ChainFollower(
                name,
                node,
                lease,
                new ChainCursors(db),
                history,
                new ObservationLog(db),
                new ReorgDetector(node, history),
                new WatchedTargets(db),
                transactions,
                50,
                128);
    }

    private static UUID watch(String name, String depositAddress) throws SQLException {
        return watch(name, token, depositAddress);
    }

    /** Puts a token and a deposit address in front of the watcher, the way the api would. */
    private static UUID watch(String name, String tokenAddress, String depositAddress) throws SQLException {
        UUID asset = UUID.randomUUID();
        try (Connection api = CryptoPaymentDatabase.as("cryptopayment_api")) {
            try (PreparedStatement statement = api.prepareStatement("""
                    INSERT INTO ledger.assets (id, chain, contract_address, symbol, decimals)
                    VALUES (?, ?, ?, 'TUSDC', 6)
                    """)) {
                statement.setObject(1, asset);
                statement.setString(2, name);
                statement.setString(3, tokenAddress);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = api.prepareStatement("""
                    INSERT INTO treasury.forwarders (id, chain, intent_id, salt, address)
                    VALUES (?, ?, ?, ?, ?)
                    """)) {
                statement.setObject(1, UUID.randomUUID());
                statement.setString(2, name);
                statement.setObject(3, UUID.randomUUID());
                statement.setString(4, "0x" + UUID.randomUUID().toString().replace("-", ""));
                statement.setString(5, depositAddress.toLowerCase());
                statement.executeUpdate();
            }
        }
        return asset;
    }

    private static ChainCursor cursorOf(String name) {
        return new ChainCursors(db).of(name).orElseThrow();
    }

    private static List<Reported> observationsOn(String name) throws SQLException {
        try (Connection api = CryptoPaymentDatabase.as("cryptopayment_api");
                PreparedStatement statement = api.prepareStatement("""
                        SELECT seq, kind, to_address, asset_id, amount, finality, block_number
                          FROM chain.api_observations
                         WHERE chain = ?
                         ORDER BY seq
                        """)) {
            statement.setString(1, name);
            try (ResultSet rows = statement.executeQuery()) {
                List<Reported> reported = new ArrayList<>();
                while (rows.next()) {
                    reported.add(new Reported(
                            rows.getLong("seq"),
                            rows.getString("kind"),
                            rows.getString("to_address"),
                            rows.getObject("asset_id", UUID.class),
                            rows.getBigDecimal("amount"),
                            rows.getString("finality"),
                            rows.getBigDecimal("block_number")));
                }
                return reported;
            }
        }
    }

    /** An observation as the api sees it: read through the view, with the api's own role. */
    private record Reported(
            long seq,
            String kind,
            String toAddress,
            UUID assetId,
            BigDecimal amount,
            String finality,
            BigDecimal blockNumber) {
    }
}

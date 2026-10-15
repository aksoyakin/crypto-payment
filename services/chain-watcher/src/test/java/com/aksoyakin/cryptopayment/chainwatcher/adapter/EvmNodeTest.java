package com.aksoyakin.cryptopayment.chainwatcher.adapter;

import java.math.BigInteger;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Keys;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * What the watcher needs from a node, proven against a real one: reading blocks and logs, and a transaction
 * this project signed itself being accepted.
 * <p>
 * The node is Anvil, pinned to the same image as {@code infra/docker-compose.yml}. Keys are generated in the
 * test, so none is written down anywhere.
 */
class EvmNodeTest {

    private static final BigInteger CHAIN_ID = BigInteger.valueOf(31337);
    private static final int BLOCK_TIME_SECONDS = 2;
    private static final BigInteger GAS_PRICE = BigInteger.valueOf(2_000_000_000L);
    private static final BigInteger GAS_LIMIT = BigInteger.valueOf(21_000);
    private static final BigInteger ONE_ETHER = BigInteger.TEN.pow(18);

    @SuppressWarnings("resource") // closed in stopChain()
    private static final GenericContainer<?> ANVIL = new GenericContainer<>("ghcr.io/foundry-rs/foundry:v1.8.4")
            // The image's entrypoint is "/bin/sh -c", so the whole command has to arrive as one argument. Passed
            // as a plain string it would be split on spaces, and anvil would start with its defaults: bound to
            // localhost, where no mapped port can reach it.
            .withCommand(new String[] {"anvil --host 0.0.0.0 --port 8545 --chain-id %s --block-time %d"
                    .formatted(CHAIN_ID, BLOCK_TIME_SECONDS)})
            .withExposedPorts(8545)
            .waitingFor(Wait.forListeningPort());

    private static JsonRpc rpc;
    private static EvmNode node;

    @BeforeAll
    static void startChain() {
        ANVIL.start();
        rpc = JsonRpc.over(
                "http://%s:%d".formatted(ANVIL.getHost(), ANVIL.getMappedPort(8545)),
                Duration.ofSeconds(2),
                Duration.ofSeconds(10));
        node = new EvmNode(rpc);
    }

    @AfterAll
    static void stopChain() {
        ANVIL.stop();
    }

    @Test
    void readsTheChainIdAndFollowsNewBlocks() {
        assertThat(node.chainId()).isEqualTo(CHAIN_ID);

        BigInteger start = node.blockNumber();
        await().atMost(Duration.ofSeconds(4L * BLOCK_TIME_SECONDS))
                .pollInterval(Duration.ofMillis(500))
                .until(() -> node.blockNumber().compareTo(start) > 0);

        ChainBlock block = node.blockAt(BlockTag.LATEST).orElseThrow();
        assertThat(block.hash()).isNotBlank();
        assertThat(block.parentHash()).isNotBlank();
        assertThat(block.timestamp()).isAfter(java.time.Instant.EPOCH);
        assertThat(node.blockAt(block.number()).orElseThrow().hash()).isEqualTo(block.hash());
    }

    /** A block that does not exist is an empty answer, not an error: the watcher polls ahead of the chain. */
    @Test
    void reportsAnUnknownBlockAsAbsent() {
        assertThat(node.blockAt(node.blockNumber().add(BigInteger.valueOf(1_000)))).isEmpty();
    }

    /** A receipt that is not there yet is the same shape of answer, which is how broadcasting waits. */
    @Test
    void reportsAnUnknownReceiptAsAbsent() {
        assertThat(node.receiptOf("0x" + "11".repeat(32))).isEmpty();
    }

    /**
     * Flushes and payouts are signed in Java (ADR 0006, ADR 0014), so the signature this JDK's crypto produces
     * has to be one the node accepts.
     */
    @Test
    void signsAndSendsATransactionThatTheChainAccepts() throws Exception {
        Credentials sender = fundedAccount(ONE_ETHER.multiply(BigInteger.TEN));
        String recipient = Credentials.create(Keys.createEcKeyPair()).getAddress();

        RawTransaction transfer = RawTransaction.createEtherTransaction(
                node.transactionCount(sender.getAddress(), BlockTag.LATEST),
                GAS_PRICE,
                GAS_LIMIT,
                recipient,
                ONE_ETHER);
        String hash = node.sendRawTransaction(
                Hex.of(TransactionEncoder.signMessage(transfer, CHAIN_ID.longValueExact(), sender)));

        TransactionReceipt receipt = await().atMost(Duration.ofSeconds(4L * BLOCK_TIME_SECONDS))
                .pollInterval(Duration.ofMillis(500))
                .until(() -> node.receiptOf(hash).orElse(null), r -> r != null);

        assertThat(receipt.successful()).isTrue();
        assertThat(receipt.transactionHash()).isEqualTo(hash);
        assertThat(receipt.gasUsed()).isEqualTo(GAS_LIMIT);
        assertThat(node.balanceOf(recipient, BlockTag.LATEST)).isEqualTo(ONE_ETHER);
    }

    /**
     * Token transfers are detected by reading a contract's logs for a block range and matching recipients in
     * memory (ADR 0004). The range query is what this asserts; the matching comes with the watcher.
     */
    @Test
    void readsLogsForABlockRange() {
        assertThat(node.logs(BigInteger.ZERO, node.blockNumber(), List.of(), List.of())).isEmpty();
    }

    /**
     * Confirmation is decided by the {@code safe} and {@code finalized} tags rather than a block count
     * (ADR 0004). Anvil answers both with the head of the chain, so a local node cannot exercise a finality
     * threshold: the tests for that need a lever of their own. This fails the day Anvil's behaviour changes.
     */
    @Test
    void anvilFinalisesEveryBlockImmediately() {
        BigInteger latest = node.blockAt(BlockTag.LATEST).orElseThrow().number();

        assertThat(node.blockAt(BlockTag.SAFE).orElseThrow().number())
                .as("safe trails the head by at most one block")
                .isBetween(latest.subtract(BigInteger.ONE), latest.add(BigInteger.ONE));
        assertThat(node.blockAt(BlockTag.FINALIZED).orElseThrow().number())
                .as("finalized trails the head by at most one block")
                .isBetween(latest.subtract(BigInteger.ONE), latest.add(BigInteger.ONE));
    }

    /** A node that refuses is told apart from a node that cannot be reached. */
    @Test
    void reportsANodeErrorAsSuch() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> node.sendRawTransaction("0xdeadbeef"))
                .isInstanceOf(JsonRpcException.class)
                .hasMessageContaining("eth_sendRawTransaction");
    }

    /** Anvil holds the keys of its own funded accounts; a fresh account is funded through its test RPC. */
    private static Credentials fundedAccount(BigInteger wei) throws Exception {
        Credentials credentials = Credentials.create(Keys.createEcKeyPair());
        rpc.call(
                "anvil_setBalance",
                List.of(credentials.getAddress(), Hex.of(wei)),
                new ParameterizedTypeReference<JsonRpc.Result<Object>>() {
                });
        assertThat(node.balanceOf(credentials.getAddress(), BlockTag.LATEST)).isEqualTo(wei);
        return credentials;
    }
}

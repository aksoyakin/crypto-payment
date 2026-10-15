package com.aksoyakin.cryptopayment.chainwatcher.adapter;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What happens when a node cannot be reached. A chain may be configured with more than one provider, and the
 * watcher's progress should not depend on the first one being up (ADR 0022).
 */
class JsonRpcFailoverTest {

    /** Nothing listens here; a connection is refused immediately rather than hanging. */
    private static final String DEAD = "http://127.0.0.1:1";

    private static LocalChain chain;

    @BeforeAll
    static void startChain() {
        chain = new LocalChain();
    }

    @AfterAll
    static void stopChain() {
        chain.close();
    }

    @Test
    void movesOnToTheNextEndpoint() {
        EvmNode node = new EvmNode(JsonRpc.over(
                List.of(DEAD, chain.url()), Duration.ofSeconds(1), Duration.ofSeconds(5), 2, Duration.ZERO));

        assertThat(node.chainId()).isEqualTo(LocalChain.CHAIN_ID);
    }

    @Test
    void failsWhenNoEndpointAnswers() {
        EvmNode node = new EvmNode(JsonRpc.over(
                List.of(DEAD, DEAD), Duration.ofSeconds(1), Duration.ofSeconds(5), 2, Duration.ZERO));

        assertThatThrownBy(node::blockNumber)
                .isInstanceOf(NodeUnreachableException.class)
                .hasMessageContaining("eth_blockNumber")
                // Every attempt is kept, so a log shows what each endpoint said.
                .satisfies(failure -> assertThat(failure.getSuppressed()).hasSize(4));
    }

    /** A node that answers and refuses is not a transport problem, so it is not tried again. */
    @Test
    void doesNotRetryARefusal() {
        EvmNode node = new EvmNode(JsonRpc.over(
                List.of(chain.url()), Duration.ofSeconds(1), Duration.ofSeconds(5), 3, Duration.ZERO));

        assertThatThrownBy(() -> node.sendRawTransaction("0xdeadbeef"))
                .isInstanceOf(JsonRpcException.class);
    }

    @Test
    void needsAtLeastOneEndpoint() {
        assertThatThrownBy(() -> JsonRpc.over(List.of(), Duration.ofSeconds(1), Duration.ofSeconds(1), 1, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

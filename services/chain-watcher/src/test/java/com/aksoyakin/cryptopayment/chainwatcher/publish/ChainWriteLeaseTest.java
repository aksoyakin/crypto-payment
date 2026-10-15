package com.aksoyakin.cryptopayment.chainwatcher.publish;

import com.aksoyakin.cryptopayment.chainwatcher.support.CryptoPaymentDatabase;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lock that makes "one writer per chain" a property of the database rather than of deployment discipline
 * (ADR 0015).
 */
class ChainWriteLeaseTest {

    private static final SimpleDriverDataSource DATA_SOURCE = new SimpleDriverDataSource(
            new Driver(),
            CryptoPaymentDatabase.url(),
            "cryptopayment_chain_watcher",
            "cryptopayment_chain_watcher");

    @Test
    void onlyOneLeaseIsHeldAtATime() {
        try (ChainWriteLease first = new ChainWriteLease(DATA_SOURCE, "contested-chain");
                ChainWriteLease second = new ChainWriteLease(DATA_SOURCE, "contested-chain")) {

            assertThat(first.acquired()).isTrue();
            assertThat(second.acquired()).as("a second watcher of the same chain does not write").isFalse();

            // Asking again does not lose what is already held.
            assertThat(first.acquired()).isTrue();
        }
    }

    /** A watcher that stops frees its chain, which is how a deploy hands it over. */
    @Test
    void releasingLetsTheNextWatcherIn() {
        ChainWriteLease leaving = new ChainWriteLease(DATA_SOURCE, "handover-chain");
        assertThat(leaving.acquired()).isTrue();

        try (ChainWriteLease arriving = new ChainWriteLease(DATA_SOURCE, "handover-chain")) {
            assertThat(arriving.acquired()).isFalse();
            leaving.release();
            assertThat(arriving.acquired()).isTrue();
        }
    }

    /** Two chains are followed at the same time; the lock is per chain, not per watcher. */
    @Test
    void differentChainsDoNotContend() {
        try (ChainWriteLease one = new ChainWriteLease(DATA_SOURCE, "chain-one");
                ChainWriteLease another = new ChainWriteLease(DATA_SOURCE, "chain-two")) {

            assertThat(one.acquired()).isTrue();
            assertThat(another.acquired()).isTrue();
        }
    }

    @Test
    void aChainsLockKeyIsStable() {
        assertThat(ChainWriteLease.lockKeyFor("ethereum-sepolia"))
                .isEqualTo(ChainWriteLease.lockKeyFor("ethereum-sepolia"))
                .isNotEqualTo(ChainWriteLease.lockKeyFor("base-sepolia"));
    }
}

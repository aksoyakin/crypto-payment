package com.aksoyakin.cryptopayment.chainwatcher.config;

import java.util.List;

import com.aksoyakin.cryptopayment.chainwatcher.scan.ChainFollower;
import com.aksoyakin.cryptopayment.chainwatcher.support.CryptoPaymentDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A deployment that has no RPC provider yet. The service has to start and simply follow nothing: a container
 * that refuses to start would take the whole stack's deploy with it.
 */
@SpringBootTest(properties = {"cryptopayment.watcher.scheduled=false", "CHAIN_RPC_URLS="})
class UnconfiguredChainTest {

    @Autowired
    private List<ChainFollower> followers;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", CryptoPaymentDatabase::url);
        properties.add("spring.datasource.username", () -> "cryptopayment_chain_watcher");
        properties.add("spring.datasource.password", () -> "cryptopayment_chain_watcher");
        properties.add("spring.flyway.url", CryptoPaymentDatabase::url);
        properties.add("spring.flyway.user", () -> "cryptopayment_chain_watcher_migrator");
        properties.add("spring.flyway.password", () -> "cryptopayment_chain_watcher_migrator");
    }

    @Test
    void followsNothingAndStartsAnyway() {
        assertThat(followers).isEmpty();
    }
}

package com.aksoyakin.cryptopayment.chainwatcher;

import com.aksoyakin.cryptopayment.chainwatcher.support.CryptoPaymentDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The application starts, which now includes running its migrations as the migrator role and connecting as the
 * runtime role.
 */
@SpringBootTest
class ChainWatcherApplicationTests {

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
    void contextLoads() {
    }
}

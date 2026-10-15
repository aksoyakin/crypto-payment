package com.aksoyakin.cryptopayment.api;

import com.aksoyakin.cryptopayment.api.support.CryptoPaymentDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The application starts, which now includes running its migrations as the migrator role and connecting as the
 * runtime role: the two-role setup is part of what has to work for the service to come up.
 */
@SpringBootTest
class ApiApplicationTests {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", CryptoPaymentDatabase::url);
        properties.add("spring.datasource.username", () -> "cryptopayment_api");
        properties.add("spring.datasource.password", () -> "cryptopayment_api");
        properties.add("spring.flyway.url", CryptoPaymentDatabase::url);
        properties.add("spring.flyway.user", () -> "cryptopayment_api_migrator");
        properties.add("spring.flyway.password", () -> "cryptopayment_api_migrator");
    }

    @Test
    void contextLoads() {
    }
}

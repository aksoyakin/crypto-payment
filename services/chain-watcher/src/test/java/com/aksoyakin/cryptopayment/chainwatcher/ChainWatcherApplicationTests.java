package com.aksoyakin.cryptopayment.chainwatcher;

import javax.sql.DataSource;

import com.aksoyakin.cryptopayment.chainwatcher.support.CryptoPaymentDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The application starts, and starting it brings its own schema with it.
 * <p>
 * The schema assertions are not decoration. Spring Boot 4 splits auto-configuration into modules, so Flyway on
 * the classpath is not enough to make Boot run it: without {@code spring-boot-starter-flyway} the service
 * starts happily and its migrations never run, which is only noticed when the first query hits a table that
 * does not exist.
 */
@SpringBootTest(properties = "cryptopayment.watcher.scheduled=false")
class ChainWatcherApplicationTests {

    @Autowired
    private DataSource dataSource;

    @Autowired(required = false)
    private Flyway flyway;

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
        assertThat(dataSource).isNotNull();
    }

    @Test
    void bringsItsSchemaWithIt() {
        assertThat(flyway).as("Boot has to be the one running the migrations").isNotNull();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("1");

        // The application's own role, reading what the migration created: both halves have to be right.
        assertThat(JdbcClient.create(dataSource)
                        .sql("SELECT count(*) FROM chain.cursors")
                        .query(Long.class)
                        .single())
                .isNotNull();
    }
}

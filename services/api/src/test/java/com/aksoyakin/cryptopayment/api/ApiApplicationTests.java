package com.aksoyakin.cryptopayment.api;

import javax.sql.DataSource;

import com.aksoyakin.cryptopayment.api.support.CryptoPaymentDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The application starts, and starting it brings its own schema with it: migrations run as the migrator role,
 * the application then connects as a role that owns nothing.
 * <p>
 * Spring Boot 4 splits auto-configuration into modules, so Flyway on the classpath is not enough for Boot to
 * run it. Without {@code spring-boot-starter-flyway} the service starts happily and no migration ever runs, so
 * these assertions are what stands between that and a deployment with no tables in it.
 */
@SpringBootTest
class ApiApplicationTests {

    @Autowired
    private DataSource dataSource;

    @Autowired(required = false)
    private Flyway flyway;

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
        assertThat(dataSource).isNotNull();
    }

    @Test
    void bringsItsSchemaWithIt() {
        assertThat(flyway).as("Boot has to be the one running the migrations").isNotNull();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("2");

        JdbcClient db = JdbcClient.create(dataSource);
        assertThat(db.sql("SELECT count(*) FROM ledger.entries").query(Long.class).single()).isNotNull();
        assertThat(db.sql("SELECT count(*) FROM treasury.watcher_addresses").query(Long.class).single()).isNotNull();
    }
}

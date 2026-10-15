package com.aksoyakin.cryptopayment.chainwatcher.config;

import java.util.List;

import javax.sql.DataSource;

import com.aksoyakin.cryptopayment.chainwatcher.adapter.EvmNode;
import com.aksoyakin.cryptopayment.chainwatcher.adapter.JsonRpc;
import com.aksoyakin.cryptopayment.chainwatcher.cursor.ChainCursors;
import com.aksoyakin.cryptopayment.chainwatcher.publish.ChainWriteLease;
import com.aksoyakin.cryptopayment.chainwatcher.publish.ObservationLog;
import com.aksoyakin.cryptopayment.chainwatcher.reorg.BlockHistory;
import com.aksoyakin.cryptopayment.chainwatcher.reorg.ReorgDetector;
import com.aksoyakin.cryptopayment.chainwatcher.scan.ChainFollower;
import com.aksoyakin.cryptopayment.chainwatcher.scan.WatchedTargets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wires one follower per configured chain. The components are plain objects with constructors, so a test can
 * build a follower against a container without a Spring context.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WatcherProperties.class)
@EnableScheduling
public class WatcherConfiguration {

    private static final Logger log = LoggerFactory.getLogger(WatcherConfiguration.class);

    @Bean
    JdbcClient jdbcClient(DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager transactions) {
        return new TransactionTemplate(transactions);
    }

    @Bean
    List<ChainFollower> chainFollowers(
            WatcherProperties properties,
            DataSource dataSource,
            JdbcClient db,
            TransactionTemplate transactions) {
        ChainCursors cursors = new ChainCursors(db);
        BlockHistory history = new BlockHistory(db);
        ObservationLog observations = new ObservationLog(db);
        WatchedTargets targets = new WatchedTargets(db);

        return properties.chains().stream()
                .filter(chain -> {
                    if (chain.rpcUrls() == null || chain.rpcUrls().isEmpty()) {
                        // A chain nobody gave a node for is not followed. Starting up anyway keeps a service
                        // whose other chains work, and makes "not configured yet" a visible state rather than
                        // a container that will not start.
                        log.warn("{} has no node endpoint configured and is not followed", chain.id());
                        return false;
                    }
                    return true;
                })
                .map(chain -> {
                    EvmNode node = new EvmNode(JsonRpc.over(
                            chain.rpcUrls(),
                            properties.rpc().connectTimeout(),
                            properties.rpc().readTimeout(),
                            properties.rpc().attemptsPerEndpoint(),
                            properties.rpc().backoff()));
                    return new ChainFollower(
                            chain.id(),
                            node,
                            new ChainWriteLease(dataSource, chain.id()),
                            cursors,
                            history,
                            observations,
                            new ReorgDetector(node, history),
                            targets,
                            transactions,
                            properties.blockWindow(),
                            properties.blockHistory());
                })
                .toList();
    }
}

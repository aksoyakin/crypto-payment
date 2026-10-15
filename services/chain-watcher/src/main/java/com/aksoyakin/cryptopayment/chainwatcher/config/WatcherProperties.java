package com.aksoyakin.cryptopayment.chainwatcher.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * What to follow, and how hard to try.
 *
 * @param pollInterval how often a chain is looked at; a block time is the natural order of magnitude
 * @param blockWindow  how many blocks one cycle may read at once, which bounds both the log query and the
 *                     headers fetched while catching up
 * @param blockHistory how far back block hashes are kept, and therefore the deepest reorganisation that can
 *                     still be resolved without a person looking
 * @param rpc          how calls to a node behave
 * @param chains       the chains this instance follows; each is written by whoever holds its lock (ADR 0015)
 */
@ConfigurationProperties("cryptopayment.watcher")
public record WatcherProperties(
        @DefaultValue("2s") Duration pollInterval,
        @DefaultValue("50") int blockWindow,
        @DefaultValue("128") int blockHistory,
        @DefaultValue Rpc rpc,
        @DefaultValue List<Chain> chains) {

    /**
     * @param id      the chain's name, used in every row the watcher writes and in its lock key
     * @param rpcUrls the chain's node endpoints, tried in the order given
     */
    public record Chain(String id, List<String> rpcUrls) {
    }

    public record Rpc(
            @DefaultValue("2s") Duration connectTimeout,
            @DefaultValue("10s") Duration readTimeout,
            @DefaultValue("3") int attemptsPerEndpoint,
            @DefaultValue("250ms") Duration backoff) {
    }
}

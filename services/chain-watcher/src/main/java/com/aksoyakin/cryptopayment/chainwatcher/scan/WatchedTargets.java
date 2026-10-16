package com.aksoyakin.cryptopayment.chainwatcher.scan;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * What to watch on a chain, read from the api's views: the deposit addresses it has handed out, and the tokens
 * whose transfers matter. Views are the whole contract between the two services (ADR 0003).
 */
public final class WatchedTargets {

    private final JdbcClient db;

    public WatchedTargets(JdbcClient db) {
        this.db = db;
    }

    /**
     * Lower case, because that is the one form addresses are stored and compared in.
     * <p>
     * Read into a list and then mapped: a {@code JdbcClient} stream is lazy and holds its connection until the
     * stream is closed, which is a connection leaked per call for anything that does not close it.
     */
    public Set<String> addressesOn(String chain) {
        return db.sql("SELECT address FROM treasury.watcher_addresses WHERE chain = ?")
                .param(chain)
                .query(String.class)
                .list()
                .stream()
                .map(String::toLowerCase)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Only tokens: a native coin has no contract and no transfer log, so it is detected from transactions
     * instead, which is not part of this milestone (ADR 0004).
     */
    public List<WatchedAsset> tokensOn(String chain) {
        return db.sql("""
                        SELECT contract_address, asset_id, decimals
                          FROM treasury.watcher_assets
                         WHERE chain = ? AND contract_address IS NOT NULL
                         ORDER BY contract_address
                        """)
                .param(chain)
                .query((rs, row) -> new WatchedAsset(
                        rs.getString("contract_address").toLowerCase(),
                        rs.getObject("asset_id", UUID.class),
                        rs.getInt("decimals")))
                .list();
    }

    public record WatchedAsset(String contractAddress, UUID assetId, int decimals) {
    }
}

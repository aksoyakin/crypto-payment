package com.aksoyakin.cryptopayment.chainwatcher.publish;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The right to write a chain's observations, held as a PostgreSQL advisory lock on a connection of its own
 * (ADR 0015).
 * <p>
 * The order of observations is only correct while one watcher writes a chain, and during a deploy the old and
 * the new container overlap for a moment, which is exactly when a second writer would appear. The database
 * decides who writes rather than deployment discipline, and because the lock belongs to a session, a watcher
 * that crashes frees its chain without anyone intervening.
 * <p>
 * Not thread-safe: one lease belongs to one follower.
 */
public final class ChainWriteLease implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ChainWriteLease.class);

    private final DataSource dataSource;
    private final String chain;
    private final long lockKey;

    private Connection session;

    public ChainWriteLease(DataSource dataSource, String chain) {
        this.dataSource = dataSource;
        this.chain = chain;
        this.lockKey = lockKeyFor(chain);
    }

    /**
     * @return true when this watcher may write the chain. Taking the lock is attempted only while it is not
     *         already held; a connection that died releases the lock, so it is dropped and tried again.
     */
    public boolean acquired() {
        if (session != null) {
            if (alive()) {
                return true;
            }
            log.warn("the lease connection for {} is gone; it stops writing until the lock is taken again", chain);
            release();
        }
        return tryAcquire();
    }

    private boolean tryAcquire() {
        Connection candidate = null;
        try {
            candidate = dataSource.getConnection();
            candidate.setAutoCommit(true);
            try (PreparedStatement statement = candidate.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                statement.setLong(1, lockKey);
                try (ResultSet answer = statement.executeQuery()) {
                    answer.next();
                    if (answer.getBoolean(1)) {
                        session = candidate;
                        log.info("following {}: the write lock is held by this instance", chain);
                        return true;
                    }
                }
            }
            // Someone else follows this chain. Nothing is wrong; this instance simply does not write.
            candidate.close();
            return false;
        } catch (SQLException failure) {
            log.warn("could not take the write lock for {}: {}", chain, failure.getMessage());
            closeQuietly(candidate);
            return false;
        }
    }

    /** True while the session that holds the lock is still usable. */
    private boolean alive() {
        try {
            return session.isValid(1);
        } catch (SQLException failure) {
            return false;
        }
    }

    public void release() {
        Connection held = session;
        session = null;
        if (held == null) {
            return;
        }
        // Closing the connection is what releases the lock; unlocking first keeps a pooled connection reusable.
        try (PreparedStatement statement = held.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            statement.setLong(1, lockKey);
            statement.execute();
        } catch (SQLException failure) {
            log.debug("unlocking {} failed, closing the connection instead: {}", chain, failure.getMessage());
        }
        closeQuietly(held);
    }

    @Override
    public void close() {
        release();
    }

    /**
     * A chain's name turned into the bigint the lock is taken on. Derived in Java rather than with the
     * database's own hash function, so the key does not depend on a PostgreSQL version.
     */
    static long lockKeyFor(String chain) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(("cryptopayment.chain:" + chain).getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest, 0, Long.BYTES).getLong();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is part of every JDK", impossible);
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Nothing useful can be done; the lock is released when the session ends either way.
        }
    }
}

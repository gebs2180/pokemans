package com.pokemans.app.pricing;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * One pricing refresh at a time across every instance: the nightly job and the admin's refresh
 * button take the same Postgres advisory lock, so they can't write two snapshot sets for one card
 * and day side by side. The lock is held on a connection of its own for the length of the refresh
 * (which runs several transactions of its own), and goes away with that connection if the instance
 * dies, so a crash can't leave it stuck.
 */
@Component
class PricingLock {

    /** Advisory lock key: "pokepric". */
    static final long KEY = 0x706f6b65_70726963L;

    /** Thrown when another refresh holds the lock; an HTTP caller gets 409. */
    static final class Busy extends ResponseStatusException {
        Busy() {
            super(HttpStatus.CONFLICT, "A pricing refresh is already running; try again when it has finished");
        }
    }

    private final DataSource dataSource;

    PricingLock(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Runs {@code work} holding the lock.
     *
     * @throws Busy if another refresh is running
     */
    <T> T run(Supplier<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            if (!call(connection, "select pg_try_advisory_lock(?)")) {
                throw new Busy();
            }
            try {
                return work.get();
            } finally {
                call(connection, "select pg_advisory_unlock(?)");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Pricing lock: " + e.getMessage(), e);
        }
    }

    private static boolean call(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, KEY);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }
}

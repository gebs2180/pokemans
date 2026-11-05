package com.pokemans.app.offers;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * One writer at a time per card on the market: posting, withdrawing, taking, buying the label, recording
 * an arrival, payment or return, voiding, cancelling and a doubt all take this Postgres advisory lock
 * on the card first, then read what the history says and append. The lock is the transaction's
 * ({@code pg_advisory_xact_lock}), so it goes with the commit or rollback and must be taken inside one;
 * every instance shares it.
 */
@Component
class ListingLock {

    private final JdbcTemplate jdbc;

    ListingLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Waits for the card's listing lock and holds it until the caller's transaction ends. */
    @Transactional(propagation = Propagation.MANDATORY)
    void lock(UUID cardItemId) {
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtext(?))", Object.class, key(cardItemId));
    }

    static String key(UUID cardItemId) {
        return "listing:" + cardItemId;
    }
}

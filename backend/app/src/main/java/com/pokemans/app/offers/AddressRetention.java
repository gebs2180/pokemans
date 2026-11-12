package com.pokemans.app.offers;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps an address only as long as a claim could need it (privacy review 2026-11-12; both periods are settings and
 * defaults to confirm). An org's newest address is never touched: it's the one the next label uses.
 * <ul>
 *   <li>An address replaced before any label used it is deleted {@code pokemans.retention.replaced-address-days} (30)
 *       after it was replaced.</li>
 *   <li>An address a label used is anonymised once every deal on a label using it is closed, and the latest closed
 *       more than {@code pokemans.retention.label-address-days} (365) ago: name, company, street, city, phone and the
 *       payment handle go, and state, zip and country stay. The row stays, because the labels point at it.</li>
 * </ul>
 * A deal is closed when it was cancelled, or when it was settled (payment confirmed, the card back with the seller, an
 * admin resolution, or an older deal's recorded arrival or payment) with no return or dispute after the last payment
 * confirmation or resolution. It closed at its last event. Anything else keeps its addresses.
 */
@Component
public class AddressRetention {

    private static final Logger log = LoggerFactory.getLogger(AddressRetention.class);

    /** What one run did. */
    public record Result(int deleted, int anonymised) {
    }

    private final JdbcClient jdbc;
    private final Duration replacedKeep;
    private final Duration labelKeep;

    AddressRetention(JdbcClient jdbc,
                     @Value("${pokemans.retention.replaced-address-days:30}") int replacedDays,
                     @Value("${pokemans.retention.label-address-days:365}") int labelDays) {
        this.jdbc = jdbc;
        this.replacedKeep = Duration.of(replacedDays, ChronoUnit.DAYS);
        this.labelKeep = Duration.of(labelDays, ChronoUnit.DAYS);
    }

    @Transactional
    public Result run(Instant now) {
        int deleted = jdbc.sql("""
                        delete from ship_address a
                        where not exists (select 1 from shipping_label l where l.from_address_id = a.id or l.to_address_id = a.id)
                          and exists (select 1 from ship_address newer
                                      where newer.org_id = a.org_id and newer.created_at > a.created_at and newer.created_at < :cutoff)""")
                .param("cutoff", Timestamp.from(now.minus(replacedKeep)))
                .update();
        int anonymised = jdbc.sql("""
                        with deal as (
                            select l.from_address_id, l.to_address_id,
                                   (select max(e.created_at) from listing_event e where e.listing_id = l.listing_id) as last_event_at,
                                   exists (select 1 from listing_event e where e.listing_id = l.listing_id and e.type = 'CANCELLED') as cancelled,
                                   exists (select 1 from listing_event e where e.listing_id = l.listing_id
                                           and (e.type in ('PAYMENT_CONFIRMED', 'RESOLVED', 'CARD_BACK', 'INSPECTED')
                                                or (e.type = 'RECEIVED' and e.price_paid is not null))) as settled,
                                   (select max(e.created_at) from listing_event e
                                    where e.listing_id = l.listing_id and e.type in ('RETURNED', 'DISPUTED')) as issue_at,
                                   (select max(e.created_at) from listing_event e
                                    where e.listing_id = l.listing_id and e.type in ('PAYMENT_CONFIRMED', 'RESOLVED')) as cleared_at
                            from shipping_label l
                        ), closed as (
                            select from_address_id, to_address_id,
                                   case when cancelled or (settled and (issue_at is null or cleared_at > issue_at))
                                        then last_event_at end as closed_at
                            from deal
                        ), uses as (
                            select from_address_id as address_id, closed_at from closed
                            union all
                            select to_address_id, closed_at from closed
                        )
                        update ship_address a
                        set name = null, company = null, street1 = null, street2 = null, city = null, phone = null,
                            payment_method = null, payment_handle = null, anonymised_at = :now
                        where a.anonymised_at is null
                          and exists (select 1 from uses u where u.address_id = a.id)
                          and not exists (select 1 from uses u where u.address_id = a.id and (u.closed_at is null or u.closed_at >= :cutoff))
                          and exists (select 1 from ship_address newer where newer.org_id = a.org_id and newer.created_at > a.created_at)""")
                .param("now", Timestamp.from(now))
                .param("cutoff", Timestamp.from(now.minus(labelKeep)))
                .update();
        Result result = new Result(deleted, anonymised);
        log.info("Retention: deleted {} replaced address(es) no label used, anonymised {} address(es) of long-closed deals",
                deleted, anonymised);
        return result;
    }

    /** Daily, with retention on (the default); tests call {@link #run} directly. */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnBooleanProperty("pokemans.retention.enabled")
    static class Job {

        private final AddressRetention retention;

        Job(AddressRetention retention) {
            this.retention = retention;
        }

        @Scheduled(cron = "${pokemans.retention.cron:0 15 4 * * *}", zone = "UTC")
        void run() {
            try {
                retention.run(Instant.now());
            } catch (RuntimeException e) {
                log.error("Address retention failed", e);
            }
        }
    }
}

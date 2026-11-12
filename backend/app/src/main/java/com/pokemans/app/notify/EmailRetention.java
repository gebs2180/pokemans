package com.pokemans.app.notify;

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
 * Clears an email's address, subject and bodies once it's done with (sent, failed for good, skipped or cancelled) and
 * older than {@code pokemans.retention.email-content-days} (90, a default to confirm; privacy review 2026-11-12). The
 * row stays, with its template, status, reason and timestamps, as the record that it went out or why not. The last
 * provider error goes too: it can quote the address.
 */
@Component
public class EmailRetention {

    private static final Logger log = LoggerFactory.getLogger(EmailRetention.class);

    private final JdbcClient jdbc;
    private final Duration keep;

    EmailRetention(JdbcClient jdbc, @Value("${pokemans.retention.email-content-days:90}") int days) {
        this.jdbc = jdbc;
        this.keep = Duration.of(days, ChronoUnit.DAYS);
    }

    /** Returns how many emails had their content cleared. */
    @Transactional
    public int run(Instant now) {
        int cleared = jdbc.sql("""
                        update email_message
                        set recipient_email = null, subject = null, html_body = null, text_body = null, last_error = null,
                            content_cleared_at = :now
                        where content_cleared_at is null
                          and status in ('SENT', 'FAILED', 'SKIPPED', 'CANCELLED')
                          and created_at < :cutoff""")
                .param("now", java.sql.Timestamp.from(now))
                .param("cutoff", java.sql.Timestamp.from(now.minus(keep)))
                .update();
        log.info("Retention: cleared the content of {} email(s) older than {} days", cleared, keep.toDays());
        return cleared;
    }

    /** Daily, with retention on (the default); tests call {@link #run} directly. */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnBooleanProperty("pokemans.retention.enabled")
    static class Job {

        private final EmailRetention retention;

        Job(EmailRetention retention) {
            this.retention = retention;
        }

        @Scheduled(cron = "${pokemans.retention.cron:0 15 4 * * *}", zone = "UTC")
        void run() {
            try {
                retention.run(Instant.now());
            } catch (RuntimeException e) {
                log.error("Email retention failed", e);
            }
        }
    }
}

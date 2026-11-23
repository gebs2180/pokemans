package com.pokemans.app.notify;

import com.pokemans.app.offers.Deals;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Email settings, and, with sending on (NOTIFY_EMAIL_ENABLED), the Resend client and the jobs that send. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EmailProperties.class)
class NotifyConfig {

    @Bean
    @ConditionalOnBooleanProperty("pokemans.notify.email.enabled")
    ResendClient resendClient(EmailProperties properties) {
        return new ResendClient(properties);
    }

    @Bean
    @ConditionalOnBooleanProperty("pokemans.notify.email.enabled")
    EmailSender emailSender(EmailOutbox outbox, ResendClient resend, Deals deals, EmailProperties properties) {
        return new EmailSender(outbox, resend, deals, properties);
    }

    /** Sends due emails every poll interval, and queues the daily digest. Tests call the jobs directly. */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnBooleanProperty("pokemans.notify.email.enabled")
    static class Jobs {

        private static final Logger log = LoggerFactory.getLogger(Jobs.class);

        private final EmailSender sender;
        private final ListingDigest digest;
        private final OfferReadyEmails offerReady;

        Jobs(EmailSender sender, ListingDigest digest, OfferReadyEmails offerReady) {
            this.sender = sender;
            this.digest = digest;
            this.offerReady = offerReady;
        }

        @Scheduled(fixedDelayString = "${pokemans.notify.email.offer-ready-interval:5m}", initialDelayString = "1m")
        void offerReady() {
            try {
                int queued = offerReady.run(Instant.now());
                if (queued > 0) {
                    log.info("Queued {} offer-ready email(s)", queued);
                }
            } catch (RuntimeException e) {
                log.error("Offer-ready pass failed", e);
            }
        }

        @Scheduled(fixedDelayString = "${pokemans.notify.email.poll-interval:20s}", initialDelayString = "${pokemans.notify.email.poll-interval:20s}")
        void send() {
            try {
                sender.sendDue(Instant.now());
            } catch (RuntimeException e) {
                log.error("Email send pass failed", e);
            }
        }

        @Scheduled(cron = "${pokemans.notify.email.digest-cron:0 0 14 * * *}", zone = "UTC")
        void digest() {
            int queued = digest.run(Instant.now());
            log.info("Queued {} new-listings digest(s)", queued);
        }
    }
}

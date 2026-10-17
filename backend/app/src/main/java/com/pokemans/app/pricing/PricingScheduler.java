package com.pokemans.app.pricing;

import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Runs the pricing refresh nightly. Off by default (no licensed price source); tests drive it directly. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnBooleanProperty("pokemans.pricing.ingestion.enabled")
class PricingScheduler {

    private final PricingRefresh refresh;

    PricingScheduler(PricingRefresh refresh) {
        this.refresh = refresh;
    }

    @Scheduled(cron = "${pokemans.pricing.ingestion.cron:0 30 6 * * *}", zone = "UTC")
    void run() {
        refresh.runOnce(LocalDate.now(ZoneOffset.UTC));
    }
}

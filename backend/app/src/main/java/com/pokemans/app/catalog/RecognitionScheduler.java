package com.pokemans.app.catalog;

import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Reads queued cards in the background. Only with the reader on; tests drive it directly. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnBooleanProperty("pokemans.catalog.reader.enabled")
class RecognitionScheduler {

    private final RecognitionService recognition;
    private final CatalogProperties properties;

    RecognitionScheduler(RecognitionService recognition, CatalogProperties properties) {
        this.recognition = recognition;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${pokemans.catalog.reader.poll-interval:15s}", initialDelayString = "${pokemans.catalog.reader.poll-interval:15s}")
    void run() {
        if (properties.reader().workerEnabled()) {
            recognition.runOnce();
        }
    }

    @Scheduled(fixedDelayString = "${pokemans.catalog.reader.sweep-interval:5m}", initialDelayString = "1m")
    void sweep() {
        if (properties.reader().workerEnabled()) {
            recognition.sweepMissedCaptures(Instant.now());
        }
    }
}

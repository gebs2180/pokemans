package com.pokemans.app.condition;

import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Runs the prediction worker and the missed-capture sweep in the background. Off in tests, which call them directly. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnBooleanProperty(name = "pokemans.condition.worker.enabled", matchIfMissing = true)
class PredictionScheduler {

    private final PredictionWorker worker;
    private final ConditionService condition;

    PredictionScheduler(PredictionWorker worker, ConditionService condition) {
        this.worker = worker;
        this.condition = condition;
    }

    @Scheduled(fixedDelayString = "${pokemans.condition.worker.poll-interval:15s}", initialDelayString = "10s")
    void run() {
        worker.runOnce();
    }

    @Scheduled(fixedDelayString = "${pokemans.condition.worker.sweep-interval:5m}", initialDelayString = "1m")
    void sweep() {
        condition.sweepMissedCaptures(Instant.now());
    }
}

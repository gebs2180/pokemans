package com.pokemans.app.condition;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Runs the prediction worker in the background. Off in tests, which call the worker directly. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnBooleanProperty(name = "pokemans.condition.worker.enabled", matchIfMissing = true)
class PredictionScheduler {

    private final PredictionWorker worker;

    PredictionScheduler(PredictionWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${pokemans.condition.worker.poll-interval:15s}", initialDelayString = "10s")
    void run() {
        worker.runOnce();
    }
}

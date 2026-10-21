package com.pokemans.app.condition;

import com.pokemans.app.intake.CardItems;
import com.pokemans.app.storage.BlobStore;
import com.pokemans.app.storage.StorageProperties;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Works through due prediction requests: submits pending ones, polls submitted ones, stores every
 * answer, and rebuilds the card's system assessment once nothing is left open for it.
 *
 * <p>Safe with several instances running: due requests are claimed in a short transaction
 * ({@code FOR UPDATE SKIP LOCKED}, then leased by moving their next attempt out), the provider is
 * called with no transaction open, and the answer is written in another short transaction. A crashed
 * worker's claims come back when the lease runs out. Each request is handled on its own, so one
 * failing provider doesn't hold up the others.
 */
@Component
class PredictionWorker {

    private static final Logger log = LoggerFactory.getLogger(PredictionWorker.class);

    /** What one provider call came back with, applied to the request afterwards. */
    private sealed interface Outcome {
    }

    private record Submitted(String externalRequestId) implements Outcome {
    }

    private record Polled(PreGradeProvider.Poll poll, long latencyMs) implements Outcome {
    }

    private record CallFailed(String error) implements Outcome {
    }

    /** Requests leased to this worker until {@code until}; the lease doubles as the claim's token. */
    private record Claim(List<UUID> ids, Instant until) {
    }

    private final Map<String, PreGradeProvider> providers;
    private final PredictionRequestRepository requests;
    private final ProviderPredictionRepository predictions;
    private final ConditionService condition;
    private final CardItems cardItems;
    private final BlobStore blobs;
    private final StorageProperties storage;
    private final ConditionProperties properties;
    private final TransactionTemplate transactions;

    PredictionWorker(List<PreGradeProvider> providers, PredictionRequestRepository requests, ProviderPredictionRepository predictions,
                     ConditionService condition, CardItems cardItems, BlobStore blobs, StorageProperties storage,
                     ConditionProperties properties, TransactionTemplate transactions) {
        this.providers = providers.stream().collect(Collectors.toMap(PreGradeProvider::name, Function.identity()));
        this.requests = requests;
        this.predictions = predictions;
        this.condition = condition;
        this.cardItems = cardItems;
        this.blobs = blobs;
        this.storage = storage;
        this.properties = properties;
        this.transactions = transactions;
    }

    /**
     * One pass over everything due now, claimed a batch at a time. Returns how many requests were
     * handled. The pass stops after twenty batches so it can't run on forever.
     */
    int runOnce() {
        int handled = 0;
        int limit = properties.worker().claimBatch() * 20;
        while (handled < limit) {
            Claim claim = claim();
            if (claim.ids().isEmpty()) {
                break;
            }
            for (UUID id : claim.ids()) {
                handle(id, claim.until());
                handled++;
            }
        }
        return handled;
    }

    /** Locks a batch of due requests and leases them to this worker, in one short transaction. */
    private Claim claim() {
        Instant now = Instant.now();
        // Postgres keeps microseconds: truncate so the lease reads back equal and can mark ownership.
        Instant until = now.plus(properties.worker().lease()).truncatedTo(ChronoUnit.MICROS);
        List<UUID> ids = transactions.execute(status -> {
            List<UUID> due = requests.lockDue(now, properties.worker().claimBatch());
            if (!due.isEmpty()) {
                requests.lease(due, until);
            }
            return due;
        });
        return new Claim(ids, until);
    }

    private void handle(UUID id, Instant leasedUntil) {
        try {
            PredictionRequest request = requests.findById(id).orElseThrow();
            if (!request.isOpen() || !leasedUntil.equals(request.getNextAttemptAt())) {
                return;
            }
            Outcome outcome = call(request); // no transaction open: provider calls can take many seconds
            PredictionRequest finished = transactions.execute(status -> apply(id, leasedUntil, outcome));
            if (finished != null) {
                // Its own transaction: a failed rebuild must not roll back the stored prediction.
                transactions.executeWithoutResult(status -> afterFinished(finished));
            }
        } catch (RuntimeException e) {
            log.warn("Prediction request {} failed unexpectedly", id, e);
        }
    }

    /** Calls the provider for one request. A failed call is an outcome too, not an exception. */
    private Outcome call(PredictionRequest request) {
        PreGradeProvider provider = providers.get(request.getProvider());
        if (provider == null) {
            return new CallFailed("Provider " + request.getProvider() + " is not enabled");
        }
        long started = System.nanoTime();
        try {
            if (request.getStatus() == PredictionRequest.Status.PENDING) {
                return new Submitted(provider.submit(photos(request), request.getId().toString()));
            }
            return new Polled(provider.poll(request.getExternalRequestId()), elapsedMs(started));
        } catch (RuntimeException e) {
            return new CallFailed(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Writes what the provider said. Every answer is stored, even if this worker's lease ran out while
     * the call was running; the request itself only moves on while it is still ours.
     *
     * @return the request when it has just finished (so the assessment may need rebuilding), else null
     */
    private PredictionRequest apply(UUID id, Instant leasedUntil, Outcome outcome) {
        PredictionRequest request = requests.findById(id).orElseThrow();
        if (outcome instanceof Polled(PreGradeProvider.Poll poll, long latencyMs)) {
            switch (poll) {
                case PreGradeProvider.Done done ->
                        predictions.save(ProviderPrediction.succeeded(request, done.reading(), withoutImages(done.raw()), latencyMs));
                case PreGradeProvider.Failed failed ->
                        predictions.save(ProviderPrediction.rejected(request, failed.reason(), withoutImages(failed.raw()), latencyMs));
                case PreGradeProvider.Pending pending -> {
                }
            }
        }
        if (!request.isOpen() || !leasedUntil.equals(request.getNextAttemptAt())) {
            log.warn("Prediction request {} was claimed again while its provider call ran; {} not applied to it", id, outcome);
            return null;
        }
        switch (outcome) {
            case Submitted submitted -> request.submitted(submitted.externalRequestId());
            case CallFailed failed -> request.callFailed(failed.error(), properties.worker().maxAttempts());
            case Polled polled -> {
                switch (polled.poll()) {
                    case PreGradeProvider.Pending pending -> request.stillPending(Duration.ofSeconds(10), properties.worker().maxPending());
                    case PreGradeProvider.Done done -> request.done();
                    case PreGradeProvider.Failed failed -> request.rejected(failed.reason());
                }
            }
        }
        return request.isOpen() ? null : request;
    }

    private void afterFinished(PredictionRequest request) {
        if (requests.findByCardItemIdOrderByCreatedAtDesc(request.getCardItemId()).stream().noneMatch(PredictionRequest::isOpen)) {
            condition.rebuildSystemAssessment(request.getCardItemId());
        }
    }

    private PreGradeProvider.Photos photos(PredictionRequest request) {
        Map<UUID, CardItems.PhotoRef> passed = cardItems.passedPhotos(request.getCardItemId()).stream()
                .collect(Collectors.toMap(CardItems.PhotoRef::id, Function.identity()));
        List<UUID> ids = request.getPhotoIds();
        PreGradeProvider.Image front = image(passed.get(ids.get(0)));
        PreGradeProvider.Image back = ids.size() > 1 ? image(passed.get(ids.get(1))) : null;
        return new PreGradeProvider.Photos(front, back);
    }

    private PreGradeProvider.Image image(CardItems.PhotoRef photo) {
        if (photo == null) {
            throw new IllegalStateException("A photo in this request no longer passes its checks");
        }
        return new PreGradeProvider.Image(blobs.read(storage.photoBucket(), photo.storageKey()), photo.contentType());
    }

    /** Drops any echoed image data from a provider response before it is stored. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> withoutImages(Map<String, Object> raw) {
        if (raw == null) {
            return null;
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (key.equals("_base64")) {
                return;
            }
            if (value instanceof Map<?, ?> m) {
                copy.put(key, withoutImages((Map<String, Object>) m));
            } else if (value instanceof List<?> l) {
                copy.put(key, l.stream().map(v -> v instanceof Map<?, ?> m ? withoutImages((Map<String, Object>) m) : v).toList());
            } else {
                copy.put(key, value);
            }
        });
        return copy;
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}

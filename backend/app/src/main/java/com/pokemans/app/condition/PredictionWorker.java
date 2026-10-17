package com.pokemans.app.condition;

import com.pokemans.app.intake.CardItems;
import com.pokemans.app.storage.BlobStore;
import com.pokemans.app.storage.StorageProperties;
import java.time.Duration;
import java.time.Instant;
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
 * answer, and rebuilds the card's system assessment once nothing is left open for it. Each request
 * is handled in its own transaction, so one failing provider doesn't hold up the others.
 */
@Component
class PredictionWorker {

    private static final Logger log = LoggerFactory.getLogger(PredictionWorker.class);

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

    /** One pass over everything due now. Returns how many requests were looked at. */
    int runOnce() {
        List<UUID> due = requests.findDue(Instant.now()).stream().map(PredictionRequest::getId).toList();
        for (UUID id : due) {
            try {
                transactions.executeWithoutResult(status -> handle(requests.findById(id).orElseThrow()));
            } catch (RuntimeException e) {
                log.warn("Prediction request {} failed unexpectedly", id, e);
            }
        }
        return due.size();
    }

    private void handle(PredictionRequest request) {
        if (!request.isOpen()) {
            return;
        }
        PreGradeProvider provider = providers.get(request.getProvider());
        if (provider == null) {
            request.callFailed("Provider " + request.getProvider() + " is not enabled", properties.worker().maxAttempts());
            return;
        }
        long started = System.nanoTime();
        try {
            if (request.getStatus() == PredictionRequest.Status.PENDING) {
                request.submitted(provider.submit(photos(request), request.getId().toString()));
                return;
            }
            switch (provider.poll(request.getExternalRequestId())) {
                case PreGradeProvider.Pending pending -> request.stillPending(Duration.ofSeconds(10));
                case PreGradeProvider.Done done -> {
                    predictions.save(ProviderPrediction.succeeded(request, done.reading(), withoutImages(done.raw()), elapsedMs(started)));
                    request.done();
                    afterFinished(request);
                }
                case PreGradeProvider.Failed failed -> {
                    predictions.save(ProviderPrediction.rejected(request, failed.reason(), withoutImages(failed.raw()), elapsedMs(started)));
                    request.rejected(failed.reason());
                    afterFinished(request);
                }
            }
        } catch (RuntimeException e) {
            request.callFailed(e.getClass().getSimpleName() + ": " + e.getMessage(), properties.worker().maxAttempts());
            if (!request.isOpen()) {
                afterFinished(request);
            }
        }
    }

    private void afterFinished(PredictionRequest request) {
        requests.flush();
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

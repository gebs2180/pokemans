package com.pokemans.app.catalog;

import com.pokemans.app.identity.CurrentUser;
import com.pokemans.app.intake.CardItems;
import com.pokemans.app.storage.BlobStore;
import com.pokemans.app.storage.StorageProperties;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Automatic card recognition: reads the front card crop and stores ranked identity suggestions
 * for the reviewer. Runs when capture completes (single cards only) or when the admin asks.
 */
@Service
class RecognitionService {

    private static final Logger log = LoggerFactory.getLogger(RecognitionService.class);

    private final Optional<CardReader> reader;
    private final Optional<CatalogProvider> provider;
    private final CardReadingRepository readings;
    private final CardIdentificationRepository identifications;
    private final IdentificationService identification;
    private final CardItems cardItems;
    private final BlobStore blobs;
    private final StorageProperties storage;
    private final CatalogProperties properties;
    private final CurrentUser currentUser;
    private final TransactionTemplate transactions;

    RecognitionService(Optional<CardReader> reader, Optional<CatalogProvider> provider, CardReadingRepository readings,
                       CardIdentificationRepository identifications, IdentificationService identification, CardItems cardItems,
                       BlobStore blobs, StorageProperties storage, CatalogProperties properties, CurrentUser currentUser,
                       TransactionTemplate transactions) {
        this.reader = reader;
        this.provider = provider;
        this.readings = readings;
        this.identifications = identifications;
        this.identification = identification;
        this.cardItems = cardItems;
        this.blobs = blobs;
        this.storage = storage;
        this.properties = properties;
        this.currentUser = currentUser;
        this.transactions = transactions;
    }

    boolean enabled() {
        return reader.isPresent();
    }

    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void onCaptureCompleted(CardItems.CaptureCompleted event) {
        CardItems.CardItemRef card = cardItems.get(event.cardItemId());
        if (reader.isPresent() && !card.lot()) {
            queue(card, null);
        }
    }

    /** Admin: read the card (again), from its latest passing front. */
    @Transactional
    CardReading requestReading(UUID orgId, UUID cardId) {
        if (reader.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Card recognition is not enabled");
        }
        CardItems.CardItemRef card = cardItems.get(cardId);
        if (!card.orgId().equals(orgId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Card not found");
        }
        return queue(card, currentUser.id());
    }

    private CardReading queue(CardItems.CardItemRef card, UUID requestedBy) {
        CardItems.PhotoRef front = cardItems.passedPhotos(card.id()).stream()
                .filter(p -> p.side().equals("FRONT"))
                .max(Comparator.comparing(CardItems.PhotoRef::uploadedAt))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "The card needs a passing front photo first"));
        return readings.save(new CardReading(card.orgId(), card.id(), front.id(), reader.orElseThrow().name(), requestedBy));
    }

    /**
     * One pass over due readings. Returns how many were looked at. The reading commits on its own
     * before matching, so a catalog outage can't roll it back and have the (paid) read run again.
     */
    int runOnce() {
        List<UUID> due = readings.findDue(Instant.now()).stream().map(CardReading::getId).toList();
        for (UUID id : due) {
            try {
                CardReader.Reading result = transactions.execute(status -> process(readings.findById(id).orElseThrow()));
                if (result != null && provider.isPresent()) {
                    suggest(id, result);
                }
            } catch (RuntimeException e) {
                log.warn("Card reading {} failed unexpectedly", id, e);
            }
        }
        return due.size();
    }

    private void suggest(UUID readingId, CardReader.Reading result) {
        try {
            transactions.executeWithoutResult(status -> match(readings.findById(readingId).orElseThrow(), result));
        } catch (RuntimeException e) {
            log.warn("Matching card reading {} to the catalog failed", readingId, e);
            transactions.executeWithoutResult(status -> readings.findById(readingId).orElseThrow()
                    .matchFailed(e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    /** Reads the card; the reading to match when it was read, else null. */
    private CardReader.Reading process(CardReading reading) {
        if (reading.getStatus() != CardReading.Status.PENDING) {
            return null;
        }
        if (reader.isEmpty()) {
            reading.failed("Card recognition is not enabled");
            return null;
        }
        Optional<CardItems.PhotoRef> photo = cardItems.passedPhotos(reading.getCardItemId()).stream()
                .filter(p -> p.id().equals(reading.getPhotoId())).findFirst();
        if (photo.isEmpty() || photo.get().cropKey() == null) {
            reading.failed("No card crop to read: retake the front so the whole card is found");
            return null;
        }
        CardReader.Reading result;
        try {
            result = reader.get().read(blobs.read(storage.photoBucket(), photo.get().cropKey()), "image/jpeg");
        } catch (RuntimeException e) {
            reading.callFailed(e.getClass().getSimpleName() + ": " + e.getMessage(), properties.reader().maxAttempts());
            return null;
        }
        reading.done(result);
        return result;
    }

    private void match(CardReading reading, CardReader.Reading result) {
        for (SuggestionMatcher.Match match : new SuggestionMatcher(provider.get()).match(result)) {
            CatalogCard card = identification.findOrCreate(
                    IdentificationService.identity(match.card(), match.edition(), match.variant(), null, null),
                    match.card().payload(), null);
            identifications.save(CardIdentification.suggested(reading.getOrgId(), reading.getCardItemId(), card.getId(),
                    BigDecimal.valueOf(match.confidence()).setScale(3, RoundingMode.HALF_UP), reading.getId(),
                    "Matched on " + match.why()));
        }
    }

    /** The card's latest reading, if any. */
    @Transactional(readOnly = true)
    Optional<CardReading> latestReading(UUID cardId) {
        return readings.findByCardItemIdOrderByCreatedAtDesc(cardId).stream().findFirst();
    }

    /** Suggestions from a reading, best first. */
    @Transactional(readOnly = true)
    List<CardIdentification> suggestions(UUID readingId) {
        return identifications.findByReadingIdOrderByConfidenceDesc(readingId);
    }
}

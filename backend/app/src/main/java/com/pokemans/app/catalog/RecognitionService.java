package com.pokemans.app.catalog;

import com.pokemans.app.identity.CurrentUser;
import com.pokemans.app.intake.CardItems;
import com.pokemans.app.storage.BlobStore;
import com.pokemans.app.storage.StorageProperties;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
    /** How long a claimed reading stays hidden from other workers: well over a batch of reads. */
    private static final Duration LEASE = Duration.ofMinutes(15);
    private static final int CLAIM_BATCH = 10;
    private static final Duration SWEEP_WINDOW = Duration.ofDays(7);
    /** Younger completed captures are left to the capture-completed listener. */
    private static final Duration SWEEP_MIN_AGE = Duration.ofMinutes(2);
    /** Advisory lock key for {@link #sweepMissedCaptures}. */
    private static final long SWEEP_LOCK = 0x706f6b65_63617461L;

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
     * Catches completed captures whose {@link CardItems.CaptureCompleted} was lost (a crash between
     * the photo check committing and the listener running): queues a reading for single cards whose
     * capture completed in the last week and that have never been read. Cards completed in the last
     * two minutes are left to the listener. One sweep at a time across instances.
     *
     * @return how many cards were queued
     */
    @Transactional
    int sweepMissedCaptures(Instant now) {
        if (reader.isEmpty() || !readings.tryTransactionLock(SWEEP_LOCK)) {
            return 0;
        }
        int queued = 0;
        for (CardItems.CardItemRef card : cardItems.captureCompletedBetween(now.minus(SWEEP_WINDOW), now.minus(SWEEP_MIN_AGE))) {
            if (!card.lot() && !readings.existsByCardItemId(card.id())) {
                log.warn("Card {} completed capture but was never read; queuing a reading now", card.id());
                queue(card, null);
                queued++;
            }
        }
        return queued;
    }

    /**
     * One pass over due readings, claimed a batch at a time, so several instances never read the
     * same card at once. Returns how many were handled. The claim and each write are short
     * transactions; the reader is called with none open. The reading commits on its own before
     * matching, so a catalog outage can't roll it back and have the (paid) read run again.
     */
    int runOnce() {
        int handled = 0;
        while (handled < CLAIM_BATCH * 20) {
            Instant now = Instant.now();
            // Postgres keeps microseconds: truncate so the lease reads back equal and can mark ownership.
            Instant until = now.plus(LEASE).truncatedTo(ChronoUnit.MICROS);
            List<UUID> claimed = transactions.execute(status -> {
                List<UUID> due = readings.lockDue(now, CLAIM_BATCH);
                if (!due.isEmpty()) {
                    readings.lease(due, until);
                }
                return due;
            });
            if (claimed.isEmpty()) {
                break;
            }
            for (UUID id : claimed) {
                handle(id, until);
                handled++;
            }
        }
        return handled;
    }

    private void handle(UUID id, Instant leasedUntil) {
        try {
            CardReading reading = readings.findById(id).orElseThrow();
            if (reading.getStatus() != CardReading.Status.PENDING || !leasedUntil.equals(reading.getNextAttemptAt())) {
                return;
            }
            Outcome outcome = read(reading); // no transaction open: a read can take many seconds
            CardReader.Reading result = transactions.execute(status -> apply(id, leasedUntil, outcome));
            if (result != null && provider.isPresent()) {
                suggest(id, result);
            }
        } catch (RuntimeException e) {
            log.warn("Card reading {} failed unexpectedly", id, e);
        }
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

    /** What one read came back with, applied to the reading afterwards. */
    private sealed interface Outcome {
    }

    private record Read(CardReader.Reading result) implements Outcome {
    }

    /** No point retrying. */
    private record Unreadable(String reason) implements Outcome {
    }

    private record CallFailed(String error) implements Outcome {
    }

    /** Reads the card. A failed read is an outcome too, not an exception. */
    private Outcome read(CardReading reading) {
        if (reader.isEmpty()) {
            return new Unreadable("Card recognition is not enabled");
        }
        Optional<CardItems.PhotoRef> photo = cardItems.passedPhotos(reading.getCardItemId()).stream()
                .filter(p -> p.id().equals(reading.getPhotoId())).findFirst();
        if (photo.isEmpty() || photo.get().cropKey() == null) {
            return new Unreadable("No card crop to read: retake the front so the whole card is found");
        }
        try {
            return new Read(reader.get().read(blobs.read(storage.photoBucket(), photo.get().cropKey()), "image/jpeg"));
        } catch (RuntimeException e) {
            return new CallFailed(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Writes the outcome; returns what was read when it is to be matched, else null. A (paid) read is
     * stored even if this worker's lease ran out meanwhile, as long as nobody stored one first.
     */
    private CardReader.Reading apply(UUID id, Instant leasedUntil, Outcome outcome) {
        CardReading reading = readings.findById(id).orElseThrow();
        if (reading.getStatus() != CardReading.Status.PENDING
                || (!(outcome instanceof Read) && !leasedUntil.equals(reading.getNextAttemptAt()))) {
            log.warn("Card reading {} was handled by another worker while this one ran; {} not applied", id, outcome);
            return null;
        }
        switch (outcome) {
            case Read read -> {
                reading.done(read.result());
                return read.result();
            }
            case Unreadable unreadable -> reading.failed(unreadable.reason());
            case CallFailed failed -> reading.callFailed(failed.error(), properties.reader().maxAttempts());
        }
        return null;
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

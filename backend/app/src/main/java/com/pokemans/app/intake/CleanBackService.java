package com.pokemans.app.intake;

import com.pokemans.app.identity.CurrentUser;
import com.pokemans.app.storage.BlobStore;
import com.pokemans.app.storage.StorageProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The surface view's "Clean back" ({@link CleanBack}): keeps the reference back and makes each BACK photo's clean back
 * against its latest version. A viewing aid only: the scores it records feed no grade, price, eligibility or flag.
 */
@Service
class CleanBackService {

    private static final Logger log = LoggerFactory.getLogger(CleanBackService.class);

    /** Photos looked at to pick an automatic reference's candidates (one per card is kept). */
    private static final int CANDIDATE_POOL = 200;

    private final BackReferenceRepository references;
    private final PhotoRepository photos;
    private final BlobStore blobs;
    private final StorageProperties storage;
    private final CurrentUser currentUser;

    private record Prepared(int version, CleanBack.Reference reference) {
    }

    private volatile Prepared prepared;

    CleanBackService(BackReferenceRepository references, PhotoRepository photos, BlobStore blobs, StorageProperties storage,
                     CurrentUser currentUser) {
        this.references = references;
        this.photos = photos;
        this.blobs = blobs;
        this.storage = storage;
        this.currentUser = currentUser;
    }

    /** The latest reference version, or null if none was built yet. */
    Integer currentVersion() {
        return references.findFirstByOrderByVersionDesc().map(BackReference::getVersion).orElse(null);
    }

    /**
     * Makes a BACK photo's clean back from its surface crop against the latest reference, and records it on the photo:
     * the image, or why there is none, with the scores. Does nothing without a reference, or for another side. Returns
     * whether an image was stored.
     */
    boolean make(Photo photo, byte[] surfaceJpeg) {
        if (photo.getSide() != Photo.Side.BACK) {
            return false;
        }
        Prepared current = prepared();
        if (current == null) {
            return false;
        }
        CleanBack.Outcome outcome = CleanBack.clean(surfaceJpeg, current.reference());
        String key = null;
        if (outcome.jpeg() != null) {
            key = photo.surfaceCleanStorageKey(current.version());
            blobs.putIfAbsent(storage.photoBucket(), key, outcome.jpeg(), "image/jpeg");
        }
        photo.cleanBackChecked(current.version(), key, outcome.scores(), outcome.failure());
        return key != null;
    }

    /** As {@link #make}, from the photo's stored surface crop. Null when there is none to read. */
    Boolean makeFromStored(Photo photo) {
        if (photo.getSurfaceKey() == null || blobs.head(storage.photoBucket(), photo.getSurfaceKey()).isEmpty()) {
            return null;
        }
        return make(photo, blobs.read(storage.photoBucket(), photo.getSurfaceKey()));
    }

    private Prepared prepared() {
        BackReference latest = references.findFirstByOrderByVersionDesc().orElse(null);
        if (latest == null) {
            return null;
        }
        Prepared cached = prepared;
        if (cached != null && cached.version() == latest.getVersion()) {
            return cached;
        }
        synchronized (this) {
            if (prepared != null && prepared.version() == latest.getVersion()) {
                return prepared;
            }
            CleanBack.Reference reference = CleanBack.Reference.of(blobs.read(storage.photoBucket(), latest.getStorageKey()));
            if (reference == null) {
                throw new IllegalStateException("Reference back v" + latest.getVersion() + " doesn't decode");
            }
            prepared = new Prepared(latest.getVersion(), reference);
            return prepared;
        }
    }

    /**
     * @param version  the reference's version, null when none (or no new one) was built
     * @param built    whether this call built a new version
     * @param needed   backs that must agree for a reference to be built
     * @param used     photos in the median
     * @param rejected photos left out, and why
     * @param message  what happened, and what to do if nothing was built
     */
    record ReferenceView(Integer version, boolean built, boolean seeded, int needed, List<UUID> candidates, List<UUID> used,
                         Map<UUID, String> rejected, Instant createdAt, String message) {
    }

    @Transactional(readOnly = true)
    ReferenceView current() {
        return references.findFirstByOrderByVersionDesc()
                .map(r -> new ReferenceView(r.getVersion(), false, r.isSeeded(), r.isSeeded() ? CleanBack.MIN_SEEDED_BACKS
                        : CleanBack.MIN_REFERENCE_BACKS, null, r.getUsedPhotoIds(), r.getRejected(), r.getCreatedAt(),
                        "Reference back v" + r.getVersion() + " from " + r.getUsedPhotoIds().size() + " backs."))
                .orElse(new ReferenceView(null, false, false, CleanBack.MIN_REFERENCE_BACKS, null, List.of(), Map.of(), null,
                        "No reference back yet. POST here to build one from our clean English backs, or with {\"photoIds\": [...]} to choose them."));
    }

    /**
     * Builds a new reference version. With photo ids, from those (at least {@link CleanBack#MIN_SEEDED_BACKS} that agree);
     * otherwise from the sharpest, least glary passed backs, one per card (at least {@link CleanBack#MIN_REFERENCE_BACKS}
     * that agree). Too few gives no new version and says how many are needed.
     */
    @Transactional
    ReferenceView rebuild(List<UUID> photoIds) {
        boolean seeded = photoIds != null && !photoIds.isEmpty();
        int needed = seeded ? CleanBack.MIN_SEEDED_BACKS : CleanBack.MIN_REFERENCE_BACKS;
        Map<UUID, String> rejected = new LinkedHashMap<>();
        List<Photo> candidates = new ArrayList<>();
        if (seeded) {
            if (photoIds.size() > CleanBack.MAX_REFERENCE_CANDIDATES) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Choose at most " + CleanBack.MAX_REFERENCE_CANDIDATES + " photos for a reference back");
            }
            for (UUID id : new java.util.LinkedHashSet<>(photoIds)) {
                Photo p = photos.findById(id).orElse(null);
                String why = p == null ? "NOT_FOUND"
                        : p.getSide() != Photo.Side.BACK ? "NOT_A_BACK"
                        : p.getStatus() != Photo.Status.PASSED ? "NOT_PASSED"
                        : p.getSurfaceKey() == null ? "NO_SURFACE_CROP" : null;
                if (why != null) {
                    rejected.put(id, why);
                } else {
                    candidates.add(p);
                }
            }
        } else {
            Set<UUID> cards = new HashSet<>();
            for (Photo p : photos.findReferenceCandidates(Limit.of(CANDIDATE_POOL))) {
                if (candidates.size() < CleanBack.MAX_REFERENCE_CANDIDATES && cards.add(p.getCardItemId())) {
                    candidates.add(p);
                }
            }
        }

        List<Photo> readable = new ArrayList<>();
        List<byte[]> surfaces = new ArrayList<>();
        for (Photo p : candidates) {
            if (blobs.head(storage.photoBucket(), p.getSurfaceKey()).isEmpty()) {
                rejected.put(p.getId(), "SURFACE_CROP_MISSING");
                continue;
            }
            readable.add(p);
            surfaces.add(blobs.read(storage.photoBucket(), p.getSurfaceKey()));
        }
        CleanBack.Built built = CleanBack.buildReference(surfaces, needed);
        built.rejected().forEach((i, why) -> rejected.put(readable.get(i).getId(), why));
        List<UUID> used = built.used().stream().map(i -> readable.get(i).getId()).toList();
        List<UUID> candidateIds = candidates.stream().map(Photo::getId).toList();

        if (built.png() == null) {
            String message = "Not enough clean backs agree to build a reference: " + used.size() + " of " + needed + " needed"
                    + " (" + candidates.size() + " candidates). " + (seeded
                    ? "Choose more photo ids of clean, sharp English backs."
                    : "Choose photo ids of at least " + CleanBack.MIN_SEEDED_BACKS + " clean, sharp English backs and POST them as {\"photoIds\": [...]}.");
            log.info("Reference back not built: {}", message);
            return new ReferenceView(null, false, seeded, needed, candidateIds, used, rejected, null, message);
        }
        Integer latest = currentVersion();
        int version = latest == null ? 1 : latest + 1;
        BackReference reference = new BackReference(version, candidateIds, used, rejected, seeded, currentUser.id());
        blobs.putIfAbsent(storage.photoBucket(), reference.getStorageKey(), built.png(), "image/png");
        references.save(reference);
        log.info("Reference back v{} built from {} backs ({} rejected)", version, used.size(), rejected.size());
        return new ReferenceView(version, true, seeded, needed, candidateIds, used, rejected, reference.getCreatedAt(),
                "Reference back v" + version + " built from " + used.size() + " backs. Run POST /api/admin/photos/wide-crops"
                        + " until cleanRemaining is 0 to make the clean backs against it.");
    }
}

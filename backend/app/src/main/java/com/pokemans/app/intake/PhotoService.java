package com.pokemans.app.intake;

import com.pokemans.app.identity.Consents;
import com.pokemans.app.identity.CurrentUser;
import com.pokemans.app.identity.OrgAccess;
import com.pokemans.app.identity.OrgType;
import com.pokemans.app.intake.IntakeViews.PhotoView;
import com.pokemans.app.intake.IntakeViews.UploadSlot;
import com.pokemans.app.storage.BlobStore;
import com.pokemans.app.storage.StorageProperties;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Photo uploads: the client asks for an upload slot, PUTs the file straight to storage, then
 * confirms; confirming runs the quality checks and records pass or retake.
 */
@Service
class PhotoService {

    static final Set<String> ACCEPTED_TYPES = Set.of("image/jpeg", "image/png");

    private final IntakeService intake;
    private final CurrentUser currentUser;
    private final PhotoRepository photos;
    private final BlobStore blobs;
    private final StorageProperties storage;
    private final QualityChecker quality;
    private final ApplicationEventPublisher events;
    private final OrgAccess access;
    private final Consents consents;

    PhotoService(IntakeService intake, CurrentUser currentUser, PhotoRepository photos, BlobStore blobs,
                 StorageProperties storage, QualityProperties qualityProperties, ApplicationEventPublisher events,
                 OrgAccess access, Consents consents) {
        this.intake = intake;
        this.currentUser = currentUser;
        this.photos = photos;
        this.blobs = blobs;
        this.storage = storage;
        this.quality = new QualityChecker(qualityProperties);
        this.events = events;
        this.access = access;
        this.consents = consents;
    }

    @Transactional
    UploadSlot requestUpload(UUID orgId, UUID cardId, Photo.Side side, String contentType, long sizeBytes) {
        CardItem card = intake.requireCard(orgId, cardId);
        if (access.require(orgId, OrgType.values()).getType() != OrgType.PARTNER) {
            consents.requireAccepted(orgId);
        }
        requireNotFrozen(card);
        if (!ACCEPTED_TYPES.contains(contentType)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Photos must be JPEG or PNG");
        }
        if (sizeBytes <= 0 || sizeBytes > storage.maxUploadBytes()) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE,
                    "Photos must be between 1 byte and " + storage.maxUploadBytes() + " bytes");
        }
        Photo photo = photos.save(new Photo(orgId, card.getId(), side, contentType, sizeBytes, currentUser.id()));
        BlobStore.PresignedUpload upload = blobs.presignUpload(storage.photoBucket(), photo.getStorageKey(), contentType, sizeBytes);
        return new UploadSlot(photo.getId(), upload.url(), upload.method(), upload.headers(), upload.expiresAt());
    }

    /** Idempotent: confirming a photo that was already checked returns the stored result. */
    @Transactional
    PhotoView confirm(UUID orgId, UUID photoId) {
        if (access.require(orgId, OrgType.values()).getType() != OrgType.PARTNER) {
            consents.requireAccepted(orgId);
        }
        // Locks photo then card: a repeated confirm waits and sees the stored result, and confirms of the
        // card's other photos run one at a time, so the capture finishing is published exactly once.
        Photo photo = photos.findLockedByIdAndOrgId(photoId, orgId).orElseThrow(() -> IntakeService.notFound("Photo"));
        CardItem card = intake.requireCardLocked(orgId, photo.getCardItemId());
        if (photo.getStatus() != Photo.Status.AWAITING_UPLOAD) {
            return IntakeViews.photo(photo);
        }
        // Checked again under the card's lock: posting takes the same lock, so a photo can't slip in as a card goes up for sale.
        requireNotFrozen(card);
        BlobStore.StoredObject stored = blobs.head(storage.photoBucket(), photo.getStorageKey())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Nothing uploaded for this photo yet"));

        byte[] bytes = blobs.read(storage.photoBucket(), photo.getStorageKey());
        String sha256 = sha256(bytes);
        boolean wholeCardExpected = !card.isLot() && photo.getSide() != Photo.Side.DETAIL;
        QualityChecker.Result result = quality.check(bytes, wholeCardExpected);

        List<RetakeReason> extra = new ArrayList<>();
        if (stored.size() != photo.getDeclaredSize()) {
            extra.add(RetakeReason.SIZE_MISMATCH);
        }
        if (photos.existsByOrgIdAndSha256AndCardItemIdNot(orgId, sha256, photo.getCardItemId())) {
            extra.add(RetakeReason.DUPLICATE);
        }
        String cropKey = null;
        if (result.card() != null) {
            cropKey = photo.cropStorageKey();
            blobs.putIfAbsent(storage.photoBucket(), cropKey, result.card().cropJpeg(), "image/jpeg");
        }
        boolean wasComplete = IntakeViews.card(card, photos.findByCardItemIdOrderByCreatedAtAsc(card.getId())).captureComplete();
        photo.checked(stored.size(), sha256, result, ExifReader.capturedAt(bytes), extra, cropKey);
        if (cropKey == null && photo.getStatus() == Photo.Status.PASSED) {
            // Buyers are shown this copy (CardItems.shownKey), never the upload with its EXIF location.
            byte[] clean = CardDetector.withoutMetadata(bytes);
            if (clean != null) {
                blobs.putIfAbsent(storage.photoBucket(), photo.viewStorageKey(), clean, "image/jpeg");
            }
        }
        photos.flush();
        if (!wasComplete && IntakeViews.card(card, photos.findByCardItemIdOrderByCreatedAtAsc(card.getId())).captureComplete()) {
            events.publishEvent(new CardItems.CaptureCompleted(orgId, card.getId()));
        } else if (wasComplete && photo.getStatus() == Photo.Status.PASSED && photo.getSide() != Photo.Side.DETAIL) {
            events.publishEvent(new CardItems.PhotoRetaken(orgId, card.getId()));
        }
        return IntakeViews.photo(photo);
    }

    private void requireNotFrozen(CardItem card) {
        String frozen = intake.frozenReason(card.getId());
        if (frozen != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, frozen);
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

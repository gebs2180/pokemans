package com.pokemans.app.condition;

import com.pokemans.app.catalog.Catalog;
import com.pokemans.app.identity.Directory;
import com.pokemans.app.intake.CardItems;
import com.pokemans.app.storage.BlobStore;
import com.pokemans.app.storage.StorageProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Admin-only (by path): labelling the printed frame by hand, and the file the centering scoreboard runs on. */
@RestController
@RequestMapping("/api/admin")
class CenteringLabelController {

    /** @param left a side's border in mm from the card edge, null for "can't see it" */
    record LabelRequest(@NotNull CenteringLabel.Face face, @NotNull UUID photoId,
                        @DecimalMin("0") @DecimalMax("15") Double left, @DecimalMin("0") @DecimalMax("15") Double right,
                        @DecimalMin("0") @DecimalMax("15") Double top, @DecimalMin("0") @DecimalMax("15") Double bottom,
                        boolean hardCard) {
    }

    record LabelView(UUID id, CenteringLabel.Face face, UUID photoId, Double left, Double right, Double top, Double bottom,
                     boolean hardCard, UUID labelledBy, Instant createdAt) {
    }

    /** @param refused the latest measurement refused this face; null if none ran */
    record FaceView(LabelView label, Boolean refused, int photos) {
    }

    record QueueItem(UUID cardId, UUID orgId, String label, String orgName, String identifiedAs, FaceView front, FaceView back) {
    }

    record PhotoRef(UUID id, CenteringLabel.Face face, Instant uploadedAt) {
    }

    record CardView(UUID cardId, UUID orgId, String label, String identifiedAs, LabelView front, LabelView back, List<PhotoRef> photos) {
    }

    /** One passing photo: a link to the upload itself, the bytes the measurer reads in prod. */
    record ScorePhoto(UUID id, URI url) {
    }

    /** @param label the newest label, null if none yet: such a face still counts for photo agreement */
    record ScoreFace(CenteringLabel.Face face, ScoreLabel label, List<ScorePhoto> photos) {
    }

    record ScoreLabel(Double left, Double right, Double top, Double bottom, boolean hardCard, UUID labelledBy, Instant labelledAt) {
    }

    /** @param set the identified set, for splitting by set; null before identification */
    record ScoreCard(UUID cardId, String label, String set, List<ScoreFace> faces) {
    }

    record ScoreFile(Instant exportedAt, String measurerVersion, List<ScoreCard> cards) {
    }

    private final CenteringLabels labels;
    private final ConditionService condition;
    private final CardItems cardItems;
    private final Catalog catalog;
    private final Directory directory;
    private final BlobStore blobs;
    private final StorageProperties storage;

    CenteringLabelController(CenteringLabels labels, ConditionService condition, CardItems cardItems, Catalog catalog,
                             Directory directory, BlobStore blobs, StorageProperties storage) {
        this.labels = labels;
        this.condition = condition;
        this.cardItems = cardItems;
        this.catalog = catalog;
        this.directory = directory;
        this.blobs = blobs;
        this.storage = storage;
    }

    /** Single cards with a complete capture, unlabelled first, and among those the ones the measurer refused. */
    @GetMapping("/centering-labels")
    List<QueueItem> queue() {
        List<CardItems.CardItemRef> cards = singleCards();
        List<UUID> ids = cards.stream().map(CardItems.CardItemRef::id).toList();
        Map<UUID, Map<CenteringLabel.Face, CenteringLabels.FaceStatus>> statuses = labels.statuses(ids);
        Map<UUID, Catalog.IdentifiedCard> identities = catalog.current(ids);
        Map<UUID, String> orgNames = new HashMap<>();
        cards.forEach(c -> orgNames.computeIfAbsent(c.orgId(), directory::orgName));
        return cards.stream()
                .map(c -> {
                    List<CardItems.PhotoRef> photos = cardItems.passedPhotos(c.id());
                    Map<CenteringLabel.Face, CenteringLabels.FaceStatus> status = statuses.get(c.id());
                    return new QueueItem(c.id(), c.orgId(), c.label(), orgNames.get(c.orgId()), identifiedAs(identities.get(c.id())),
                            faceView(status, photos, CenteringLabel.Face.FRONT), faceView(status, photos, CenteringLabel.Face.BACK));
                })
                .sorted(Comparator.comparing((QueueItem q) -> q.front().label() != null)
                        .thenComparing(q -> !Boolean.TRUE.equals(q.front().refused())))
                .toList();
    }

    @GetMapping("/orgs/{orgId}/cards/{cardId}/centering-labels")
    CardView card(@PathVariable UUID orgId, @PathVariable UUID cardId) {
        CardItems.CardItemRef card = condition.requireCard(orgId, cardId);
        Map<CenteringLabel.Face, CenteringLabels.FaceStatus> status = labels.statuses(List.of(cardId)).get(cardId);
        List<PhotoRef> photos = cardItems.passedPhotos(cardId).stream().filter(p -> !p.side().equals("DETAIL"))
                .map(p -> new PhotoRef(p.id(), CenteringLabel.Face.valueOf(p.side()), p.uploadedAt()))
                .toList();
        return new CardView(card.id(), card.orgId(), card.label(), identifiedAs(catalog.current(cardId).orElse(null)),
                view(status.get(CenteringLabel.Face.FRONT).label()), view(status.get(CenteringLabel.Face.BACK).label()), photos);
    }

    /** The photo straightened as the measurer straightens it, or why it can't be. Takes a second or so. */
    @GetMapping("/orgs/{orgId}/cards/{cardId}/photos/{photoId}/straightened")
    CenteringLabels.Straightened straightened(@PathVariable UUID orgId, @PathVariable UUID cardId, @PathVariable UUID photoId) {
        condition.requireCard(orgId, cardId);
        return labels.straighten(cardId, photoId);
    }

    @PostMapping("/orgs/{orgId}/cards/{cardId}/centering-labels")
    @ResponseStatus(HttpStatus.CREATED)
    LabelView label(@PathVariable UUID orgId, @PathVariable UUID cardId, @Valid @RequestBody LabelRequest request) {
        return view(labels.save(condition.requireCard(orgId, cardId), request.face(), request.photoId(), request.left(), request.right(),
                request.top(), request.bottom(), request.hardCard()));
    }

    /**
     * What the scoreboard runs on: every single card's photos and newest labels. The links are short-lived
     * and lead to the uploads with their EXIF, so the file stays on the admin's machine.
     */
    @GetMapping("/centering-labels/export")
    ScoreFile export() {
        List<CardItems.CardItemRef> cards = singleCards();
        List<UUID> ids = cards.stream().map(CardItems.CardItemRef::id).toList();
        Map<UUID, Map<CenteringLabel.Face, CenteringLabels.FaceStatus>> statuses = labels.statuses(ids);
        Map<UUID, Catalog.IdentifiedCard> identities = catalog.current(ids);
        return new ScoreFile(Instant.now(), CenteringPreGradeProvider.VERSION, cards.stream().map(c -> {
            List<CardItems.PhotoRef> photos = cardItems.passedPhotos(c.id());
            List<ScoreFace> faces = List.of(CenteringLabel.Face.values()).stream()
                    .map(face -> new ScoreFace(face, scoreLabel(statuses.get(c.id()).get(face).label()),
                            CenteringLabels.faces(photos, face).stream()
                                    .map(p -> new ScorePhoto(p.id(), blobs.presignDownload(storage.photoBucket(), p.storageKey())))
                                    .toList()))
                    .toList();
            Catalog.IdentifiedCard identity = identities.get(c.id());
            return new ScoreCard(c.id(), c.label(), identity == null ? null : identity.setName(), faces);
        }).toList());
    }

    private List<CardItems.CardItemRef> singleCards() {
        return cardItems.captureComplete().stream().filter(c -> !c.lot()).toList();
    }

    private static FaceView faceView(Map<CenteringLabel.Face, CenteringLabels.FaceStatus> status, List<CardItems.PhotoRef> photos,
                                     CenteringLabel.Face face) {
        CenteringLabels.FaceStatus one = status.get(face);
        return new FaceView(view(one.label()), one.refused(), CenteringLabels.faces(photos, face).size());
    }

    private static String identifiedAs(Catalog.IdentifiedCard id) {
        return id == null ? null : id.name() + " · " + id.setName() + " " + id.number();
    }

    private static LabelView view(CenteringLabel l) {
        return l == null ? null : new LabelView(l.getId(), l.getFace(), l.getPhotoId(), l.getLeftMm(), l.getRightMm(), l.getTopMm(),
                l.getBottomMm(), l.isHardCard(), l.getLabelledBy(), l.getCreatedAt());
    }

    private static ScoreLabel scoreLabel(CenteringLabel l) {
        return l == null ? null : new ScoreLabel(l.getLeftMm(), l.getRightMm(), l.getTopMm(), l.getBottomMm(), l.isHardCard(),
                l.getLabelledBy(), l.getCreatedAt());
    }
}

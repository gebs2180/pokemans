package com.pokemans.app.intake;

import com.pokemans.app.identity.CurrentUser;
import com.pokemans.app.identity.Org;
import com.pokemans.app.identity.OrgAccess;
import com.pokemans.app.identity.OrgType;
import com.pokemans.app.intake.IntakeViews.CardView;
import com.pokemans.app.intake.IntakeViews.ContainerDetail;
import com.pokemans.app.intake.IntakeViews.ContainerSummary;
import com.pokemans.app.storage.BlobStore;
import com.pokemans.app.storage.StorageProperties;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Batches (partner orgs), collections (collector and contributor orgs) and the cards in them. */
@Service
class IntakeService {

    /** Org types whose cards live in collections. */
    static final OrgType[] COLLECTION_ORGS = {OrgType.COLLECTOR, OrgType.CONTRIBUTOR};

    private final OrgAccess access;
    private final CurrentUser currentUser;
    private final SubmissionBatchRepository batches;
    private final CardCollectionRepository collections;
    private final CardItemRepository cards;
    private final PhotoRepository photos;
    private final BlobStore blobs;
    private final StorageProperties storage;
    private final ObjectProvider<CaptureFreeze> freezes;

    IntakeService(OrgAccess access, CurrentUser currentUser, SubmissionBatchRepository batches,
                  CardCollectionRepository collections, CardItemRepository cards, PhotoRepository photos, BlobStore blobs,
                  StorageProperties storage, ObjectProvider<CaptureFreeze> freezes) {
        this.access = access;
        this.currentUser = currentUser;
        this.batches = batches;
        this.collections = collections;
        this.cards = cards;
        this.photos = photos;
        this.blobs = blobs;
        this.storage = storage;
        this.freezes = freezes;
    }

    // Batches

    @Transactional
    ContainerSummary createBatch(UUID orgId, String name) {
        Org org = access.require(orgId, OrgType.PARTNER);
        SubmissionBatch batch = batches.save(new SubmissionBatch(org.getId(), name, currentUser.id()));
        return new ContainerSummary(batch.getId(), batch.getName(), batch.getCreatedAt(), 0);
    }

    @Transactional(readOnly = true)
    List<ContainerSummary> listBatches(UUID orgId) {
        access.require(orgId, OrgType.PARTNER);
        return batches.findByOrgIdOrderByCreatedAtDesc(orgId).stream()
                .map(b -> new ContainerSummary(b.getId(), b.getName(), b.getCreatedAt(), cards.countByBatchId(b.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    ContainerDetail batch(UUID orgId, UUID batchId) {
        access.require(orgId, OrgType.PARTNER);
        SubmissionBatch batch = batches.findByIdAndOrgId(batchId, orgId).orElseThrow(() -> notFound("Batch"));
        return new ContainerDetail(batch.getId(), batch.getName(), batch.getCreatedAt(),
                cardViews(cards.findByBatchIdOrderByCreatedAtAsc(batchId)));
    }

    @Transactional
    CardView addCardToBatch(UUID orgId, UUID batchId, String label, boolean lot, int quantity) {
        access.require(orgId, OrgType.PARTNER);
        batches.findByIdAndOrgId(batchId, orgId).orElseThrow(() -> notFound("Batch"));
        CardItem card = cards.save(CardItem.inBatch(orgId, batchId, label, lot, quantity, currentUser.id()));
        return IntakeViews.card(card, List.of());
    }

    // Collections

    @Transactional
    ContainerSummary createCollection(UUID orgId, String name) {
        Org org = access.require(orgId, COLLECTION_ORGS);
        CardCollection collection = collections.save(new CardCollection(org.getId(), name, currentUser.id()));
        return new ContainerSummary(collection.getId(), collection.getName(), collection.getCreatedAt(), 0);
    }

    @Transactional(readOnly = true)
    List<ContainerSummary> listCollections(UUID orgId) {
        access.require(orgId, COLLECTION_ORGS);
        return collections.findByOrgIdOrderByCreatedAtDesc(orgId).stream()
                .map(c -> new ContainerSummary(c.getId(), c.getName(), c.getCreatedAt(), cards.countByCollectionId(c.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    ContainerDetail collection(UUID orgId, UUID collectionId) {
        access.require(orgId, COLLECTION_ORGS);
        CardCollection collection = collections.findByIdAndOrgId(collectionId, orgId).orElseThrow(() -> notFound("Collection"));
        return new ContainerDetail(collection.getId(), collection.getName(), collection.getCreatedAt(),
                cardViews(cards.findByCollectionIdOrderByCreatedAtAsc(collectionId)));
    }

    @Transactional
    CardView addCardToCollection(UUID orgId, UUID collectionId, String label, boolean lot, int quantity) {
        access.require(orgId, COLLECTION_ORGS);
        collections.findByIdAndOrgId(collectionId, orgId).orElseThrow(() -> notFound("Collection"));
        CardItem card = cards.save(CardItem.inCollection(orgId, collectionId, label, lot, quantity, currentUser.id()));
        return IntakeViews.card(card, List.of());
    }

    /** A card in any org the caller can reach, whichever kind it is. */
    CardItem requireCard(UUID orgId, UUID cardId) {
        return checkKind(orgId, cards.findByIdAndOrgId(cardId, orgId));
    }

    /** {@link #requireCard}, holding the card's row lock until the transaction ends. */
    CardItem requireCardLocked(UUID orgId, UUID cardId) {
        return checkKind(orgId, cards.findLockedByIdAndOrgId(cardId, orgId));
    }

    private CardItem checkKind(UUID orgId, java.util.Optional<CardItem> found) {
        // Membership first, so outsiders can't tell whether a card id exists in the org.
        access.require(orgId, OrgType.values());
        CardItem card = found.orElseThrow(() -> notFound("Card"));
        if (card.getBatchId() != null) {
            access.require(orgId, OrgType.PARTNER);
        } else {
            access.require(orgId, COLLECTION_ORGS);
        }
        return card;
    }

    @Transactional(readOnly = true)
    CardView card(UUID orgId, UUID cardId) {
        CardItem card = requireCard(orgId, cardId);
        return IntakeViews.card(card, photos.findByCardItemIdOrderByCreatedAtAsc(card.getId()),
                p -> blobs.presignDownload(storage.photoBucket(), p.getCropKey() != null ? p.getCropKey() : p.getStorageKey()),
                frozenReason(card.getId()));
    }

    /** Why the card's photos can't change any more (it is posted or sold), or null. */
    String frozenReason(UUID cardId) {
        return freezes.orderedStream().map(f -> f.frozenReason(cardId)).flatMap(Optional::stream).findFirst().orElse(null);
    }

    private List<CardView> cardViews(List<CardItem> items) {
        if (items.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<Photo>> byCard = photos.findByCardItemIdInOrderByCreatedAtAsc(items.stream().map(CardItem::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(Photo::getCardItemId));
        return items.stream().map(c -> IntakeViews.card(c, byCard.getOrDefault(c.getId(), List.of()), p -> null, frozenReason(c.getId())))
                .toList();
    }

    static ResponseStatusException notFound(String what) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, what + " not found");
    }
}

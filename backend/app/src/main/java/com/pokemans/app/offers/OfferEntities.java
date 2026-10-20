package com.pokemans.app.offers;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Our offer on one card, valid until {@link #getExpiresAt()}. Append-only. */
@Entity
@Table(name = "card_offer")
class CardOffer {

    @Id
    private UUID id;
    private UUID orgId;
    private UUID cardItemId;
    private UUID catalogCardId;
    private UUID assessmentId;
    private UUID snapshotSetId;
    private boolean graded;
    @JdbcTypeCode(SqlTypes.JSON)
    private String inputs;
    private String parameterSet;
    private BigDecimal buyerMargin;
    @JdbcTypeCode(SqlTypes.JSON)
    private String ladder;
    private BigDecimal expectedOffer;
    private BigDecimal fmv;
    private String confidence;
    private boolean subjectToAuthentication;
    @JdbcTypeCode(SqlTypes.JSON)
    private String trace;
    private UUID createdBy;
    private Instant createdAt;
    private Instant expiresAt;

    protected CardOffer() {
    }

    CardOffer(UUID orgId, UUID cardItemId, UUID catalogCardId, UUID assessmentId, UUID snapshotSetId, boolean graded, String inputs,
              String parameterSet, BigDecimal buyerMargin, String ladder, BigDecimal expectedOffer, BigDecimal fmv, String confidence,
              boolean subjectToAuthentication, String trace, UUID createdBy, Instant createdAt, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.orgId = orgId;
        this.cardItemId = cardItemId;
        this.catalogCardId = catalogCardId;
        this.assessmentId = assessmentId;
        this.snapshotSetId = snapshotSetId;
        this.graded = graded;
        this.inputs = inputs;
        this.parameterSet = parameterSet;
        this.buyerMargin = buyerMargin;
        this.ladder = ladder;
        this.expectedOffer = expectedOffer;
        this.fmv = fmv;
        this.confidence = confidence;
        this.subjectToAuthentication = subjectToAuthentication;
        this.trace = trace;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    UUID getId() {
        return id;
    }

    UUID getOrgId() {
        return orgId;
    }

    UUID getCardItemId() {
        return cardItemId;
    }

    boolean isGraded() {
        return graded;
    }

    String getInputs() {
        return inputs;
    }

    String getLadder() {
        return ladder;
    }

    BigDecimal getExpectedOffer() {
        return expectedOffer;
    }

    BigDecimal getFmv() {
        return fmv;
    }

    String getConfidence() {
        return confidence;
    }

    boolean isSubjectToAuthentication() {
        return subjectToAuthentication;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getExpiresAt() {
        return expiresAt;
    }
}

/** An offer posted to buyers. Append-only: what happens to it is in {@link ListingEvent}. */
@Entity
@Table(name = "sale_listing")
class SaleListing {

    @Id
    private UUID id;
    private UUID offerId;
    private UUID orgId;
    private UUID cardItemId;
    private UUID listedBy;
    private Instant createdAt;
    private Instant expiresAt;

    protected SaleListing() {
    }

    SaleListing(UUID offerId, UUID orgId, UUID cardItemId, UUID listedBy, Instant createdAt, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.offerId = offerId;
        this.orgId = orgId;
        this.cardItemId = cardItemId;
        this.listedBy = listedBy;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    UUID getId() {
        return id;
    }

    UUID getOfferId() {
        return offerId;
    }

    UUID getOrgId() {
        return orgId;
    }

    UUID getCardItemId() {
        return cardItemId;
    }

    UUID getListedBy() {
        return listedBy;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getExpiresAt() {
        return expiresAt;
    }
}

/** Something that happened to a listing. Append-only; a listing is closed at most once. */
@Entity
@Table(name = "listing_event")
class ListingEvent {

    enum Type { ACCEPTED, WITHDRAWN, INSPECTED }

    /** Why a buyer declined after inspection: the only reasons the buyer terms allow. */
    enum DeclinedReason { NOT_GENUINE, NOT_THE_LISTED_CARD, DOES_NOT_MATCH_PHOTOS }

    @Id
    private UUID id;
    private UUID listingId;
    @Enumerated(EnumType.STRING)
    private Type type;
    private UUID actorOrgId;
    private UUID actorUserId;
    private BigDecimal inspectedGrade;
    private Boolean authentic;
    private BigDecimal pricePaid;
    @Enumerated(EnumType.STRING)
    private DeclinedReason declinedReason;
    private String note;
    private Instant createdAt;

    protected ListingEvent() {
    }

    private ListingEvent(UUID listingId, Type type, UUID actorOrgId, UUID actorUserId) {
        this.id = UUID.randomUUID();
        this.listingId = listingId;
        this.type = type;
        this.actorOrgId = actorOrgId;
        this.actorUserId = actorUserId;
        this.createdAt = Instant.now();
    }

    static ListingEvent accepted(UUID listingId, UUID buyerOrgId, UUID userId) {
        return new ListingEvent(listingId, Type.ACCEPTED, buyerOrgId, userId);
    }

    static ListingEvent withdrawn(UUID listingId, UUID sellerOrgId, UUID userId) {
        return new ListingEvent(listingId, Type.WITHDRAWN, sellerOrgId, userId);
    }

    static ListingEvent inspected(UUID listingId, UUID buyerOrgId, UUID userId, BigDecimal grade, boolean authentic,
                                  BigDecimal pricePaid, DeclinedReason declinedReason, String note) {
        ListingEvent e = new ListingEvent(listingId, Type.INSPECTED, buyerOrgId, userId);
        e.inspectedGrade = grade;
        e.authentic = authentic;
        e.pricePaid = pricePaid;
        e.declinedReason = declinedReason;
        e.note = note;
        return e;
    }

    UUID getListingId() {
        return listingId;
    }

    Type getType() {
        return type;
    }

    UUID getActorOrgId() {
        return actorOrgId;
    }

    UUID getActorUserId() {
        return actorUserId;
    }

    BigDecimal getInspectedGrade() {
        return inspectedGrade;
    }

    Boolean getAuthentic() {
        return authentic;
    }

    BigDecimal getPricePaid() {
        return pricePaid;
    }

    DeclinedReason getDeclinedReason() {
        return declinedReason;
    }

    String getNote() {
        return note;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}

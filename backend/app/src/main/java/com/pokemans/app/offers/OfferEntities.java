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

/** Our firm offer on one card, valid until {@link #getExpiresAt()}. Append-only. */
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
    /** The firm price: Σ p × the buyer's price at each grade. */
    private BigDecimal price;
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
              String parameterSet, BigDecimal buyerMargin, String ladder, BigDecimal price, BigDecimal fmv, String confidence,
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
        this.price = price;
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

    BigDecimal getPrice() {
        return price;
    }

    BigDecimal getFmv() {
        return fmv;
    }

    BigDecimal getBuyerMargin() {
        return buyerMargin;
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

    /**
     * RECEIVED: arrived genuine and as shown, and the buyer paid. RETURNED: sent back for an allowed
     * reason. INSPECTED: a grade recorded under the per-grade offers that came before firm prices.
     * VOIDED: we took an open listing down, with its offer. CANCELLED: we undid a taken deal. Both are
     * the admin's, with no actor org and the reason in the note (Zach, 2026-10-21).
     */
    enum Type { ACCEPTED, WITHDRAWN, INSPECTED, RECEIVED, RETURNED, VOIDED, CANCELLED }

    /** Why a buyer returns a card: the only reasons the buyer terms allow. */
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

    static ListingEvent received(UUID listingId, UUID buyerOrgId, UUID userId, BigDecimal pricePaid, String note) {
        ListingEvent e = new ListingEvent(listingId, Type.RECEIVED, buyerOrgId, userId);
        e.authentic = true;
        e.pricePaid = pricePaid;
        e.note = note;
        return e;
    }

    static ListingEvent returned(UUID listingId, UUID buyerOrgId, UUID userId, DeclinedReason reason, String note) {
        ListingEvent e = new ListingEvent(listingId, Type.RETURNED, buyerOrgId, userId);
        e.authentic = reason != DeclinedReason.NOT_GENUINE;
        e.declinedReason = reason;
        e.note = note;
        return e;
    }

    static ListingEvent voided(UUID listingId, UUID adminUserId, String reason) {
        ListingEvent e = new ListingEvent(listingId, Type.VOIDED, null, adminUserId);
        e.note = reason;
        return e;
    }

    static ListingEvent cancelled(UUID listingId, UUID adminUserId, String reason) {
        ListingEvent e = new ListingEvent(listingId, Type.CANCELLED, null, adminUserId);
        e.note = reason;
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

/** We voided an offer: it can't be posted again (Zach, 2026-10-21). Append-only. */
@Entity
@Table(name = "card_offer_void")
class CardOfferVoid {

    @Id
    private UUID id;
    private UUID offerId;
    private String reason;
    private UUID voidedBy;
    private Instant createdAt;

    protected CardOfferVoid() {
    }

    CardOfferVoid(UUID offerId, String reason, UUID voidedBy) {
        this.id = UUID.randomUUID();
        this.offerId = offerId;
        this.reason = reason;
        this.voidedBy = voidedBy;
        this.createdAt = Instant.now();
    }

    UUID getOfferId() {
        return offerId;
    }

    String getReason() {
        return reason;
    }

    UUID getVoidedBy() {
        return voidedBy;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}

/**
 * A label marked to be refunded, and what its provider said when asked (Zach, 2026-10-21). A request
 * the provider never answered can be made again. Append-only.
 */
@Entity
@Table(name = "label_refund")
class LabelRefund {

    @Id
    private UUID id;
    private UUID labelId;
    private UUID listingId;
    private String reason;
    private UUID requestedBy;
    private String provider;
    private String providerStatus;
    private String providerError;
    private Instant createdAt;

    protected LabelRefund() {
    }

    LabelRefund(UUID labelId, UUID listingId, String reason, UUID requestedBy, String provider, String providerStatus,
                String providerError) {
        this.id = UUID.randomUUID();
        this.labelId = labelId;
        this.listingId = listingId;
        this.reason = reason;
        this.requestedBy = requestedBy;
        this.provider = provider;
        this.providerStatus = providerStatus;
        this.providerError = providerError;
        this.createdAt = Instant.now();
    }

    UUID getLabelId() {
        return labelId;
    }

    String getReason() {
        return reason;
    }

    UUID getRequestedBy() {
        return requestedBy;
    }

    String getProviderStatus() {
        return providerStatus;
    }

    String getProviderError() {
        return providerError;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}

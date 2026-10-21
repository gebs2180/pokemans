package com.pokemans.app.offers;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface CardOfferRepository extends JpaRepository<CardOffer, UUID> {

    Optional<CardOffer> findFirstByCardItemIdOrderByCreatedAtDesc(UUID cardItemId);

    /** The newest offers, for the admin. */
    List<CardOffer> findTop200ByOrderByCreatedAtDesc();
}

interface CardOfferVoidRepository extends JpaRepository<CardOfferVoid, UUID> {

    Optional<CardOfferVoid> findByOfferId(UUID offerId);

    List<CardOfferVoid> findByOfferIdIn(Collection<UUID> offerIds);
}

interface LabelRefundRepository extends JpaRepository<LabelRefund, UUID> {

    /** Every request for these labels, oldest first; the latest is the one that counts. */
    List<LabelRefund> findByLabelIdInOrderByCreatedAtAsc(Collection<UUID> labelIds);
}

interface SaleListingRepository extends JpaRepository<SaleListing, UUID> {

    Optional<SaleListing> findFirstByCardItemIdOrderByCreatedAtDesc(UUID cardItemId);

    Optional<SaleListing> findByIdAndOrgId(UUID id, UUID orgId);

    List<SaleListing> findByOfferIdIn(Collection<UUID> offerIds);

    /** Listings not yet expired and never accepted or withdrawn, newest first. */
    @Query("""
            select l from SaleListing l where l.expiresAt > :now and not exists (
              select e from ListingEvent e where e.listingId = l.id
              and e.type in (com.pokemans.app.offers.ListingEvent.Type.ACCEPTED, com.pokemans.app.offers.ListingEvent.Type.WITHDRAWN,
                           com.pokemans.app.offers.ListingEvent.Type.VOIDED))
            order by l.createdAt desc""")
    List<SaleListing> findOpen(Instant now);

    /** Listings this buyer org accepted, newest first. */
    @Query("""
            select l from SaleListing l, ListingEvent e where e.listingId = l.id
            and e.type = com.pokemans.app.offers.ListingEvent.Type.ACCEPTED and e.actorOrgId = :buyerOrgId
            order by e.createdAt desc""")
    List<SaleListing> findAcceptedBy(UUID buyerOrgId);

    /** Every accepted listing, oldest accept first. */
    @Query("""
            select l from SaleListing l, ListingEvent e where e.listingId = l.id
            and e.type = com.pokemans.app.offers.ListingEvent.Type.ACCEPTED
            order by e.createdAt asc""")
    List<SaleListing> findAllAccepted();
}

interface ListingEventRepository extends JpaRepository<ListingEvent, UUID> {

    List<ListingEvent> findByListingIdInOrderByCreatedAtAsc(Collection<UUID> listingIds);

    boolean existsByListingIdAndType(UUID listingId, ListingEvent.Type type);
}

package com.pokemans.app.offers;

import com.pokemans.app.identity.CurrentUser;
import com.pokemans.app.identity.Directory;
import com.pokemans.app.storage.BlobStore;
import com.pokemans.app.storage.StorageProperties;
import com.pokemans.app.valuation.CardInputs;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Posting offers to buyers and what happens next: the first buyer to accept takes the card, the
 * seller may withdraw until then, and the buyer records the inspection. No access checks: the
 * controllers have done them.
 */
@Service
class ListingService {

    enum Status { OPEN, ACCEPTED, WITHDRAWN, EXPIRED }

    record RungView(String grade, BigDecimal probability, BigDecimal price) {
    }

    /** @param expired true once the offer is past its validity: price it again before posting */
    record OfferView(UUID id, BigDecimal expectedOffer, BigDecimal fmv, String confidence, boolean graded,
                     boolean subjectToAuthentication, List<RungView> ladder, Instant createdAt, Instant expiresAt, boolean expired) {
    }

    record Party(String orgName, String email) {
    }

    /** @param ladderPrice what the offer said for the grade found (half grades fold down) */
    record InspectionView(BigDecimal inspectedGrade, boolean authentic, BigDecimal pricePaid, BigDecimal ladderPrice,
                          String declinedReason, String note, Instant recordedAt) {
    }

    /** @param buyer who took it, once accepted (shown to the seller) */
    record ListingView(UUID id, UUID offerId, Status status, Instant listedAt, Instant expiresAt, Instant closedAt, Party buyer,
                       InspectionView inspection) {
    }

    record SaleView(OfferView offer, ListingView listing) {
    }

    record CardView(String name, String setName, String number, String language, String edition, String variant, String label,
                    Map<String, BigDecimal> distribution, String gradedAs) {
    }

    record PhotoLink(String side, String url) {
    }

    record MarketItem(UUID listingId, CardView card, OfferView offer, List<PhotoLink> photos, Instant listedAt, Instant expiresAt) {
    }

    record DealView(UUID listingId, CardView card, OfferView offer, List<PhotoLink> photos, Party seller, Instant acceptedAt,
                    InspectionView inspection) {
    }

    record InspectionRequest(BigDecimal inspectedGrade, boolean authentic, BigDecimal pricePaid, ListingEvent.DeclinedReason declinedReason,
                             String note) {
    }

    private final CardOfferRepository offers;
    private final SaleListingRepository listings;
    private final ListingEventRepository events;
    private final OffersProperties properties;
    private final Directory directory;
    private final BlobStore blobs;
    private final StorageProperties storage;
    private final CurrentUser currentUser;
    private final JdbcTemplate jdbc;

    ListingService(CardOfferRepository offers, SaleListingRepository listings, ListingEventRepository events, OffersProperties properties,
                   Directory directory, BlobStore blobs, StorageProperties storage, CurrentUser currentUser, JdbcTemplate jdbc) {
        this.offers = offers;
        this.listings = listings;
        this.events = events;
        this.properties = properties;
        this.directory = directory;
        this.blobs = blobs;
        this.storage = storage;
        this.currentUser = currentUser;
        this.jdbc = jdbc;
    }

    /** The seller's view: the latest offer on the card and its latest listing. */
    @Transactional(readOnly = true)
    SaleView sale(UUID cardItemId) {
        Optional<CardOffer> offer = offers.findFirstByCardItemIdOrderByCreatedAtDesc(cardItemId);
        Optional<SaleListing> listing = listings.findFirstByCardItemIdOrderByCreatedAtDesc(cardItemId);
        Map<UUID, List<ListingEvent>> history = history(listing.stream().toList());
        return new SaleView(offer.map(ListingService::offerView).orElse(null),
                listing.map(l -> listingView(l, history.getOrDefault(l.getId(), List.of()), true)).orElse(null));
    }

    /** Posts the offer to every buyer. One open or accepted listing per card at a time. */
    @Transactional
    SaleListing post(UUID orgId, UUID cardItemId, UUID offerId) {
        // Serialize posting per card, so two clicks can't open two listings.
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtext(?))", Object.class, "listing:" + cardItemId);
        CardOffer offer = offers.findById(offerId).filter(o -> o.getCardItemId().equals(cardItemId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Offer not found"));
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (!offer.getExpiresAt().isAfter(now)) {
            throw conflict("This offer has expired. Get a new one first.");
        }
        Optional<SaleListing> latest = listings.findFirstByCardItemIdOrderByCreatedAtDesc(cardItemId);
        if (latest.isPresent()) {
            Status status = status(latest.get(), history(List.of(latest.get())).getOrDefault(latest.get().getId(), List.of()), now);
            if (status == Status.OPEN) {
                throw conflict("This card is already posted to buyers.");
            }
            if (status == Status.ACCEPTED) {
                throw conflict("A buyer has already taken this card.");
            }
        }
        return listings.save(new SaleListing(offerId, orgId, cardItemId, currentUser.id(), now, now.plus(properties.listingOpenFor())));
    }

    @Transactional
    void withdraw(UUID orgId, UUID listingId) {
        SaleListing listing = listings.findByIdAndOrgId(listingId, orgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Listing not found"));
        requireOpen(listing, Map.of(Status.ACCEPTED, "A buyer has already taken this card, so it can't be withdrawn.",
                Status.WITHDRAWN, "This listing is already withdrawn.", Status.EXPIRED, "This listing has already expired."));
        close(ListingEvent.withdrawn(listingId, orgId, currentUser.id()), "A buyer took this card before it was withdrawn.");
    }

    @Transactional(readOnly = true)
    List<MarketItem> market() {
        List<SaleListing> open = listings.findOpen(Instant.now());
        Map<UUID, CardOffer> byId = offers.findAllById(open.stream().map(SaleListing::getOfferId).toList()).stream()
                .collect(Collectors.toMap(CardOffer::getId, o -> o));
        List<MarketItem> items = new ArrayList<>();
        for (SaleListing l : open) {
            CardOffer offer = byId.get(l.getOfferId());
            CardInputs in = OfferService.inputs(offer);
            items.add(new MarketItem(l.getId(), card(in), offerView(offer), photos(in), l.getCreatedAt(), l.getExpiresAt()));
        }
        return items;
    }

    /** First come, first served: the first buyer to accept takes the card, everyone after gets 409. */
    @Transactional
    void accept(UUID buyerOrgId, UUID listingId) {
        SaleListing listing = listings.findById(listingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Listing not found"));
        requireOpen(listing, Map.of(Status.ACCEPTED, "Another buyer took this card first.",
                Status.WITHDRAWN, "The seller withdrew this card.", Status.EXPIRED, "This listing has expired."));
        close(ListingEvent.accepted(listingId, buyerOrgId, currentUser.id()), "Another buyer took this card first.");
    }

    @Transactional(readOnly = true)
    List<DealView> deals(UUID buyerOrgId) {
        List<SaleListing> accepted = listings.findAcceptedBy(buyerOrgId);
        Map<UUID, List<ListingEvent>> history = history(accepted);
        List<DealView> deals = new ArrayList<>();
        for (SaleListing l : accepted) {
            CardOffer offer = offers.findById(l.getOfferId()).orElseThrow();
            CardInputs in = OfferService.inputs(offer);
            List<ListingEvent> h = history.getOrDefault(l.getId(), List.of());
            ListingEvent acceptedEvent = h.stream().filter(e -> e.getType() == ListingEvent.Type.ACCEPTED).findFirst().orElseThrow();
            Directory.Contact seller = directory.contact(l.getOrgId(), l.getListedBy());
            deals.add(new DealView(l.getId(), card(in), offerView(offer), photos(in), new Party(seller.orgName(), seller.email()),
                    acceptedEvent.getCreatedAt(), inspection(h, offer)));
        }
        return deals;
    }

    /** The buyer's record of the card in hand, once per deal. */
    @Transactional
    void inspect(UUID buyerOrgId, UUID listingId, InspectionRequest request) {
        SaleListing listing = listings.findById(listingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Listing not found"));
        List<ListingEvent> h = history(List.of(listing)).getOrDefault(listingId, List.of());
        boolean ours = h.stream().anyMatch(e -> e.getType() == ListingEvent.Type.ACCEPTED && e.getActorOrgId().equals(buyerOrgId));
        if (!ours) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Deal not found");
        }
        boolean declined = request.declinedReason() != null;
        if (declined && request.pricePaid() != null && request.pricePaid().signum() > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A declined card has no price paid");
        }
        if (!declined && request.pricePaid() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Record the price paid, or why you declined");
        }
        if (!declined && !request.authentic()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A card that isn't genuine is declined as NOT_GENUINE");
        }
        try {
            events.saveAndFlush(ListingEvent.inspected(listingId, buyerOrgId, currentUser.id(), request.inspectedGrade(),
                    request.authentic(), declined ? null : request.pricePaid(), request.declinedReason(), blankToNull(request.note())));
        } catch (DataIntegrityViolationException e) {
            throw conflict("The inspection for this card is already recorded.");
        }
    }

    /** 409 with the reason for the listing's state, unless it is open. */
    private void requireOpen(SaleListing listing, Map<Status, String> reasons) {
        Status status = status(listing, history(List.of(listing)).getOrDefault(listing.getId(), List.of()), Instant.now());
        if (status != Status.OPEN) {
            throw conflict(reasons.get(status));
        }
    }

    /** Saves a closing event; the database lets only one close a listing, so a lost race is a 409. */
    private void close(ListingEvent event, String lostRace) {
        try {
            events.saveAndFlush(event);
        } catch (DataIntegrityViolationException e) {
            throw conflict(lostRace);
        }
    }

    private Map<UUID, List<ListingEvent>> history(List<SaleListing> ls) {
        if (ls.isEmpty()) {
            return Map.of();
        }
        return events.findByListingIdInOrderByCreatedAtAsc(ls.stream().map(SaleListing::getId).toList()).stream()
                .collect(Collectors.groupingBy(ListingEvent::getListingId, LinkedHashMap::new, Collectors.toList()));
    }

    static Status status(SaleListing listing, List<ListingEvent> history, Instant now) {
        for (ListingEvent e : history) {
            if (e.getType() == ListingEvent.Type.ACCEPTED) {
                return Status.ACCEPTED;
            }
            if (e.getType() == ListingEvent.Type.WITHDRAWN) {
                return Status.WITHDRAWN;
            }
        }
        return listing.getExpiresAt().isAfter(now) ? Status.OPEN : Status.EXPIRED;
    }

    private ListingView listingView(SaleListing l, List<ListingEvent> history, boolean showBuyer) {
        Status status = status(l, history, Instant.now());
        ListingEvent closed = history.stream()
                .filter(e -> e.getType() == ListingEvent.Type.ACCEPTED || e.getType() == ListingEvent.Type.WITHDRAWN).findFirst().orElse(null);
        Party buyer = null;
        if (showBuyer && closed != null && closed.getType() == ListingEvent.Type.ACCEPTED) {
            Directory.Contact c = directory.contact(closed.getActorOrgId(), closed.getActorUserId());
            buyer = new Party(c.orgName(), c.email());
        }
        CardOffer offer = offers.findById(l.getOfferId()).orElseThrow();
        return new ListingView(l.getId(), l.getOfferId(), status, l.getCreatedAt(), l.getExpiresAt(),
                closed == null ? null : closed.getCreatedAt(), buyer, inspection(history, offer));
    }

    private static InspectionView inspection(List<ListingEvent> history, CardOffer offer) {
        return history.stream().filter(e -> e.getType() == ListingEvent.Type.INSPECTED).findFirst()
                .map(e -> new InspectionView(e.getInspectedGrade(), Boolean.TRUE.equals(e.getAuthentic()), e.getPricePaid(),
                        OfferService.priceAt(OfferService.ladder(offer), e.getInspectedGrade()),
                        e.getDeclinedReason() == null ? null : e.getDeclinedReason().name(), e.getNote(), e.getCreatedAt()))
                .orElse(null);
    }

    static OfferView offerView(CardOffer o) {
        List<RungView> ladder = OfferService.ladder(o).stream()
                .map(r -> new RungView(r.grade().toString(), r.probability(), r.price())).toList();
        return new OfferView(o.getId(), o.getExpectedOffer(), o.getFmv(), o.getConfidence(), o.isGraded(), o.isSubjectToAuthentication(),
                ladder, o.getCreatedAt(), o.getExpiresAt(), !o.getExpiresAt().isAfter(Instant.now()));
    }

    private static CardView card(CardInputs in) {
        Map<String, BigDecimal> distribution = new LinkedHashMap<>();
        if (in.assessment() != null) {
            in.assessment().distribution().forEach((g, p) -> distribution.put(g.toString(), p));
        }
        CardInputs.Identity id = in.identity();
        return new CardView(id.name(), id.setName(), id.number(), id.language(), id.edition(), id.variant(), in.label(), distribution,
                in.graded() == null ? null : in.graded().gradingCompany() + " " + in.graded().grade().stripTrailingZeros().toPlainString());
    }

    private List<PhotoLink> photos(CardInputs in) {
        return in.photos().stream()
                .map(p -> new PhotoLink(p.side(), blobs.presignDownload(storage.photoBucket(), p.cropKey() != null ? p.cropKey() : p.storageKey())
                        .toString()))
                .toList();
    }

    private static ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}

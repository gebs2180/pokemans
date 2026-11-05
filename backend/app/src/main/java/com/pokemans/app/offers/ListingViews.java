package com.pokemans.app.offers;

import static com.pokemans.app.offers.ListingStateMachine.progress;
import static com.pokemans.app.offers.ListingStateMachine.status;

import com.pokemans.app.identity.Directory;
import com.pokemans.app.intake.CardItems;
import com.pokemans.app.offers.ListingStateMachine.DealStage;
import com.pokemans.app.offers.ListingStateMachine.Progress;
import com.pokemans.app.offers.ListingStateMachine.Status;
import com.pokemans.app.storage.BlobStore;
import com.pokemans.app.storage.StorageProperties;
import com.pokemans.app.valuation.CardInputs;
import com.pokemans.valuation.GradePrice;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * What sellers, buyers and the admin see of offers, listings and deals: the JSON records and how each
 * is built from the stored rows (review finding 23). Builds views only; it never writes.
 */
@Component
class ListingViews {

    record RungView(String grade, BigDecimal probability, BigDecimal price) {
    }

    /** Something we undid, and why (Zach, 2026-10-21). */
    record UndoView(String reason, Instant at) {
    }


    /**
     * @param price           the firm price
     * @param buyerMargin     the margin left to the buyer, set by the confidence
     * @param ladder          how it was reached: what a buyer could pay at each grade, and how likely each is
     * @param expired         true once the offer is past its validity: price it again before posting
     * @param voided          set once we voided the offer: it can't be posted, get a new one
     * @param sold            what each grade recently sold for, or null when showing sold prices is switched off
     * @param lessSureBecause why the confidence is below A ({@link LessSure}), empty at A
     */
    record OfferView(UUID id, BigDecimal price, BigDecimal fmv, String confidence, BigDecimal buyerMargin, boolean graded,
                     boolean subjectToAuthentication, List<RungView> ladder, Instant createdAt, Instant expiresAt, boolean expired,
                     UndoView voided, List<SoldView> sold, List<String> lessSureBecause) {
    }

    /**
     * What a grade of the card recently sold for: the recency-weighted median of its own sold comps (before grading costs),
     * as the offer's valuation used them (UX review finding 11). Grades priced from other grades have none.
     *
     * @param sales      sales in the window
     * @param windowDays the window the sales are from, 90 days or 180 when there were fewer than 2
     */
    record SoldView(String gradingCompany, String grade, BigDecimal price, int sales, Integer windowDays) {
    }

    /**
     * Why an offer's confidence is below A, for the seller in words (UX review finding 11), read from the offer's stored
     * trace: PHOTOS (the photos left the condition less certain), FEW_SALES (few sold comps), OLD_SALES (the price table
     * hasn't been refreshed lately), ESTIMATED_GRADES (a grade the card is likely to be has no sales of its own).
     */
    enum LessSure { PHOTOS, FEW_SALES, OLD_SALES, ESTIMATED_GRADES }

    record Party(String orgName, String email) {
    }

    /**
     * @param labelUrl the printable label; shown to the seller, who ships the card
     * @param shipBy   when the seller must hand it to the carrier, from {@link ListingStateMachine#SHIP_WITHIN} (review finding 17)
     */
    record LabelView(String carrier, String service, String trackingCode, String labelUrl, BigDecimal insuredValue, Instant boughtAt,
                     Instant shipBy) {
    }

    /**
     * What happened once the card arrived.
     *
     * @param type           RECEIVED (as shown), RETURNED (with the reason), or INSPECTED (a grade
     *                       recorded under the per-grade offers that came before)
     * @param pricePaid      only on a RECEIVED from before payment became a step of its own
     * @param inspectedGrade only for INSPECTED
     */
    record OutcomeView(String type, BigDecimal pricePaid, String returnedReason, BigDecimal inspectedGrade, String note,
                       Instant recordedAt) {
    }

    /**
     * @param buyer    who took it, once accepted (shown to the seller)
     * @param label    the prepaid label, once bought; null while it follows. Its printable link goes once it's used
     * @param undone   why we took it down (VOIDED) or cancelled the deal (CANCELLED)
     * @param progress where the deal is, once a buyer took it; null otherwise or once cancelled
     */
    record ListingView(UUID id, UUID offerId, Status status, Instant listedAt, Instant expiresAt, Instant closedAt, Party buyer,
                       LabelView label, OutcomeView outcome, UndoView undone, Progress progress) {
    }

    record SaleView(OfferView offer, ListingView listing) {
    }

    record CardView(String name, String setName, String number, String language, String edition, String variant, String label,
                    Map<String, BigDecimal> distribution, String gradedAs) {

        /** The card without the seller's own label: free text a stranger shouldn't be shown, and it could carry a name or a phone number. */
        CardView forBuyer() {
            return new CardView(name, setName, number, language, edition, variant, null, distribution, gradedAs);
        }
    }

    /**
     * @param side    FRONT, BACK or DETAIL (the close-up)
     * @param url     the card crop, for thumbnails
     * @param fullUrl the photo exactly as taken, without its metadata: what the card must match on arrival
     */
    record PhotoLink(String side, String url, String fullUrl) {
    }

    record MarketItem(UUID listingId, CardView card, OfferView offer, List<PhotoLink> photos, Instant listedAt, Instant expiresAt) {
    }

    /**
     * @param payTo     how the seller gets paid, only ever shown once a buyer has accepted; null once
     *                  nothing is owed (cancelled or returned), or if the seller gave none
     * @param cancelled set once we cancelled the deal: nothing more to record, and the label isn't used
     */
    record DealView(UUID listingId, CardView card, OfferView offer, List<PhotoLink> photos, Party seller, Instant acceptedAt,
                    LabelView label, OutcomeView outcome, UndoView cancelled, PaymentHandle payTo, Progress progress) {
    }

    /** A deal whose label hasn't been bought, for the admin: the label to retry, or the arrival to record by hand. */
    record AwaitingLabel(UUID listingId, UUID buyerOrgId, Party buyer, Party seller, CardView card, OfferView offer,
                         List<PhotoLink> photos, Instant acceptedAt) {
    }

    /** @param labels sold cards past their ship-by date that nobody has said shipped */
    record Overdue(int labels) {
    }

    private final CardOfferVoidRepository voids;
    private final OffersProperties properties;
    private final Directory directory;
    private final Shipping shipping;
    private final BlobStore blobs;
    private final StorageProperties storage;

    ListingViews(CardOfferVoidRepository voids, OffersProperties properties, Directory directory, Shipping shipping, BlobStore blobs,
                 StorageProperties storage) {
        this.voids = voids;
        this.properties = properties;
        this.directory = directory;
        this.shipping = shipping;
        this.blobs = blobs;
        this.storage = storage;
    }

    ListingView listingView(SaleListing l, List<ListingEvent> history) {
        Status status = status(l, history, Instant.now());
        ListingEvent closed = history.stream()
                .filter(e -> e.getType() == ListingEvent.Type.ACCEPTED || e.getType() == ListingEvent.Type.WITHDRAWN
                        || e.getType() == ListingEvent.Type.VOIDED)
                .findFirst().orElse(null);
        Party buyer = null;
        if (closed != null && closed.getType() == ListingEvent.Type.ACCEPTED) {
            Directory.Contact c = directory.contact(closed.getActorOrgId(), closed.getActorUserId());
            buyer = new Party(c.orgName(), c.email());
        }
        UndoView undone = status == Status.CANCELLED ? undone(history, ListingEvent.Type.CANCELLED)
                : status == Status.VOIDED ? undone(history, ListingEvent.Type.VOIDED) : null;
        Progress progress = progress(history);
        // Once it's used, nobody needs the printable label, and printing it again could only send a second parcel.
        // Once we cancelled, it's ours to refund: it isn't offered to the seller to print, nor is the buyer's address on it.
        boolean labelUsed = progress != null && progress.stage() != DealStage.SOLD;
        LabelView label = labelUsed || status == Status.CANCELLED ? withoutPrintable(label(l.getId())) : label(l.getId());
        return new ListingView(l.getId(), l.getOfferId(), status, l.getCreatedAt(), l.getExpiresAt(),
                closed == null ? null : closed.getCreatedAt(), buyer, label, outcome(history), undone, progress);
    }

    static UndoView undone(List<ListingEvent> history, ListingEvent.Type type) {
        return history.stream().filter(e -> e.getType() == type).findFirst()
                .map(e -> new UndoView(e.getNote(), e.getCreatedAt())).orElse(null);
    }

    /**
     * The label as a buyer sees it: carrier and tracking, but not the printable label, which shows the
     * seller's address. Buyers never learn where a seller is, so they can't meet up and deal around us
     * (Zach, 2026-10-21).
     */
    static LabelView withoutPrintable(LabelView label) {
        return label == null ? null
                : new LabelView(label.carrier(), label.service(), label.trackingCode(), null, label.insuredValue(), label.boughtAt(),
                        label.shipBy());
    }

    LabelView label(UUID listingId) {
        return shipping.label(listingId)
                .map(x -> new LabelView(x.getCarrier(), x.getService(), x.getTrackingCode(), x.getLabelUrl(), x.getInsuredValue(),
                        x.getCreatedAt(), x.getCreatedAt().plus(ListingStateMachine.SHIP_WITHIN)))
                .orElse(null);
    }

    static OutcomeView outcome(List<ListingEvent> history) {
        return history.stream()
                .filter(e -> e.getType() == ListingEvent.Type.RECEIVED || e.getType() == ListingEvent.Type.RETURNED
                        || e.getType() == ListingEvent.Type.INSPECTED)
                .findFirst()
                .map(e -> new OutcomeView(e.getType().name(), e.getPricePaid(),
                        e.getDeclinedReason() == null ? null : e.getDeclinedReason().name(), e.getInspectedGrade(), e.getNote(),
                        e.getCreatedAt()))
                .orElse(null);
    }

    /** The offer, with whether we voided it. */
    OfferView offerView(CardOffer o) {
        return offerView(o, voids.findByOfferId(o.getId()).orElse(null));
    }

    OfferView offerView(CardOffer o, CardOfferVoid voided) {
        List<RungView> ladder = OfferService.ladder(o).stream()
                .map(r -> new RungView(r.grade().toString(), r.probability(), r.price())).toList();
        return new OfferView(o.getId(), o.getPrice(), o.getFmv(), o.getConfidence(), o.getBuyerMargin(), o.isGraded(),
                o.isSubjectToAuthentication(), ladder, o.getCreatedAt(), o.getExpiresAt(), !o.getExpiresAt().isAfter(Instant.now()),
                voided == null ? null : new UndoView(voided.getReason(), voided.getCreatedAt()),
                properties.showSoldPrices() ? sold(OfferService.inputs(o)) : null, lessSureBecause(o));
    }

    /** The grades with sold comps of their own, from the prices stored with the offer: never a fresh query. */
    static List<SoldView> sold(CardInputs in) {
        if (in.prices() == null || in.prices().grades() == null) {
            return List.of();
        }
        return in.prices().grades().stream()
                .filter(r -> r.method() == GradePrice.Method.OBSERVED && r.n() > 0 && r.gross() != null)
                .map(r -> new SoldView(in.prices().gradingCompany(), r.grade().toString(), r.gross(), r.n(), r.windowDays()))
                .toList();
    }

    /** CardValuator's confidence_base detail, e.g. "photo B, comps C (weighted sales 3.20)". */
    private static final Pattern BASE_DETAIL = Pattern.compile("photo ([A-D]), comps ([A-D])");

    /** Why the offer's confidence is below A, from the steps its stored trace took to reach it. */
    static List<String> lessSureBecause(CardOffer o) {
        List<String> reasons = new ArrayList<>();
        for (JsonNode step : OfferService.JSON.readTree(o.getTrace())) {
            String subject = step.path("subject").asString("");
            String rule = step.path("rule").asString("");
            if (subject.equals("confidence_base")) {
                Matcher m = BASE_DETAIL.matcher(step.path("detail").asString(""));
                if (m.find()) {
                    if (!m.group(1).equals("A")) {
                        reasons.add(LessSure.PHOTOS.name());
                    }
                    if (!m.group(2).equals("A")) {
                        reasons.add(LessSure.FEW_SALES.name());
                    }
                }
            } else if (subject.equals("offer_confidence_penalty") && rule.startsWith("price table")) {
                reasons.add(LessSure.OLD_SALES.name());
            } else if (subject.equals("offer_confidence") && rule.startsWith("derived price")) {
                reasons.add(LessSure.ESTIMATED_GRADES.name());
            }
        }
        return reasons;
    }

    static CardView card(CardInputs in) {
        Map<String, BigDecimal> distribution = new LinkedHashMap<>();
        if (in.assessment() != null) {
            in.assessment().distribution().forEach((g, p) -> distribution.put(g.toString(), p));
        }
        CardInputs.Identity id = in.identity();
        return new CardView(id.name(), id.setName(), id.number(), id.language(), id.edition(), id.variant(), in.label(), distribution,
                in.graded() == null ? null : in.graded().gradingCompany() + " " + in.graded().grade().stripTrailingZeros().toPlainString());
    }

    /**
     * The photos the offer was priced on: each side's newest, front, back then close-up, never superseded
     * attempts. Never the uploads themselves: their EXIF can say where the seller is.
     */
    List<PhotoLink> photos(CardInputs in) {
        return current(in.photos()).stream()
                .map(p -> new PhotoLink(p.side(), blobs.presignDownload(storage.photoBucket(), CardItems.shownKey(p.storageKey(), p.cropKey()))
                        .toString(), blobs.presignDownload(storage.photoBucket(), CardItems.exactKey(p.storageKey())).toString()))
                .toList();
    }

    /** Each side's newest photo (the list is oldest first), in the order front, back, close-up. */
    static List<CardInputs.Photo> current(List<CardInputs.Photo> oldestFirst) {
        Map<String, CardInputs.Photo> latest = new LinkedHashMap<>();
        for (String side : List.of("FRONT", "BACK", "DETAIL")) {
            oldestFirst.stream().filter(p -> side.equals(p.side())).reduce((a, b) -> b).ifPresent(p -> latest.put(side, p));
        }
        return List.copyOf(latest.values());
    }
}

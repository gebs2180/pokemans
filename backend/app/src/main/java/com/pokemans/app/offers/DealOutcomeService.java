package com.pokemans.app.offers;

import static com.pokemans.app.offers.ListingStateMachine.progress;

import com.pokemans.app.identity.CurrentUser;
import com.pokemans.app.identity.Directory;
import com.pokemans.app.offers.ListingStateMachine.DealStage;
import com.pokemans.app.offers.ListingStateMachine.Progress;
import com.pokemans.app.offers.ListingViews.CardView;
import com.pokemans.app.outcomes.DealOutcomes;
import com.pokemans.app.outcomes.DealOutcomes.Kind;
import com.pokemans.app.outcomes.DealOutcomes.Recorded;
import com.pokemans.app.valuation.CardInputs;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * What happened to a card after its deal: the buyer says it was graded (and what it got), resold raw, or
 * kept. The grade is what the offer's predicted condition is measured against. No access checks: the
 * controllers have done them.
 */
@Service
class DealOutcomeService {

    /**
     * A buyer org's record: of the cards it graded (MISMATCH certs left out), how many came back within one
     * grade of the condition the offer predicted.
     *
     * @param verified graded cards whose cert the admin has checked
     */
    record BuyerRecord(int graded, int withinOne, int verified, int resold, int kept, List<Recorded> outcomes) {
    }

    /** Graded deals compared with their offer's prediction. */
    record Summary(int graded, int withinOne, int belowStressed) {
    }

    /**
     * One deal's outcome against its offer's prediction. The prediction fields are null for a card the offer
     * already priced as graded, which predicted no condition.
     */
    record AdminRow(UUID listingId, UUID offerId, UUID buyerOrgId, String buyer, CardView card, BigDecimal offerPrice, String confidence,
                    Map<String, BigDecimal> predicted, String median, Integer stressShift, String stressedMedian, Recorded outcome,
                    String returnedGrade, Boolean withinOne, Boolean belowStressed) {
    }

    /**
     * @param all      every graded deal with a prediction, except MISMATCH certs
     * @param verified only those whose cert the admin checked
     */
    record AdminCalibration(Summary all, Summary verified, int resold, int kept, List<AdminRow> rows) {
    }

    record OutcomeRequest(Kind kind, DealOutcomes.GradeReport graded, BigDecimal resalePrice, String note) {
    }

    private final DealService deals;
    private final DealOutcomes outcomes;
    private final CardOfferRepository offers;
    private final SaleListingRepository listings;
    private final ListingEventRepository events;
    private final ListingLock lock;
    private final Directory directory;
    private final CurrentUser currentUser;

    DealOutcomeService(DealService deals, DealOutcomes outcomes, CardOfferRepository offers, SaleListingRepository listings,
                       ListingEventRepository events, ListingLock lock, Directory directory, CurrentUser currentUser) {
        this.deals = deals;
        this.outcomes = outcomes;
        this.offers = offers;
        this.listings = listings;
        this.events = events;
        this.lock = lock;
        this.directory = directory;
        this.currentUser = currentUser;
    }

    /** Appends what happened to a card this buyer org took, once it arrived and stayed. */
    @Transactional
    Recorded record(UUID buyerOrgId, UUID listingId, OutcomeRequest request) {
        SaleListing listing = deals.requireDeal(buyerOrgId, listingId);
        lock.lock(listing.getCardItemId());
        Progress progress = progress(events.history(List.of(listing)).getOrDefault(listingId, List.of()));
        if (progress == null) {
            throw conflict("We cancelled this deal, so there's nothing to record on it.");
        }
        if (progress.stage() == DealStage.RETURNED || progress.stage() == DealStage.BACK) {
            throw conflict("This card went back to the seller.");
        }
        if (progress.arrivedAt() == null) {
            throw conflict("Record that the card arrived first.");
        }
        DealOutcomes.Deal deal = new DealOutcomes.Deal(listingId, listing.getOfferId(), listing.getCardItemId(), listing.getOrgId(), buyerOrgId);
        return outcomes.record(deal, request.kind(), request.graded(), request.resalePrice(), request.note(), currentUser.id());
    }

    @Transactional(readOnly = true)
    BuyerRecord buyerRecord(UUID buyerOrgId) {
        List<Recorded> latest = outcomes.latestForBuyer(buyerOrgId);
        Map<UUID, CardOffer> offerById = offersOf(latest);
        int graded = 0;
        int within = 0;
        int verified = 0;
        for (Recorded r : latest) {
            Calibration.Prediction p = prediction(offerById.get(r.offerId()));
            if (r.kind() != Kind.GRADED || "MISMATCH".equals(r.certStatus()) || p == null) {
                continue;
            }
            graded++;
            within += Calibration.compare(p, r.grade()).withinOne() ? 1 : 0;
            verified += "VERIFIED".equals(r.certStatus()) ? 1 : 0;
        }
        return new BuyerRecord(graded, within, verified, count(latest, Kind.RESOLD_RAW), count(latest, Kind.KEPT), latest);
    }

    @Transactional(readOnly = true)
    AdminCalibration calibration() {
        List<Recorded> latest = outcomes.latestAll();
        Map<UUID, CardOffer> offerById = offersOf(latest);
        List<AdminRow> rows = new ArrayList<>();
        int[] all = new int[3];
        int[] checked = new int[3];
        for (Recorded r : latest) {
            CardOffer offer = offerById.get(r.offerId());
            CardInputs in = OfferService.inputs(offer);
            Calibration.Prediction p = prediction(offer);
            Calibration.Comparison c = r.kind() == Kind.GRADED && p != null ? Calibration.compare(p, r.grade()) : null;
            if (c != null && !"MISMATCH".equals(r.certStatus())) {
                tally(all, c);
                if ("VERIFIED".equals(r.certStatus())) {
                    tally(checked, c);
                }
            }
            rows.add(new AdminRow(r.listingId(), r.offerId(), r.buyerOrgId(), directory.orgName(r.buyerOrgId()), ListingViews.card(in),
                    offer.getPrice(), offer.getConfidence(), p == null ? null : p.distribution(), p == null ? null : p.median().toString(),
                    p == null ? null : p.stressShift(), p == null ? null : p.stressedMedian().toString(), r,
                    c == null ? null : c.returned().toString(), c == null ? null : c.withinOne(), c == null ? null : c.belowStressed()));
        }
        return new AdminCalibration(new Summary(all[0], all[1], all[2]), new Summary(checked[0], checked[1], checked[2]),
                count(latest, Kind.RESOLD_RAW), count(latest, Kind.KEPT), rows.reversed());
    }

    /** The offer's prediction, or null for an offer on a card already graded, which predicted no condition. */
    private static Calibration.Prediction prediction(CardOffer offer) {
        CardInputs in = OfferService.inputs(offer);
        if (offer.isGraded() || in.assessment() == null) {
            return null;
        }
        return Calibration.predict(in.assessment().distribution(), offer.getConfidence(), offer.getTrace());
    }

    private Map<UUID, CardOffer> offersOf(List<Recorded> latest) {
        return offers.findAllById(latest.stream().map(Recorded::offerId).distinct().toList()).stream()
                .collect(Collectors.toMap(CardOffer::getId, Function.identity()));
    }

    private static void tally(int[] counts, Calibration.Comparison c) {
        counts[0]++;
        counts[1] += c.withinOne() ? 1 : 0;
        counts[2] += c.belowStressed() ? 1 : 0;
    }

    private static int count(List<Recorded> latest, Kind kind) {
        return (int) latest.stream().filter(r -> r.kind() == kind).count();
    }

    private static ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }
}

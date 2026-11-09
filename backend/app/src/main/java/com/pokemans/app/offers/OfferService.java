package com.pokemans.app.offers;

import com.pokemans.app.identity.CurrentUser;
import com.pokemans.app.pricing.Prices;
import com.pokemans.app.valuation.CardInputs;
import com.pokemans.app.valuation.Valuations;
import com.pokemans.valuation.CardValuation;
import com.pokemans.valuation.Confidence;
import com.pokemans.valuation.Grade;
import com.pokemans.valuation.GradeDistribution;
import com.pokemans.valuation.OfferLadder;
import com.pokemans.valuation.Trace;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Prices instant offers: the card valued as of now, then one firm price, the
 * expected value of what a buyer could pay at each grade ({@link OfferLadder}). Stored with every
 * input, the per-grade ladder behind the price, and the trace. No access checks:
 * the controllers have done them.
 */
@Service
@EnableConfigurationProperties(OffersProperties.class)
class OfferService {

    static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

    /** Why a card gets no offer, in words for the seller. */
    private static final Map<String, String> UNVALUED = Map.of(
            "photographed lot: lots aren't valued", "Lots don't get offers: add the cards one by one.",
            "not identified", "We haven't identified this card yet.",
            "no condition assessment", "Its condition hasn't been assessed yet.",
            "photos retaken since its condition was assessed", "Its photos changed, so we're checking its condition again.");

    /** How far back the raw sanity band looks: the comp window. */
    static final int RAW_SALES_DAYS = 90;

    /** Why a buyer can't take, or a seller post, an offer whose price no longer holds. */
    static final String PRICE_CHANGED = "The price has changed. Get a new offer.";

    private final Valuations valuations;
    private final Prices prices;
    private final CardOfferRepository offers;
    private final OffersProperties properties;
    private final CurrentUser currentUser;

    OfferService(Valuations valuations, Prices prices, CardOfferRepository offers, OffersProperties properties, CurrentUser currentUser) {
        this.valuations = valuations;
        this.prices = prices;
        this.offers = offers;
        this.properties = properties;
        this.currentUser = currentUser;
    }

    /**
     * What an offer on the card would be right now, or why it gets none: one place for both, so the
     * admin's preview and the seller's offer can't disagree.
     *
     * @param refusal why the card gets no offer, in words for the seller, or null
     * @param offer   the firm price and its ladder, or null when refused
     * @param raw     the card's raw near-mint sales of the last 90 days, or null when refused
     */
    record Quote(Valuations.CurrentValue value, String refusal, BigDecimal margin, OfferLadder.Offer offer, CardOffer.RawCheck raw) {

        Quote(Valuations.CurrentValue value, String refusal) {
            this(value, refusal, null, null, null);
        }

        /** The firm price is under what the card recently sold for raw in near mint: a flag, not a refusal. */
        boolean belowRawSales() {
            return offer != null && raw.lowest() != null && offer.price().compareTo(raw.lowest()) < 0;
        }
    }

    /**
     * The admin's preview of a card's offer, stored nowhere.
     *
     * @param fmv        null when the card can't be valued
     * @param confidence the confidence the offer would be priced at, or null
     * @param why        the trace steps that set the offer's confidence (base, price penalties, derived grades) and the parameter set
     * @param rawSales   the card's raw near-mint sales of the last 90 days, or null when it gets no offer
     */
    record Preview(boolean offered, String refusal, BigDecimal price, BigDecimal fmv, String confidence, BigDecimal buyerMargin,
                   List<Trace.Step> why, ListingViews.RawSalesView rawSales) {
    }

    @Transactional(readOnly = true)
    Quote quote(UUID cardItemId) {
        Valuations.CurrentValue value = valuations.current(cardItemId);
        CardInputs in = value.inputs();
        if (in.unvaluedReason() != null) {
            String reason = in.unvaluedReason().startsWith("no ") && in.unvaluedReason().endsWith(" price table")
                    ? "We don't have enough sold comps to price this card yet."
                    : UNVALUED.getOrDefault(in.unvaluedReason(), "This card can't be priced: " + in.unvaluedReason() + ".");
            return new Quote(value, reason);
        }
        if (in.doubted()) {
            return new Quote(value, "Its authenticity is in doubt, so it gets no offer.");
        }
        CardValuation v = value.valuation();
        // The offer's own confidence: the FLV-only penalties (fallback σ, the stress) don't apply.
        if (v.offerConfidence() == Confidence.D) {
            return new Quote(value, "There isn't enough sales data to make a firm offer on this card yet.");
        }
        if (v.fmv().compareTo(value.params().minFmv()) < 0) {
            return new Quote(value, "Its value is under the $" + value.params().minFmv().stripTrailingZeros().toPlainString()
                    + " minimum for an offer.");
        }
        BigDecimal margin = value.params().offerMargin(v.offerConfidence());
        OfferLadder.Offer offer = new OfferLadder(value.params(), margin)
                .offer(value.distribution(), value.prices(), in.prices().salesPerMonth());
        if (offer.price().compareTo(properties.maxPrice()) > 0) {
            return new Quote(value, "Instant offers go up to $" + String.format(Locale.US, "%,d", properties.maxPrice().longValue())
                    + ", the most a shipping label can insure, and this card is worth more than that, so it gets no offer.");
        }
        Prices.RawSales raw = prices.rawNearMint(in.identity().catalogCardId(), RAW_SALES_DAYS);
        return new Quote(value, null, margin, offer, new CardOffer.RawCheck(raw.lowest(), raw.sales(), raw.since()));
    }

    @Transactional(readOnly = true)
    Preview preview(UUID cardItemId) {
        Quote q = quote(cardItemId);
        CardValuation v = q.value().valuation();
        List<Trace.Step> why = q.value().trace().stream()
                .filter(st -> st.subject().equals("parameters") || st.subject().equals("confidence_base") || st.subject().startsWith("offer_confidence"))
                .toList();
        return new Preview(q.refusal() == null, q.refusal(), q.offer() == null ? null : q.offer().price(), v == null ? null : v.fmv(),
                v == null ? null : v.offerConfidence().name(), q.margin(), why,
                q.raw() == null ? null : new ListingViews.RawSalesView(q.raw().lowest(), q.raw().sales(), q.raw().since(), q.belowRawSales()));
    }

    /** A new offer on the card, or 409 with the reason it gets none. */
    @Transactional
    CardOffer price(UUID orgId, UUID cardItemId) {
        Quote q = quote(cardItemId);
        if (q.refusal() != null) {
            throw refused(q.refusal());
        }
        Valuations.CurrentValue value = q.value();
        CardInputs in = value.inputs();
        CardValuation v = value.valuation();
        BigDecimal margin = q.margin();
        OfferLadder.Offer offer = q.offer();
        List<Rung> ladder = offer.rungs().stream().map(r -> new Rung(r.grade(), r.probability(), r.price())).toList();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String trace = JSON.writeValueAsString(java.util.stream.Stream.concat(value.trace().stream(), offer.trace().steps().stream()).toList());
        return offers.save(new CardOffer(orgId, cardItemId, in.identity().catalogCardId(),
                in.assessment() == null ? null : in.assessment().assessmentId(), in.prices().snapshotSetId(), in.graded() != null,
                JSON.writeValueAsString(in), value.params().version(), margin, JSON.writeValueAsString(ladder),
                offer.price(), v.fmv(), v.offerConfidence().name(), in.provenance().authFlagUnresolved(), trace, currentUser.id(), now,
                now.plus(properties.offerValidFor()), q.raw()));
    }

    /**
     * The offer's price re-run on today's prices: its own ladder (the distribution and buyer margin it was
     * made with) against the card's current price table and sales rate, under the current parameter set. Only
     * the market moves here; a changed condition or photos are checked elsewhere.
     *
     * @param now     the re-run price, or null when the card can't be priced today (no table, or no longer the
     *                same kind of card: graded since, or no longer identified)
     * @param moved   true when the price moved more than {@code pokemans.offers.reprice-threshold} either
     *                way, or can't be re-run
     */
    record Reprice(BigDecimal was, BigDecimal now, boolean moved) {
    }

    Reprice reprice(CardOffer offer) {
        Valuations.CurrentValue value = valuations.current(offer.getCardItemId());
        CardInputs in = value.inputs();
        BigDecimal was = offer.getPrice();
        if (value.prices() == null || in.prices() == null || in.identity() == null
                || !offer.getCatalogCardId().equals(in.identity().catalogCardId()) || (in.graded() != null) != offer.isGraded()) {
            return new Reprice(was, null, true);
        }
        Map<Grade, BigDecimal> p = new EnumMap<>(Grade.class);
        ladder(offer).forEach(r -> p.put(r.grade(), r.probability()));
        BigDecimal now = new OfferLadder(value.params(), offer.getBuyerMargin())
                .offer(GradeDistribution.of(p), value.prices(), in.prices().salesPerMonth()).price();
        return new Reprice(was, now, moved(was, now, properties.repriceThreshold()));
    }

    /** More than {@code threshold} of the old price away from it, either way; exactly at the threshold still holds. */
    static boolean moved(BigDecimal was, BigDecimal now, BigDecimal threshold) {
        if (was.signum() == 0) {
            return now.signum() != 0;
        }
        return now.subtract(was).abs().divide(was, 6, RoundingMode.HALF_EVEN).compareTo(threshold) > 0;
    }

    /** The card as identified when the offer was made, without valuing it again; null for no such offer. */
    @Transactional(readOnly = true)
    CardInputs.Identity identity(UUID offerId) {
        return offers.findById(offerId).map(o -> inputs(o).identity()).orElse(null);
    }

    @Transactional(readOnly = true)
    Optional<CardOffer> latest(UUID cardItemId) {
        return offers.findFirstByCardItemIdOrderByCreatedAtDesc(cardItemId);
    }

    /** One rung of a stored ladder. */
    record Rung(Grade grade, BigDecimal probability, BigDecimal price) {
    }

    static List<Rung> ladder(CardOffer offer) {
        return JSON.readValue(offer.getLadder(), new TypeReference<List<Rung>>() { });
    }

    static CardInputs inputs(CardOffer offer) {
        return JSON.readValue(offer.getInputs(), CardInputs.class);
    }

    private static ResponseStatusException refused(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }
}

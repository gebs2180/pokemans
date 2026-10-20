package com.pokemans.app.offers;

import com.pokemans.app.identity.CurrentUser;
import com.pokemans.app.valuation.CardInputs;
import com.pokemans.app.valuation.Valuations;
import com.pokemans.valuation.CardValuation;
import com.pokemans.valuation.Confidence;
import com.pokemans.valuation.Grade;
import com.pokemans.valuation.OfferLadder;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
 * Prices instant offers (Zach, 2026-10-19): the card valued as of now, then a price for each grade it
 * could inspect at ({@link OfferLadder}). Stored with every input and the trace. No access checks:
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
            "no condition assessment", "Its condition hasn't been assessed yet.");

    private final Valuations valuations;
    private final CardOfferRepository offers;
    private final OffersProperties properties;
    private final CurrentUser currentUser;

    OfferService(Valuations valuations, CardOfferRepository offers, OffersProperties properties, CurrentUser currentUser) {
        this.valuations = valuations;
        this.offers = offers;
        this.properties = properties;
        this.currentUser = currentUser;
    }

    /** A new offer on the card, or 409 with the reason it gets none. */
    @Transactional
    CardOffer price(UUID orgId, UUID cardItemId) {
        Valuations.CurrentValue value = valuations.current(cardItemId);
        CardInputs in = value.inputs();
        if (in.unvaluedReason() != null) {
            String reason = in.unvaluedReason().startsWith("no ") && in.unvaluedReason().endsWith(" price table")
                    ? "We don't have enough sold comps to price this card yet."
                    : UNVALUED.getOrDefault(in.unvaluedReason(), "This card can't be priced: " + in.unvaluedReason() + ".");
            throw refused(reason);
        }
        if (in.doubted()) {
            throw refused("Its authenticity is in doubt, so it gets no offer.");
        }
        CardValuation v = value.valuation();
        if (v.confidence() == Confidence.D) {
            throw refused("There isn't enough sales data to make a firm offer on this card yet.");
        }
        if (v.fmv().compareTo(value.params().minFmv()) < 0) {
            throw refused("Its value is under the $" + value.params().minFmv().stripTrailingZeros().toPlainString()
                    + " minimum for an offer.");
        }
        OfferLadder.Offer offer = new OfferLadder(value.params(), properties.buyerMargin())
                .offer(value.distribution(), value.prices(), in.prices().salesPerMonth());
        List<Rung> ladder = offer.rungs().stream().map(r -> new Rung(r.grade(), r.probability(), r.price())).toList();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String trace = JSON.writeValueAsString(java.util.stream.Stream.concat(value.trace().stream(), offer.trace().steps().stream()).toList());
        return offers.save(new CardOffer(orgId, cardItemId, in.identity().catalogCardId(),
                in.assessment() == null ? null : in.assessment().assessmentId(), in.prices().snapshotSetId(), in.graded() != null,
                JSON.writeValueAsString(in), value.params().version(), properties.buyerMargin(), JSON.writeValueAsString(ladder),
                offer.expected(), v.fmv(), v.confidence().name(), in.provenance().authFlagUnresolved(), trace, currentUser.id(), now,
                now.plus(properties.offerValidFor())));
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

    /** The ladder price for a grade found on inspection; half grades fold down. */
    static BigDecimal priceAt(List<Rung> ladder, BigDecimal grade) {
        Grade g = Grade.of(grade.setScale(0, java.math.RoundingMode.FLOOR).intValueExact());
        return ladder.stream().filter(r -> r.grade() == g).map(Rung::price).findFirst().orElse(BigDecimal.ZERO);
    }

    private static ResponseStatusException refused(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }
}

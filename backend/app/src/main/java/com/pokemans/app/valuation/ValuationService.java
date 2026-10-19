package com.pokemans.app.valuation;

import com.pokemans.app.catalog.Catalog;
import com.pokemans.app.condition.Assessments;
import com.pokemans.app.identity.CurrentUser;
import com.pokemans.app.intake.CardItems;
import com.pokemans.app.outcomes.Outcomes;
import com.pokemans.app.pricing.Prices;
import com.pokemans.app.provenance.Provenance;
import com.pokemans.valuation.Grade;
import com.pokemans.valuation.GradingCost;
import com.pokemans.valuation.ParameterSet;
import com.pokemans.valuation.Trace;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Valuation runs: snapshot every input of a collection's cards, value them with valuation-core, and
 * store the inputs, results and trace together. No access checks: the admin controller is the caller.
 */
@Service
@EnableConfigurationProperties(ValuationProperties.class)
class ValuationService {

    /** Canonical JSON: sorted map keys, so equal inputs always hash the same. */
    static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

    static final String LOT = "photographed lot: lots aren't valued";
    static final String NOT_IDENTIFIED = "not identified";
    static final String NO_ASSESSMENT = "no condition assessment";

    private final CardItems cardItems;
    private final Catalog catalog;
    private final Assessments assessments;
    private final Prices prices;
    private final Provenance provenance;
    private final Outcomes outcomes;
    private final ValuationRunRepository runs;
    private final CardValuationRecordRepository lines;
    private final ValuationProperties properties;
    private final CurrentUser currentUser;

    ValuationService(CardItems cardItems, Catalog catalog, Assessments assessments, Prices prices, Provenance provenance,
                     Outcomes outcomes, ValuationRunRepository runs, CardValuationRecordRepository lines,
                     ValuationProperties properties, CurrentUser currentUser) {
        this.cardItems = cardItems;
        this.catalog = catalog;
        this.assessments = assessments;
        this.prices = prices;
        this.provenance = provenance;
        this.outcomes = outcomes;
        this.runs = runs;
        this.lines = lines;
        this.properties = properties;
        this.currentUser = currentUser;
    }

    ParameterSet parameterSet() {
        return ParameterSet.load(properties.parameterSet());
    }

    GradingCost defaultGradingCost() {
        return properties.gradingCost().toCost();
    }

    String gradingCompany() {
        return properties.gradingCompany();
    }

    @Transactional
    ValuationRun run(UUID orgId, UUID collectionId, GradingCost cost, String note) {
        CardItems.CollectionRef collection = cardItems.requireCollection(orgId, collectionId);
        ParameterSet params = parameterSet();
        List<CardInputs> inputs = gather(collection.id());

        ValuationEngine.Result result = new ValuationEngine(params, cost).value(inputs);

        String paramsJson = JSON.writeValueAsString(params);
        String costJson = JSON.writeValueAsString(cost);
        List<String> inputJson = inputs.stream().map(JSON::writeValueAsString).toList();
        var p = result.portfolio();
        ValuationRun run = runs.save(new ValuationRun(orgId, collection.id(), collection.name(), LocalDate.now(ZoneOffset.UTC),
                params.version(), paramsJson, properties.gradingCompany(), costJson, sha256(paramsJson, costJson, inputJson),
                inputs.size(), (int) result.cards().stream().filter(c -> c.valuation() != null).count(), result.eligibleCount(),
                p.eligibleFlv(), p.collateralBase(), share(p.rawShare()), share(p.gradedShare()), params.suggestedAlphaLow(),
                params.suggestedAlphaHigh(), p.suggestedAdvanceLow(), p.suggestedAdvanceHigh(), result.capsApplied(),
                result.totalRv(), JSON.writeValueAsString(p.trace().steps()), note, currentUser.id()));
        for (int i = 0; i < result.cards().size(); i++) {
            ValuationEngine.CardResult card = result.cards().get(i);
            lines.save(new CardValuationRecord(run.getId(), orgId, i, card, inputJson.get(i), JSON.writeValueAsString(card.trace())));
        }
        return run;
    }

    /** Re-values a stored run from its stored inputs alone and lists every number that came out different. */
    @Transactional(readOnly = true)
    List<String> replay(ValuationRun run) {
        ParameterSet params = JSON.readValue(run.getParameterSet(), ParameterSet.class);
        GradingCost cost = JSON.readValue(run.getGradingCost(), GradingCost.class);
        List<CardValuationRecord> stored = lines.findByRunIdOrderByPositionAsc(run.getId());
        List<CardInputs> inputs = stored.stream().map(l -> JSON.readValue(l.getInputs(), CardInputs.class)).toList();
        List<String> inputJson = inputs.stream().map(JSON::writeValueAsString).toList();
        List<String> differences = new ArrayList<>();
        String hash = sha256(JSON.writeValueAsString(params), JSON.writeValueAsString(cost), inputJson);
        if (!hash.equals(run.getInputsSha256())) {
            differences.add("inputs hash " + hash + " vs stored " + run.getInputsSha256());
        }
        ValuationEngine.Result result = new ValuationEngine(params, cost).value(inputs);
        var p = result.portfolio();
        compare(differences, "collateral base", p.collateralBase(), run.getCollateralBase());
        compare(differences, "eligible FLV", p.eligibleFlv(), run.getEligibleFlv());
        compare(differences, "total RV", result.totalRv(), run.getTotalRv());
        compare(differences, "advance low", p.suggestedAdvanceLow(), run.getAdvanceLow());
        compare(differences, "advance high", p.suggestedAdvanceHigh(), run.getAdvanceHigh());
        compare(differences, "raw share", share(p.rawShare()), run.getRawShare());
        for (int i = 0; i < stored.size(); i++) {
            CardValuationRecord line = stored.get(i);
            ValuationEngine.CardResult card = result.cards().get(i);
            String who = "card " + line.getCardItemId() + " ";
            if (card.valuation() == null) {
                if (line.getStatus() != CardValuationRecord.Status.UNVALUED) {
                    differences.add(who + "was valued, now unvalued");
                }
                continue;
            }
            compare(differences, who + "FMV", card.valuation().fmv(), line.getFmv());
            compare(differences, who + "RV", card.valuation().rv(), line.getRv());
            compare(differences, who + "QSV", card.valuation().qsv(), line.getQsv());
            compare(differences, who + "FLV", card.valuation().flv(), line.getFlv());
            compare(differences, who + "contribution", card.contribution().contribution(), line.getContribution());
            compare(differences, who + "insured RV", card.insuredRv(), line.getInsuredRv());
            if (!card.valuation().confidence().name().equals(line.getConfidence())) {
                differences.add(who + "confidence " + card.valuation().confidence() + " vs stored " + line.getConfidence());
            }
            if (card.gradeFirst() != null) {
                compare(differences, who + "lift", card.gradeFirst().lift(), line.getLift());
            }
        }
        return differences;
    }

    /** Every card in the collection with what its valuation needs, or why it can't be valued. */
    private List<CardInputs> gather(UUID collectionId) {
        List<CardItems.CardItemRef> items = cardItems.inCollection(collectionId);
        List<UUID> ids = items.stream().map(CardItems.CardItemRef::id).toList();
        Map<UUID, Catalog.IdentifiedCard> identities = catalog.current(ids);
        Map<UUID, Assessments.Current> current = assessments.current(ids);
        Map<UUID, Provenance.Status> status = provenance.status(ids);
        Map<UUID, Outcomes.VerifiedGrade> verified = outcomes.verifiedGrades(ids);

        record Draft(CardItems.CardItemRef item, CardInputs.Identity identity, CardInputs.Assessment assessment,
                     CardInputs.Graded graded, CardInputs.Prices prices, CardInputs.ProvenanceState provenance,
                     List<CardInputs.Photo> photos, String setKey, String characterKey, String reason) {
        }
        List<Draft> drafts = new ArrayList<>();
        for (CardItems.CardItemRef item : items) {
            Catalog.IdentifiedCard id = identities.get(item.id());
            Provenance.Status s = status.get(item.id());
            CardInputs.ProvenanceState prov = new CardInputs.ProvenanceState(s.authFlagUnresolved(), s.authState().name(),
                    s.custodyVerified(), s.custodyState().name(), s.custodyVerifiedUntil());
            List<CardInputs.Photo> photos = cardItems.passedPhotos(item.id()).stream()
                    .map(ph -> new CardInputs.Photo(ph.id(), ph.side(), ph.storageKey(), ph.cropKey(), ph.contentType(), ph.capturedAt(),
                            ph.uploadedAt(), ph.sha256()))
                    .toList();
            CardInputs.Identity identity = id == null ? null : new CardInputs.Identity(id.catalogCardId(), id.name(), id.setName(),
                    id.number(), id.language(), id.setReleaseDate(), id.edition().name(), id.variant().name(), id.promo(),
                    id.authenticityRisk().name());
            Assessments.Current a = current.get(item.id());
            CardInputs.Assessment assessment = a == null ? null
                    : new CardInputs.Assessment(a.assessmentId(), a.version(), a.distribution().asMap(), a.confidence());
            Outcomes.VerifiedGrade g = verified.get(item.id());
            CardInputs.Graded graded = g == null ? null : new CardInputs.Graded(g.outcomeId(), g.gradingCompany(), g.grade(),
                    Grade.of(g.grade().setScale(0, RoundingMode.FLOOR).intValueExact()), g.certNumber());

            String reason = null;
            CardInputs.Prices table = null;
            if (item.lot()) {
                reason = LOT;
            } else if (identity == null) {
                reason = NOT_IDENTIFIED;
            } else if (graded == null && assessment == null) {
                reason = NO_ASSESSMENT;
            } else {
                String company = graded == null ? properties.gradingCompany() : graded.gradingCompany();
                Optional<Prices.CardPrices> found = prices.latest(identity.catalogCardId(), company);
                if (found.isEmpty()) {
                    reason = "no " + company + " price table";
                } else {
                    table = prices(found.get());
                }
            }
            String setKey = identity == null ? null : CorrelationKeys.set(identity.language(), identity.setName());
            String characterKey = identity == null ? null : CorrelationKeys.character(identity.name());
            drafts.add(new Draft(item, identity, assessment, graded, table, prov, photos, setKey, characterKey, reason));
        }

        // Copies of the same card in the same state sell into the same market: the depth discount counts them together.
        Map<String, Long> copies = drafts.stream().filter(d -> d.reason() == null)
                .collect(Collectors.groupingBy(d -> copyKey(d.identity(), d.graded()), Collectors.counting()));
        return drafts.stream().map(d -> new CardInputs(d.item().id(), d.item().label(), d.item().lot(), d.item().quantity(),
                d.identity(), d.assessment(), d.graded(), d.prices(),
                d.reason() == null ? copies.get(copyKey(d.identity(), d.graded())).intValue() : 0,
                d.setKey(), d.characterKey(), d.provenance(), d.photos(), d.reason())).toList();
    }

    private static String copyKey(CardInputs.Identity identity, CardInputs.Graded graded) {
        return identity.catalogCardId() + (graded == null ? ":raw" : ":" + graded.gradingCompany() + graded.valuedGrade());
    }

    private static CardInputs.Prices prices(Prices.CardPrices p) {
        List<CardInputs.PriceRow> rows = p.grades().values().stream()
                .map(g -> new CardInputs.PriceRow(g.grade(), g.price(), g.method(), g.n(),
                        g.compIds().stream().map(UUID::toString).toList(), g.windowDays(), g.basis(), g.stepDownRatio(), g.confidence()))
                .toList();
        return new CardInputs.Prices(p.snapshotSetId(), p.gradingCompany(), p.asOf(), p.daysOld(), p.staleLevels(), rows, p.sigma(),
                p.sigmaChanges(), p.salesPerMonth());
    }

    static List<Trace.Step> steps(String json) {
        return JSON.readValue(json, new TypeReference<List<Trace.Step>>() { });
    }

    private static BigDecimal share(BigDecimal share) {
        return share.setScale(6, RoundingMode.HALF_EVEN);
    }

    private static void compare(List<String> differences, String what, BigDecimal now, BigDecimal stored) {
        boolean same = now == null ? stored == null : stored != null && now.compareTo(stored) == 0;
        if (!same) {
            differences.add(what + " " + now + " vs stored " + stored);
        }
    }

    static String sha256(String params, String cost, List<String> cards) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(params.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(cost.getBytes(StandardCharsets.UTF_8));
            for (String card : cards) {
                digest.update((byte) '\n');
                digest.update(card.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

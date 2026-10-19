package com.pokemans.app.valuation;

import com.pokemans.valuation.Confidence;
import com.pokemans.valuation.Grade;
import com.pokemans.valuation.GradePrice;
import com.pokemans.valuation.GradingCost;
import com.pokemans.valuation.ParameterSet;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * A fixed collection, valued by the real engine without a database: the golden report's input.
 * Thirteen items: ten eligible raw cards across two sets, a verified PSA 9, a counterfeit, a card
 * awaiting verification, one with lapsed custody, a stale table, a missing sigma, an unidentified card
 * and a lot.
 */
public final class ValuationFixtures {

    public static final LocalDate AS_OF = LocalDate.of(2026, 10, 18);
    public static final Instant CREATED = Instant.parse("2026-10-18T15:00:00Z");
    public static final GradingCost COST = new GradingCost(new BigDecimal("25"), new BigDecimal("5"), new BigDecimal("2"),
            new BigDecimal("0.02"));

    private static int next;

    private ValuationFixtures() {
    }

    /** The fixed run, as {@link Valuations#detail} would read it back. */
    public static Valuations.RunDetail detail() {
        next = 0;
        List<CardInputs> cards = new ArrayList<>();
        cards.add(raw("Charizard", "Base Set", "4", "1999-01-09", "VINTAGE_HOLO", "HOLO", "1000", 12, dist(5, "0.3", 6, "0.4", 7, "0.3"),
                verified(), custody(), "0.08", 22));
        cards.add(raw("Blastoise", "Base Set", "2", "1999-01-09", "VINTAGE_HOLO", "HOLO", "600", 12, dist(7, "0.4", 8, "0.4", 9, "0.2"),
                verified(), custody(), "0.07", 12));
        cards.add(raw("Venusaur", "Base Set", "15", "1999-01-09", "VINTAGE_HOLO", "HOLO", "500", 12, dist(6, "0.6", 7, "0.4"),
                awaiting(), custody(), "0.07", 9));
        for (String[] modern : new String[][] {
                {"Umbreon VMAX", "215", "1400"}, {"Rayquaza VMAX", "218", "700"}, {"Espeon VMAX", "270", "560"},
                {"Glaceon VMAX", "209", "520"}, {"Leafeon VMAX", "205", "480"}, {"Sylveon VMAX", "212", "460"},
                {"Gengar VMAX", "271", "440"}, {"Charizard VMAX", "20", "500"}}) {
            cards.add(raw(modern[0], "Evolving Skies", modern[1], "2021-08-27", "NONE", "HOLO", modern[2], 25,
                    dist(8, "0.2", 9, "0.5", 10, "0.3"), notFlagged(), custody(), "0.05", 30));
        }
        cards.add(raw("Mew ex", "Pokemon 151", "151", "2023-09-22", "NONE", "HOLO", "180", 3, dist(9, "0.6", 10, "0.4"),
                notFlagged(), lapsed(), null, 6));
        cards.add(graded("Lugia", "Neo Genesis", "9", "2000-12-16", "1200", custody()));
        cards.add(raw("Mewtwo", "Base Set", "10", "1999-01-09", "VINTAGE_HOLO", "HOLO", "300", 12, dist(5, "0.5", 6, "0.5"),
                doubted(), custody(), "0.07", 8));
        cards.add(unvalued("Binder page, mixed commons", false, ValuationService.NOT_IDENTIFIED));
        cards.add(unvalued("Bulk lot", true, ValuationService.LOT));
        return detail(cards);
    }

    static Valuations.RunDetail detail(List<CardInputs> cards) {
        ParameterSet params = ParameterSet.load("1.1.0");
        ValuationEngine.Result result = new ValuationEngine(params, COST).value(cards);
        List<Valuations.CardLine> lines = new ArrayList<>();
        Map<String, Long> mix = new TreeMap<>();
        for (int i = 0; i < result.cards().size(); i++) {
            ValuationEngine.CardResult c = result.cards().get(i);
            var v = c.valuation();
            var gf = c.gradeFirst();
            if (v != null) {
                mix.merge(v.confidence().name(), 1L, Long::sum);
            }
            lines.add(new Valuations.CardLine(c.inputs().cardItemId(), i, v == null ? "UNVALUED" : "VALUED", c.inputs().unvaluedReason(),
                    c.inputs().graded() != null, c.inputs(), v == null ? null : v.fmv(), v == null ? null : v.rv(),
                    v == null ? null : v.qsv(), v == null ? null : v.stressedFmv(), v == null ? null : v.flv(),
                    v == null ? null : v.confidence().name(), v != null && c.contribution().eligible(),
                    v == null ? List.of(c.inputs().unvaluedReason()) : c.contribution().exclusionReasons(),
                    v == null ? BigDecimal.ZERO : c.contribution().contribution(), c.insuredRv(), c.insurerNote(),
                    gf == null ? null : gf.flvGraded(), gf == null ? null : gf.flvRaw(), gf == null ? null : gf.cost(),
                    gf == null ? null : gf.lift(), c.gradeFirstRank(), c.trace()));
        }
        var p = result.portfolio();
        Valuations.RunSummary run = new Valuations.RunSummary(uuid(9000), uuid(9001), uuid(9002), "Zach's binder", AS_OF,
                params.version(), "PSA", COST, "fixture", cards.size(), (int) lines.stream().filter(l -> l.fmv() != null).count(),
                result.eligibleCount(), p.eligibleFlv(), p.collateralBase(), p.rawShare().setScale(6, RoundingMode.HALF_EVEN),
                p.gradedShare().setScale(6, RoundingMode.HALF_EVEN), params.suggestedAlphaLow(), params.suggestedAlphaHigh(),
                p.suggestedAdvanceLow(), p.suggestedAdvanceHigh(), result.capsApplied(), result.totalRv(), null,
                CREATED);
        return new Valuations.RunDetail(run, lines, p.trace().steps(), mix);
    }

    private static CardInputs raw(String name, String set, String number, String released, String risk, String variant, String top,
                                  int n, Map<Grade, BigDecimal> distribution, CardInputs.ProvenanceState provenance,
                                  CardInputs.ProvenanceState custody, String sigma, int salesPerMonth) {
        UUID id = uuid(++next);
        CardInputs.ProvenanceState state = new CardInputs.ProvenanceState(provenance.authFlagUnresolved(), provenance.authState(),
                custody.custodyVerified(), custody.custodyState(), custody.custodyVerifiedUntil());
        CardInputs.Identity identity = identity(id, name, set, number, released, risk, variant);
        boolean stale = "Mew ex".equals(name);
        return new CardInputs(id, name + " #" + next, false, 1, identity,
                new CardInputs.Assessment(uuid(500 + next), 2, distribution, Confidence.A), null,
                prices(id, top, n, sigma, salesPerMonth, stale ? 40 : 1, stale ? 2 : 0), 1,
                CorrelationKeys.set("EN", set), CorrelationKeys.character(name), state, photos(id), null);
    }

    private static CardInputs graded(String name, String set, String number, String released, String top,
                                     CardInputs.ProvenanceState custody) {
        UUID id = uuid(++next);
        CardInputs.ProvenanceState state = new CardInputs.ProvenanceState(false, "VERIFIED_AUTHENTIC", custody.custodyVerified(),
                custody.custodyState(), custody.custodyVerifiedUntil());
        return new CardInputs(id, name + " slab", false, 1, identity(id, name, set, number, released, "VINTAGE_HOLO", "HOLO"), null,
                new CardInputs.Graded(uuid(700), "PSA", new BigDecimal("9"), Grade.G9, "81234567"),
                prices(id, top, 15, "0.06", 8, 1, 0), 1, CorrelationKeys.set("EN", set), CorrelationKeys.character(name), state,
                photos(id), null);
    }

    private static CardInputs unvalued(String label, boolean lot, String reason) {
        UUID id = uuid(++next);
        return new CardInputs(id, label, lot, lot ? 40 : 1, null, null, null, null, 0, null, null,
                new CardInputs.ProvenanceState(true, "NOT_IDENTIFIED", false, "NOT_RECORDED", null), photos(id), reason);
    }

    private static CardInputs.Identity identity(UUID id, String name, String set, String number, String released, String risk,
                                                String variant) {
        return new CardInputs.Identity(uuid(300 + next), name, set, number, "EN", LocalDate.parse(released),
                "UNLIMITED", variant, false, risk);
    }

    /** A PSA ladder: 10 at {@code top}, each grade 0.7 of the one above; 7 to 10 observed, below that stepped down. */
    private static CardInputs.Prices prices(UUID card, String top, int n, String sigma, int salesPerMonth, long daysOld, int stale) {
        List<CardInputs.PriceRow> rows = new ArrayList<>();
        rows.add(new CardInputs.PriceRow(Grade.U, BigDecimal.ZERO, GradePrice.Method.NO_SALES, 0, List.of(), null, List.of(), null,
                Confidence.D));
        BigDecimal price = new BigDecimal(top);
        EnumMap<Grade, BigDecimal> ladder = new EnumMap<>(Grade.class);
        for (int g = 10; g >= 1; g--) {
            ladder.put(Grade.of(g), price.setScale(2, RoundingMode.HALF_EVEN));
            price = price.multiply(new BigDecimal("0.7"));
        }
        for (int g = 1; g <= 10; g++) {
            Grade grade = Grade.of(g);
            boolean observed = g >= 7;
            List<String> comps = new ArrayList<>();
            for (int i = 0; observed && i < n; i++) {
                comps.add("comp-" + g + "-" + i);
            }
            rows.add(new CardInputs.PriceRow(grade, ladder.get(grade), observed ? GradePrice.Method.OBSERVED : GradePrice.Method.STEP_DOWN,
                    observed ? n : 0, comps, observed ? 90 : null, observed ? List.of() : List.of(Grade.of(g + 1)),
                    observed ? null : new BigDecimal("0.7"), observed ? Confidence.A : Confidence.B));
        }
        return new CardInputs.Prices(uuid(800 + next), "PSA", AS_OF.minusDays(daysOld), daysOld, stale, rows,
                sigma == null ? null : new BigDecimal(sigma), sigma == null ? 1 : 11, new BigDecimal(salesPerMonth));
    }

    private static Map<Grade, BigDecimal> dist(Object... gradeThenP) {
        EnumMap<Grade, BigDecimal> d = new EnumMap<>(Grade.class);
        for (Grade g : Grade.values()) {
            d.put(g, BigDecimal.ZERO);
        }
        for (int i = 0; i < gradeThenP.length; i += 2) {
            d.put(Grade.of((Integer) gradeThenP[i]), new BigDecimal((String) gradeThenP[i + 1]));
        }
        return d;
    }

    private static List<CardInputs.Photo> photos(UUID card) {
        return List.of(
                new CardInputs.Photo(uuid(1000 + next), "FRONT", "photos/" + card + "/front.jpg", null, "image/jpeg",
                        Instant.parse("2026-10-12T18:04:00Z"), Instant.parse("2026-10-12T18:05:00Z"), "a".repeat(64)),
                new CardInputs.Photo(uuid(2000 + next), "BACK", "photos/" + card + "/back.jpg", null, "image/jpeg",
                        Instant.parse("2026-10-12T18:06:00Z"), Instant.parse("2026-10-12T18:07:00Z"), "b".repeat(64)));
    }

    private static CardInputs.ProvenanceState verified() {
        return new CardInputs.ProvenanceState(false, "VERIFIED_AUTHENTIC", false, null, null);
    }

    private static CardInputs.ProvenanceState notFlagged() {
        return new CardInputs.ProvenanceState(false, "NOT_FLAGGED", false, null, null);
    }

    private static CardInputs.ProvenanceState awaiting() {
        return new CardInputs.ProvenanceState(true, "AWAITING_VERIFICATION", false, null, null);
    }

    private static CardInputs.ProvenanceState doubted() {
        return new CardInputs.ProvenanceState(true, "COUNTERFEIT", false, null, null);
    }

    private static CardInputs.ProvenanceState custody() {
        return new CardInputs.ProvenanceState(false, null, true, "VERIFIED", Instant.parse("2027-01-10T00:00:00Z"));
    }

    private static CardInputs.ProvenanceState lapsed() {
        return new CardInputs.ProvenanceState(false, null, false, "LAPSED", null);
    }

    static UUID uuid(int n) {
        return UUID.fromString(String.format("00000000-0000-4000-8000-%012d", Math.abs(n)));
    }
}

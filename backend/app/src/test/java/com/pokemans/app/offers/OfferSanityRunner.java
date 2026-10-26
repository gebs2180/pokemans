package com.pokemans.app.offers;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pokemans.app.TestApi;
import com.pokemans.app.TestcontainersConfiguration;
import com.pokemans.app.identity.SecurityProperties;
import com.pokemans.app.valuation.Valuations;
import com.pokemans.valuation.Confidence;
import com.pokemans.valuation.Grade;
import com.pokemans.valuation.GradePrice;
import com.pokemans.valuation.GradePriceTable;
import com.pokemans.valuation.OfferLadder;
import com.pokemans.valuation.Trace;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Not a test: a report for the vendor spot check (docs/offer-sanity-2026-10-26.md). Every card in a
 * comps CSV (the columns of comps.csv) goes in through the admin comp API, the price tables come from a
 * refresh, and each card is then assessed at a sweep of condition distributions and photo confidences
 * and quoted by {@link OfferService}, exactly as the admin preview would. Prints a Markdown table and
 * writes it to {@code build/reports/offer-sanity.md}; asserts nothing.
 *
 * <p>Runs only when {@code POKEMANS_OFFER_SANITY_CSV} names the CSV, and on its own (the refresh prices
 * every card in the database, so other test classes' comps would change the era ratios):
 * {@code POKEMANS_OFFER_SANITY_CSV=... ./gradlew :app:test --tests '*OfferSanityRunner'}.
 */
@SpringBootTest(properties = "pokemans.condition.worker.enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(named = "POKEMANS_OFFER_SANITY_CSV", matches = ".+")
class OfferSanityRunner {

    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    /** Condition sweep: centred on 10 down to 6, one grade apart, then a wide one. */
    private static final Map<String, Map<String, Double>> DISTRIBUTIONS = new LinkedHashMap<>();

    static {
        DISTRIBUTIONS.put("c10", Map.of("9", 0.3, "10", 0.7));
        DISTRIBUTIONS.put("c9", Map.of("8", 0.2, "9", 0.6, "10", 0.2));
        DISTRIBUTIONS.put("c8", Map.of("7", 0.2, "8", 0.6, "9", 0.2));
        DISTRIBUTIONS.put("c7", Map.of("6", 0.2, "7", 0.6, "8", 0.2));
        DISTRIBUTIONS.put("c6", Map.of("5", 0.2, "6", 0.6, "7", 0.2));
        DISTRIBUTIONS.put("wide5-10", Map.of("5", 0.1, "6", 0.15, "7", 0.2, "8", 0.25, "9", 0.2, "10", 0.1));
    }

    /** Identity per comps.csv card: set, release date, variant and rarity as the catalog would have them. */
    private record Card(String name, String set, String number, String released, String variant, String rarity) {
    }

    private static final List<Card> CARDS = List.of(
            new Card("Charizard", "Base Set (Unlimited)", "4/102", "1999-01-09", "HOLO", "Rare Holo"),
            new Card("Umbreon VMAX", "Evolving Skies", "215/203", "2021-08-27", "HOLO", "Rare Secret"),
            new Card("Pikachu VMAX", "Vivid Voltage", "188/185", "2020-11-13", "HOLO", "Rare Rainbow"),
            new Card("Giratina V", "Lost Origin", "186/196", "2022-09-09", "HOLO", "Rare Ultra"),
            new Card("Lugia V", "Silver Tempest", "186/195", "2022-11-11", "HOLO", "Rare Ultra"));

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityProperties security;

    @Autowired
    private OfferService offers;

    @Test
    void report() throws Exception {
        List<String[]> csv = csv(Path.of(System.getenv("POKEMANS_OFFER_SANITY_CSV")));
        TestApi api = new TestApi(mvc, security);
        String orgId = api.collectorOrg("Offer sanity " + UUID.randomUUID(), "auth0|sanity-" + UUID.randomUUID());

        Map<Card, String> items = new LinkedHashMap<>();
        Map<Card, String> catalogIds = new LinkedHashMap<>();
        for (Card c : CARDS) {
            String item = api.card(orgId, c.name(), api.admin());
            String catalog = TestApi.read(api.postJson("/api/admin/orgs/" + orgId + "/cards/" + item + "/identifications",
                    "{\"provider\":\"MANUAL\",\"language\":\"en\",\"edition\":\"UNLIMITED\",\"variant\":\"" + c.variant()
                            + "\",\"setName\":\"" + c.set() + "\",\"setReleaseDate\":\"" + c.released() + "\",\"number\":\""
                            + c.number() + "\",\"name\":\"" + c.name() + "\",\"rarity\":\"" + c.rarity() + "\"}",
                    api.admin()).andExpect(status().isCreated()), "$.current.card.id");
            items.put(c, item);
            catalogIds.put(c, catalog);
        }

        // The comps, as the CSV import in the comp form would post them: graded sold rows for the card's number.
        Map<Card, BigDecimal> lowestPsa9 = new LinkedHashMap<>();
        for (String[] r : csv) {
            Card c = CARDS.stream().filter(x -> x.number().equals(r[2]) && x.name().equals(r[0])).findFirst().orElseThrow();
            String url = r[9].startsWith("http") ? ",\"url\":\"" + r[9] + "\"" : "";
            api.postJson("/api/admin/catalog-cards/" + catalogIds.get(c) + "/comps", "{\"gradingCompany\":\"" + r[3] + "\",\"grade\":" + r[4]
                    + ",\"price\":" + r[5] + ",\"soldOn\":\"" + r[6] + "\",\"listingType\":\"" + r[7] + "\",\"marketplace\":\"" + r[8]
                    + "\",\"note\":\"Imported from CSV\"" + url + "}", api.admin()).andExpect(status().isCreated());
            if (r[3].equals("PSA") && r[4].equals("9")) {
                lowestPsa9.merge(c, new BigDecimal(r[5]), BigDecimal::min);
            }
        }
        api.postJson("/api/admin/catalog-cards/" + catalogIds.get(CARDS.getFirst()) + "/prices/refresh", "", api.admin())
                .andExpect(status().isOk());

        StringBuilder out = new StringBuilder();
        out.append("Generated ").append(TODAY).append(" from ").append(csv.size()).append(" comps.\n\n");
        List<String> tables = new ArrayList<>();
        out.append("| Card | Dist | Photo conf | Offer conf | Firm price | Refusal | FMV | Engine price before refusal | Price at m(A/B/C) | Derived share | ")
                .append("Lowest PSA 9 | Ladder 10..5 (method) |\n");
        out.append("|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Card c : CARDS) {
            boolean tablePrinted = false;
            for (Map.Entry<String, Map<String, Double>> d : DISTRIBUTIONS.entrySet()) {
                for (Confidence photo : List.of(Confidence.A, Confidence.B, Confidence.C)) {
                    api.postJson("/api/admin/orgs/" + orgId + "/cards/" + items.get(c) + "/assessments",
                            "{\"distribution\":" + json(d.getValue()) + ",\"confidence\":\"" + photo + "\",\"reason\":\"offer sanity sweep\"}",
                            api.admin()).andExpect(status().isCreated());
                    OfferService.Quote q = offers.quote(UUID.fromString(items.get(c)));
                    Valuations.CurrentValue v = q.value();
                    if (!tablePrinted && v.prices() != null) {
                        tables.add(priceTable(c, v));
                        tablePrinted = true;
                    }
                    out.append(row(c, d.getKey(), photo, q, lowestPsa9.get(c))).append('\n');
                }
            }
        }
        out.append("\n## Price tables (gross, net of the default grading cost, method)\n\n");
        tables.forEach(t -> out.append(t).append('\n'));

        System.out.println(out);
        Path report = Path.of("build", "reports", "offer-sanity.md");
        Files.createDirectories(report.getParent());
        Files.writeString(report, out.toString());
    }

    private String row(Card c, String dist, Confidence photo, OfferService.Quote q, BigDecimal lowestPsa9) {
        Valuations.CurrentValue v = q.value();
        if (v.valuation() == null) {
            return "| " + c.name() + " | " + dist + " | " + photo + " | | | " + q.refusal() + " | | | | | | " + v.inputs().unvaluedReason() + " |";
        }
        // Why the offer is at its confidence: the base (photo and comps) and the derived-grade step, from the trace.
        String offerConf = v.valuation().offerConfidence().name() + " ("
                + v.trace().stream().filter(st -> st.subject().equals("confidence_base")).map(Trace.Step::detail).findFirst().orElse("")
                + v.trace().stream().filter(st -> st.subject().equals("offer_confidence") && st.rule().startsWith("derived"))
                        .map(st -> "; " + st.detail().split(";")[0]).findFirst().orElse("") + ")";
        // What the ladder gives at each margin, whatever the refusal: the same OfferLadder, margin by confidence.
        List<String> byMargin = new ArrayList<>();
        OfferLadder.Offer atOwn = null;
        for (Confidence m : List.of(Confidence.A, Confidence.B, Confidence.C)) {
            OfferLadder.Offer o = new OfferLadder(v.params(), v.params().offerMargin(m))
                    .offer(v.distribution(), v.prices(), v.inputs().prices().salesPerMonth());
            byMargin.add(o.price().toPlainString());
            // A D card gets no offer: show what its ladder would be at C's margin, the nearest it could get.
            Confidence own = v.valuation().offerConfidence() == Confidence.D ? Confidence.C : v.valuation().offerConfidence();
            if (m == own) {
                atOwn = o;
            }
        }
        String uncapped = atOwn == null ? "" : atOwn.price().toPlainString();
        String derivedShare = atOwn == null ? "" : derivedShare(atOwn, v.prices());
        StringBuilder ladder = new StringBuilder();
        if (atOwn != null) {
            for (OfferLadder.Rung r : atOwn.rungs()) {
                if (r.grade() == Grade.U || r.grade().number() < 5) {
                    continue;
                }
                ladder.append(r.grade()).append(":").append(r.price().setScale(0, RoundingMode.HALF_EVEN))
                        .append("(").append(abbrev(v.prices().get(r.grade()).method())).append(") ");
            }
        }
        return "| " + c.name() + " | " + dist + " | " + photo + " | " + offerConf + " | "
                + (q.offer() == null ? "—" : q.offer().price().toPlainString()) + " | " + (q.refusal() == null ? "" : q.refusal()) + " | "
                + v.valuation().fmv().toPlainString() + " | " + uncapped + " | " + String.join(" / ", byMargin) + " | " + derivedShare + " | "
                + (lowestPsa9 == null ? "" : lowestPsa9.toPlainString()) + " | " + ladder.toString().trim() + " |";
    }

    /** Share of the firm price carried by grades whose price was derived, not observed. */
    private static String derivedShare(OfferLadder.Offer o, GradePriceTable prices) {
        if (o.price().signum() == 0) {
            return "";
        }
        BigDecimal derived = BigDecimal.ZERO;
        for (OfferLadder.Rung r : o.rungs()) {
            if (prices.get(r.grade()).derived()) {
                derived = derived.add(r.probability().multiply(r.price()));
            }
        }
        return derived.multiply(BigDecimal.valueOf(100)).divide(o.price(), 0, RoundingMode.HALF_EVEN) + "%";
    }

    private static String priceTable(Card c, Valuations.CurrentValue v) {
        StringBuilder t = new StringBuilder("**" + c.name() + " " + c.number() + "** (" + v.inputs().prices().salesPerMonth()
                + " sales/mo, sigma " + v.inputs().prices().sigma() + ")\n\n| Grade | Gross | Net | Method | n | Basis | Ratio |\n|---|---|---|---|---|---|---|\n");
        for (int g = 10; g >= 0; g--) {
            GradePrice p = v.prices().get(Grade.of(g));
            t.append("| ").append(p.grade()).append(" | ").append(p.gross().setScale(2, RoundingMode.HALF_EVEN)).append(" | ")
                    .append(p.net().setScale(2, RoundingMode.HALF_EVEN)).append(" | ").append(p.method()).append(" | ")
                    .append(p.salesInWindow()).append(" | ").append(p.basis()).append(" | ")
                    .append(p.stepDownRatio() == null ? "" : p.stepDownRatio().setScale(3, RoundingMode.HALF_EVEN)).append(" |\n");
        }
        return t.toString();
    }

    private static String abbrev(GradePrice.Method m) {
        return switch (m) {
            case OBSERVED -> "obs";
            case INTERPOLATED -> "interp";
            case STEP_DOWN -> "era-step";
            case CARD_STEP_DOWN -> "card-step";
            case CARRIED_DOWN -> "carried";
            case NO_SALES -> "none";
        };
    }

    private static String json(Map<String, Double> d) {
        StringBuilder s = new StringBuilder("{");
        d.forEach((g, p) -> s.append(s.length() > 1 ? "," : "").append('"').append(g).append("\":").append(p));
        return s.append('}').toString();
    }

    /** The CSV's data rows; comps.csv has no quoted fields. */
    private static List<String[]> csv(Path path) throws IOException {
        return Files.readAllLines(path).stream().skip(1).filter(l -> !l.isBlank()).map(l -> l.split(",", -1)).toList();
    }
}

package com.pokemans.app.reporting;

import com.pokemans.app.valuation.CardInputs;
import com.pokemans.app.valuation.Valuations;
import com.pokemans.valuation.Grade;
import com.pokemans.valuation.GradeDistribution;
import com.pokemans.valuation.GradingCost;
import com.pokemans.valuation.Trace;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a stored valuation run into the text the report templates print: every figure formatted,
 * every reason spelled out. Pure, so a fixed run always gives the same report. Templates get plain
 * maps (Thymeleaf's expression language reads map keys, not record accessors).
 */
final class ReportModel {

    /** Bumped whenever a template or this model changes what a report says. */
    static final String TEMPLATE_VERSION = "1";

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);
    private static final Pattern CARD_ID = Pattern.compile("\\[([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})]");

    private ReportModel() {
    }

    /** What both reports share: the run, the collection and how it was valued. */
    private static Map<String, Object> header(Valuations.RunDetail detail, UUID reportId, Instant issuedAt, String title) {
        Valuations.RunSummary run = detail.run();
        GradingCost cost = run.gradingCost();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", title);
        m.put("collection", run.collectionName());
        m.put("reportId", reportId.toString());
        m.put("runId", run.id().toString());
        m.put("asOf", run.asOf().toString());
        m.put("issuedAt", TIMESTAMP.format(issuedAt));
        m.put("parameterSet", run.parameterSetVersion());
        m.put("gradingCompany", run.gradingCompany());
        m.put("gradingCost", "fee " + money(cost.fee()) + ", shipping " + money(cost.shipping()) + ", insurance "
                + money(cost.insurance()) + " per card, time-to-cash " + percent(cost.timeToCashRate()) + " of the graded price");
        m.put("inputsSha256", run.inputsSha256());
        m.put("templateVersion", TEMPLATE_VERSION);
        m.put("cardCount", run.cardCount());
        return m;
    }

    static Map<String, Object> lender(Valuations.RunDetail detail, UUID reportId, Instant issuedAt, BigDecimal lenderAlpha) {
        Valuations.RunSummary run = detail.run();
        Map<String, Object> m = header(detail, reportId, issuedAt, "Collateral report");
        Map<UUID, String> names = names(detail);
        m.put("collateralBase", money(run.collateralBase()));
        m.put("eligibleFlv", money(run.eligibleFlv()));
        m.put("eligibleCount", run.eligibleCount());
        m.put("valuedCount", run.valuedCount());
        m.put("alphaRange", percent(run.alphaLow()) + " to " + percent(run.alphaHigh()));
        m.put("advanceRange", money(run.advanceLow()) + " to " + money(run.advanceHigh()));
        m.put("lenderAlpha", lenderAlpha == null ? null : percent(lenderAlpha));
        m.put("lenderAdvance", lenderAlpha == null ? null
                : money(run.collateralBase().multiply(lenderAlpha).setScale(2, RoundingMode.HALF_EVEN)));
        m.put("rawShare", percent(run.rawShare()));
        m.put("gradedShare", percent(run.gradedShare()));
        m.put("confidenceMix", String.join(", ", detail.confidenceMix().entrySet().stream()
                .map(e -> e.getKey() + ": " + e.getValue()).toList()));
        m.put("capsApplied", run.capsApplied());
        m.put("capsNote", "Every card is capped at one level so that no card is more than 20% of the eligible FLV before caps ("
                + money(run.eligibleFlv()) + ") and the top five are at most 50% of it, whatever the number of cards."
                + (run.capsApplied() ? "" : " No card needed capping."));
        m.put("adjustments", adjustments(detail.portfolioTrace(), names));

        List<Map<String, Object>> cards = new ArrayList<>();
        List<Map<String, Object>> exclusions = new ArrayList<>();
        for (Valuations.CardLine c : detail.cards()) {
            Map<String, Object> row = card(c);
            if (c.fmv() != null) {
                row.put("fmv", money(c.fmv()));
                row.put("qsv", money(c.qsv()));
                row.put("flv", money(c.flv()));
                row.put("contribution", money(c.contribution()));
                row.put("confidence", c.confidence());
                cards.add(row);
            }
            if (!c.eligible()) {
                Map<String, Object> ex = new LinkedHashMap<>();
                ex.put("name", row.get("name"));
                ex.put("reasons", String.join("; ", reasons(c)));
                exclusions.add(ex);
            }
        }
        m.put("cards", cards);
        m.put("exclusions", exclusions);

        List<Map<String, Object>> gradeFirst = new ArrayList<>();
        for (Valuations.CardLine c : detail.gradeFirst()) {
            Map<String, Object> row = card(c);
            row.put("rank", c.gradeFirstRank());
            row.put("expectedGrade", GradeDistribution.of(c.inputs().assessment().distribution()).median().toString());
            row.put("cost", money(c.gradingCost()));
            row.put("flvRaw", money(c.flvRawForLift()));
            row.put("flvGraded", money(c.flvGraded()));
            row.put("lift", c.lift().setScale(2, RoundingMode.HALF_EVEN).toPlainString());
            gradeFirst.add(row);
        }
        m.put("gradeFirst", gradeFirst);
        m.put("traceability", traceability(detail, run.gradingCost()));
        return m;
    }

    static Map<String, Object> insurer(Valuations.RunDetail detail, UUID reportId, Instant issuedAt,
                                       Function<CardInputs.Photo, String> photoDataUri) {
        Map<String, Object> m = header(detail, reportId, issuedAt, "Replacement value report");
        m.put("totalRv", money(detail.run().totalRv()));
        List<Map<String, Object>> cards = new ArrayList<>();
        List<Map<String, Object>> notCovered = new ArrayList<>();
        for (Valuations.CardLine c : detail.cards()) {
            Map<String, Object> row = card(c);
            if (c.fmv() == null || c.insuredRv().signum() == 0) {
                row.put("reason", c.insurerNote() == null ? "no value" : c.insurerNote());
                notCovered.add(row);
                continue;
            }
            row.put("rv", money(c.insuredRv()));
            row.put("basis", c.graded() ? "graded" : "raw, condition distribution median "
                    + GradeDistribution.of(c.inputs().assessment().distribution()).median());
            row.put("note", c.insurerNote());
            List<Map<String, Object>> photos = new ArrayList<>();
            for (CardInputs.Photo p : c.inputs().photos()) {
                if ("DETAIL".equals(p.side())) {
                    continue;
                }
                Map<String, Object> ph = new LinkedHashMap<>();
                ph.put("side", p.side().charAt(0) + p.side().substring(1).toLowerCase(Locale.ROOT));
                ph.put("src", photoDataUri.apply(p));
                ph.put("captured", p.capturedAt() == null ? "not recorded" : TIMESTAMP.format(p.capturedAt()));
                ph.put("uploaded", p.uploadedAt() == null ? "not recorded" : TIMESTAMP.format(p.uploadedAt()));
                ph.put("sha256", p.sha256() == null ? "" : p.sha256());
                photos.add(ph);
            }
            row.put("photos", photos);
            cards.add(row);
        }
        m.put("cards", cards);
        m.put("notCovered", notCovered);
        return m;
    }

    private static Map<String, Object> card(Valuations.CardLine c) {
        Map<String, Object> row = new LinkedHashMap<>();
        CardInputs in = c.inputs();
        row.put("name", in.identity() == null ? in.label() : in.identity().name());
        row.put("label", in.label());
        row.put("identity", in.identity() == null ? "not identified" : identity(in.identity()));
        row.put("condition", in.graded() != null ? in.graded().gradingCompany() + " " + in.graded().grade().stripTrailingZeros().toPlainString()
                + " (cert " + in.graded().certNumber() + ")" : in.assessment() == null ? "-"
                : "raw, median " + GradeDistribution.of(in.assessment().distribution()).median());
        row.put("status", c.fmv() == null ? "not valued" : c.eligible() ? "eligible" : "excluded");
        return row;
    }

    private static String identity(CardInputs.Identity id) {
        StringBuilder sb = new StringBuilder(id.setName()).append(" #").append(id.number()).append(", ").append(id.language());
        if (!"UNLIMITED".equals(id.edition())) {
            sb.append(", ").append(id.edition().replace('_', ' ').toLowerCase(Locale.ROOT));
        }
        if (!"NORMAL".equals(id.variant())) {
            sb.append(", ").append(id.variant().replace('_', ' ').toLowerCase(Locale.ROOT));
        }
        if (id.promo()) {
            sb.append(", promo");
        }
        return sb.toString();
    }

    /** The portfolio evaluator's reasons, with the provenance state behind each gate. */
    static List<String> reasons(Valuations.CardLine c) {
        List<String> out = new ArrayList<>();
        CardInputs.ProvenanceState p = c.inputs().provenance();
        for (String r : c.exclusionReasons()) {
            out.add(switch (r) {
                case "unresolved authenticity flag" -> r + " (" + label(p.authState()) + ")";
                case "custody not verified" -> r + " (" + label(p.custodyState()) + ")";
                default -> r;
            });
        }
        return out;
    }

    /** Caps and haircuts from the portfolio trace, with card ids replaced by names. */
    private static List<String> adjustments(List<Trace.Step> steps, Map<UUID, String> names) {
        List<String> out = new ArrayList<>();
        for (Trace.Step s : steps) {
            if (s.subject().startsWith("capped[")) {
                out.add("Capped " + name(s.subject(), names) + " at " + money(new BigDecimal(s.value())) + " (FLV "
                        + money(new BigDecimal(s.detail().replace("FLV ", ""))) + ")");
            } else if (s.subject().startsWith("correlation[")) {
                String slice = s.subject().substring("correlation[".length(), s.subject().length() - 1);
                out.add("Correlation haircut on " + slice.replaceFirst(":", " ") + ": " + s.rule() + ", " + s.detail() + ", "
                        + money(new BigDecimal(s.value())) + " off");
            }
        }
        return out;
    }

    private static List<Map<String, Object>> traceability(Valuations.RunDetail detail, GradingCost cost) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Valuations.CardLine c : detail.cards()) {
            CardInputs in = c.inputs();
            if (c.fmv() == null) {
                continue;
            }
            GradeDistribution dist = in.graded() != null ? GradeDistribution.certain(in.graded().valuedGrade())
                    : GradeDistribution.of(in.assessment().distribution());
            int lowest = 10;
            for (Grade g : Grade.values()) {
                if (!g.isUngradable() && dist.p(g).signum() > 0) {
                    lowest = Math.min(lowest, g.number());
                }
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (CardInputs.PriceRow r : in.prices().grades()) {
                boolean inPlay = dist.p(r.grade()).signum() > 0 || (!r.grade().isUngradable() && r.grade().number() < lowest
                        && r.grade().number() >= lowest - 3 && in.graded() == null);
                if (!inPlay) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("grade", r.grade().toString());
                row.put("p", dist.p(r.grade()).stripTrailingZeros().toPlainString());
                row.put("gross", money(r.gross()));
                row.put("net", money(in.graded() != null || r.grade().isUngradable() ? r.gross() : cost.netPrice(r.gross())));
                row.put("method", r.method().name());
                row.put("sales", r.n());
                row.put("window", r.windowDays() == null ? "-" : r.windowDays() + "d");
                rows.add(row);
            }
            Map<String, Object> card = card(c);
            card.put("table", in.prices().gradingCompany() + " table as of " + in.prices().asOf() + " ("
                    + in.prices().daysOld() + " days old), snapshot " + in.prices().snapshotSetId());
            card.put("market", "sigma " + (in.prices().sigma() == null ? "not measured (fallback used)"
                    : percent(in.prices().sigma())) + ", " + in.prices().salesPerMonth().stripTrailingZeros().toPlainString()
                    + " sales/month, " + in.copiesHeld() + (in.copiesHeld() == 1 ? " copy" : " copies") + " held");
            card.put("rows", rows);
            out.add(card);
        }
        return out;
    }

    private static Map<UUID, String> names(Valuations.RunDetail detail) {
        Map<UUID, String> names = new LinkedHashMap<>();
        for (Valuations.CardLine c : detail.cards()) {
            names.put(c.cardItemId(), c.inputs().identity() == null ? c.inputs().label()
                    : c.inputs().identity().name() + " (" + c.inputs().label() + ")");
        }
        return names;
    }

    private static String name(String subject, Map<UUID, String> names) {
        Matcher m = CARD_ID.matcher(subject);
        return m.find() ? names.getOrDefault(UUID.fromString(m.group(1)), m.group(1)) : subject;
    }

    private static String label(String state) {
        return state.replace('_', ' ').toLowerCase(Locale.ROOT);
    }

    static String money(BigDecimal amount) {
        NumberFormat f = NumberFormat.getCurrencyInstance(Locale.US);
        return f.format(amount.setScale(2, RoundingMode.HALF_EVEN));
    }

    static String percent(BigDecimal share) {
        return share.movePointRight(2).setScale(1, RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString() + "%";
    }
}

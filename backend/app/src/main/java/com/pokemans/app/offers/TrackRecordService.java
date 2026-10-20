package com.pokemans.app.offers;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The instant-offer track record: what happened to the offers we posted, read from the same views
 * the analysis workspace uses (V15), so the admin page and the notebooks can't disagree. Every rate
 * comes with the counts behind it, and the caveats travel with the numbers.
 */
@Service
class TrackRecordService {

    /** The methodology's condition targets (CLAUDE.md, validation targets). */
    static final BigDecimal WITHIN_ONE_TARGET = new BigDecimal("0.80");
    static final BigDecimal BELOW_STRESSED_TARGET = new BigDecimal("0.05");

    /** Bands of the expected offer, in dollars: [min, max). */
    private static final List<Band> BANDS = List.of(
            new Band("Under $50", null, new BigDecimal("50")),
            new Band("$50 to $100", new BigDecimal("50"), new BigDecimal("100")),
            new Band("$100 to $250", new BigDecimal("100"), new BigDecimal("250")),
            new Band("$250 to $500", new BigDecimal("250"), new BigDecimal("500")),
            new Band("$500 and up", new BigDecimal("500"), null));

    static final List<String> CAVEATS = List.of(
            "Sellers choose which offers to post. They post the ones they like, so posted offers lean towards cards we priced generously, and the acceptance rate is about those, not all offers.",
            "Inspected grades and prices paid are self-reported by the buyer, who grades the card they are paying for and so has a reason to grade low. They are kept as their own source (BUYER_INSPECTION), never pooled with graders' results.",
            "A buyer who accepts but never records an inspection drops out of the grade and price numbers.",
            "A card posted more than once counts once per listing. Withdrawn and expired listings both count as not accepted; open listings aren't counted yet.",
            "Grades are compared unfolded, so a 7.5 against a predicted 9 is a miss. Cards declined as not genuine or not the listed card are left out of the grade numbers; cards declined as not matching their photos stay in.");

    record Band(String label, BigDecimal min, BigDecimal max) {
        boolean contains(BigDecimal value) {
            return (min == null || value.compareTo(min) >= 0) && (max == null || value.compareTo(max) < 0);
        }
    }

    /**
     * @param acceptanceRate accepted / (accepted + withdrawn + expired); null with none closed
     * @param medianHoursToAccept null with none accepted
     */
    record Acceptance(String band, int listings, int open, int accepted, int withdrawn, int expired, int closed,
                      BigDecimal acceptanceRate, BigDecimal medianHoursToAccept) {
    }

    /**
     * @param inspected      inspections compared (raw cards only)
     * @param excluded       inspections left out: graded cards, and cards declined as not genuine or not the listed card
     * @param meanDifference inspected minus predicted median, on average; negative = found worse than predicted
     */
    record GradeCheck(String source, int inspected, int excluded, int withinOne, BigDecimal withinOneShare, BigDecimal withinOneTarget,
                      int belowStressed, BigDecimal belowStressedShare, BigDecimal belowStressedTarget, BigDecimal meanDifference) {
    }

    /**
     * @param compared  inspections with a price paid and a ladder price above zero at the grade found
     * @param declined  inspections where the buyer declined, so nothing was paid
     * @param medianRatio price paid / ladder price, median
     */
    record PriceCheck(String source, int compared, int declined, int belowLadder, int atLadder, int aboveLadder, BigDecimal medianRatio,
                      BigDecimal totalPaid, BigDecimal totalLadder) {
    }

    record TrackRecord(Instant asOf, Acceptance overall, List<Acceptance> byPriceBand, GradeCheck grades, PriceCheck prices,
                       List<String> caveats) {
    }

    private record Row(String status, BigDecimal expectedOffer, BigDecimal hoursToAccept, boolean graded, String source,
                       BigDecimal inspectedGrade, String declinedReason, BigDecimal pricePaid, BigDecimal ladderPrice) {
    }

    private record Calibration(BigDecimal difference, boolean withinOne, boolean belowStressed) {
    }

    private final JdbcTemplate jdbc;

    TrackRecordService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    TrackRecord trackRecord() {
        List<Row> rows = jdbc.query("""
                select status, expected_offer, hours_to_accept, graded, inspection_source, inspected_grade, declined_reason,
                       price_paid, ladder_price_at_inspected
                from v_offer_outcomes""",
                (rs, i) -> new Row(rs.getString(1), rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getBoolean(4), rs.getString(5),
                        rs.getBigDecimal(6), rs.getString(7), rs.getBigDecimal(8), rs.getBigDecimal(9)));
        List<Calibration> calibration = jdbc.query(
                "select grade_difference, within_one, below_stressed from v_offer_grade_calibration where source = 'BUYER_INSPECTION'",
                (rs, i) -> new Calibration(rs.getBigDecimal(1), rs.getBoolean(2), rs.getBoolean(3)));

        List<Acceptance> bands = new ArrayList<>();
        for (Band band : BANDS) {
            bands.add(acceptance(band.label(), rows, r -> band.contains(r.expectedOffer())));
        }
        return new TrackRecord(Instant.now(), acceptance("All", rows, r -> true), bands, grades(rows, calibration), prices(rows), CAVEATS);
    }

    private static Acceptance acceptance(String label, List<Row> all, Predicate<Row> in) {
        List<Row> rows = all.stream().filter(in).toList();
        int open = count(rows, "OPEN");
        int accepted = count(rows, "ACCEPTED");
        int withdrawn = count(rows, "WITHDRAWN");
        int expired = count(rows, "EXPIRED");
        int closed = accepted + withdrawn + expired;
        List<BigDecimal> hours = rows.stream().map(Row::hoursToAccept).filter(h -> h != null).toList();
        BigDecimal medianHours = median(hours);
        return new Acceptance(label, rows.size(), open, accepted, withdrawn, expired, closed, share(accepted, closed),
                medianHours == null ? null : medianHours.setScale(1, RoundingMode.HALF_UP));
    }

    private static GradeCheck grades(List<Row> rows, List<Calibration> compared) {
        long inspections = rows.stream().filter(r -> "BUYER_INSPECTION".equals(r.source())).count();
        int n = compared.size();
        int withinOne = (int) compared.stream().filter(Calibration::withinOne).count();
        int below = (int) compared.stream().filter(Calibration::belowStressed).count();
        BigDecimal mean = n == 0 ? null : compared.stream().map(Calibration::difference).reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP);
        return new GradeCheck("BUYER_INSPECTION", n, (int) inspections - n, withinOne, share(withinOne, n), WITHIN_ONE_TARGET, below,
                share(below, n), BELOW_STRESSED_TARGET, mean);
    }

    private static PriceCheck prices(List<Row> rows) {
        List<Row> inspected = rows.stream().filter(r -> "BUYER_INSPECTION".equals(r.source())).toList();
        int declined = (int) inspected.stream().filter(r -> r.declinedReason() != null).count();
        List<Row> paid = inspected.stream()
                .filter(r -> r.pricePaid() != null && r.ladderPrice() != null && r.ladderPrice().signum() > 0).toList();
        int below = 0;
        int at = 0;
        int above = 0;
        BigDecimal totalPaid = BigDecimal.ZERO;
        BigDecimal totalLadder = BigDecimal.ZERO;
        List<BigDecimal> ratios = new ArrayList<>();
        for (Row r : paid) {
            int c = r.pricePaid().compareTo(r.ladderPrice());
            if (c < 0) {
                below++;
            } else if (c == 0) {
                at++;
            } else {
                above++;
            }
            totalPaid = totalPaid.add(r.pricePaid());
            totalLadder = totalLadder.add(r.ladderPrice());
            ratios.add(r.pricePaid().divide(r.ladderPrice(), 6, RoundingMode.HALF_UP));
        }
        BigDecimal medianRatio = median(ratios);
        return new PriceCheck("BUYER_INSPECTION", paid.size(), declined, below, at, above,
                medianRatio == null ? null : medianRatio.setScale(4, RoundingMode.HALF_UP), totalPaid, totalLadder);
    }

    private static int count(List<Row> rows, String status) {
        return (int) rows.stream().filter(r -> r.status().equals(status)).count();
    }

    /** count / n to four places, or null when there is nothing to divide by. */
    static BigDecimal share(int count, int n) {
        return n == 0 ? null : BigDecimal.valueOf(count).divide(BigDecimal.valueOf(n), 4, RoundingMode.HALF_UP);
    }

    static BigDecimal median(List<BigDecimal> values) {
        if (values.isEmpty()) {
            return null;
        }
        List<BigDecimal> sorted = values.stream().sorted().toList();
        int mid = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(mid)
                : sorted.get(mid - 1).add(sorted.get(mid)).divide(BigDecimal.TWO, 6, RoundingMode.HALF_UP);
    }
}

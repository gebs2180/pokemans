package com.pokemans.app.offers;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The instant-offer track record: what happened to the firm offers we posted, read from the same view
 * the analysis workspace uses ({@code v_offer_outcomes}, V16), so the admin page and the notebooks
 * can't disagree. Every rate comes with the counts behind it, and the caveats travel with the numbers.
 */
@Service
class TrackRecordService {

    /** Bands of the offer price, in dollars: [min, max). */
    private static final List<Band> BANDS = List.of(
            new Band("Under $50", null, new BigDecimal("50")),
            new Band("$50 to $100", new BigDecimal("50"), new BigDecimal("100")),
            new Band("$100 to $250", new BigDecimal("100"), new BigDecimal("250")),
            new Band("$250 to $500", new BigDecimal("250"), new BigDecimal("500")),
            new Band("$500 and up", new BigDecimal("500"), null));

    static final List<String> CAVEATS = List.of(
            "Sellers choose which offers to post. They post the ones they like, so posted offers lean towards cards we priced generously, and the acceptance rate is about those, not all offers.",
            "Arrivals and prices paid are recorded by the buyer. A buyer who never records an arrival drops out of the delivery and price numbers until they do.",
            "A card posted more than once counts once per listing. Withdrawn and expired listings both count as not accepted; open listings aren't counted yet. Listings we voided and deals we cancelled are left out of every rate.",
            "Offers are firm, so no grade is judged on arrival: grade accuracy has to come from graders' results for sold cards, which aren't collected yet.");

    record Band(String label, BigDecimal min, BigDecimal max) {
        boolean contains(BigDecimal value) {
            return (min == null || value.compareTo(min) >= 0) && (max == null || value.compareTo(max) < 0);
        }
    }

    /**
     * @param acceptanceRate accepted / (accepted + withdrawn + expired); null with none closed
     * @param medianHoursToAccept null with none accepted
     */
    /** @param voided listings we took down, and cancelled the deals we undid: left out of the rate */
    record Acceptance(String band, int listings, int open, int accepted, int withdrawn, int expired, int closed,
                      BigDecimal acceptanceRate, BigDecimal medianHoursToAccept, int voided, int cancelled) {
    }

    /**
     * What happened to the cards buyers took.
     *
     * @param inTransit      taken, with no arrival or return recorded yet
     * @param returnRate     returned / (received + returned); null with none recorded
     * @param returnedBy     returns per reason
     * @param earlierModel   taken under the per-grade offers that came before firm prices; left out of the rest
     * @param medianHoursToArrival label bought to arrival or return recorded
     */
    record Deliveries(int taken, int labels, int inTransit, int received, int returned, BigDecimal returnRate, Map<String, Integer> returnedBy,
                      int earlierModel, BigDecimal medianHoursToArrival) {
    }

    /**
     * Price paid against the firm price, for cards that arrived as shown.
     *
     * @param medianRatio price paid / offer price, median
     */
    record PriceCheck(int compared, int belowOffer, int atOffer, int aboveOffer, BigDecimal medianRatio, BigDecimal totalPaid,
                      BigDecimal totalOffered) {
    }

    /** Postage and insurance bought for taken cards, billed to buyers. */
    record ShippingCost(int labels, BigDecimal postage, BigDecimal insurance, BigDecimal meanPerLabel) {
    }

    record TrackRecord(Instant asOf, Acceptance overall, List<Acceptance> byPriceBand, Deliveries deliveries, PriceCheck prices,
                       ShippingCost shipping, List<String> caveats) {
    }

    private record Row(String status, BigDecimal price, BigDecimal hoursToAccept, boolean labelled, BigDecimal postage,
                       BigDecimal insurance, String outcome, BigDecimal pricePaid, String returnedReason, BigDecimal hoursToOutcome) {
    }

    private final JdbcTemplate jdbc;

    TrackRecordService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    TrackRecord trackRecord() {
        List<Row> rows = jdbc.query("""
                select status, price, hours_to_accept, label_at is not null, postage, insurance_cost, outcome, price_paid,
                       returned_reason, hours_label_to_outcome
                from v_offer_outcomes""",
                (rs, i) -> new Row(rs.getString(1), rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getBoolean(4), rs.getBigDecimal(5),
                        rs.getBigDecimal(6), rs.getString(7), rs.getBigDecimal(8), rs.getString(9), rs.getBigDecimal(10)));
        List<Acceptance> bands = new ArrayList<>();
        for (Band band : BANDS) {
            bands.add(acceptance(band.label(), rows, r -> band.contains(r.price())));
        }
        return new TrackRecord(Instant.now(), acceptance("All", rows, r -> true), bands, deliveries(rows), prices(rows), shipping(rows),
                CAVEATS);
    }

    private static Acceptance acceptance(String label, List<Row> all, Predicate<Row> in) {
        List<Row> rows = all.stream().filter(in).toList();
        int open = count(rows, "OPEN");
        int accepted = count(rows, "ACCEPTED");
        int withdrawn = count(rows, "WITHDRAWN");
        int expired = count(rows, "EXPIRED");
        int closed = accepted + withdrawn + expired;
        BigDecimal medianHours = median(rows.stream().filter(r -> "ACCEPTED".equals(r.status())).map(Row::hoursToAccept)
                .filter(h -> h != null).toList());
        return new Acceptance(label, rows.size(), open, accepted, withdrawn, expired, closed, share(accepted, closed),
                medianHours == null ? null : medianHours.setScale(1, RoundingMode.HALF_UP), count(rows, "VOIDED"), count(rows, "CANCELLED"));
    }

    private static Deliveries deliveries(List<Row> rows) {
        List<Row> taken = rows.stream().filter(r -> "ACCEPTED".equals(r.status())).toList();
        int earlier = (int) taken.stream().filter(r -> "INSPECTED".equals(r.outcome())).count();
        List<Row> firm = taken.stream().filter(r -> !"INSPECTED".equals(r.outcome())).toList();
        int received = (int) firm.stream().filter(r -> "RECEIVED".equals(r.outcome())).count();
        int returned = (int) firm.stream().filter(r -> "RETURNED".equals(r.outcome())).count();
        Map<String, Integer> byReason = new LinkedHashMap<>();
        firm.stream().filter(r -> "RETURNED".equals(r.outcome())).forEach(r -> byReason.merge(r.returnedReason(), 1, Integer::sum));
        BigDecimal hours = median(firm.stream().map(Row::hoursToOutcome).filter(h -> h != null).toList());
        return new Deliveries(firm.size(), (int) firm.stream().filter(Row::labelled).count(), firm.size() - received - returned, received,
                returned, share(returned, received + returned), byReason, earlier, hours == null ? null : hours.setScale(1, RoundingMode.HALF_UP));
    }

    private static PriceCheck prices(List<Row> rows) {
        List<Row> paid = rows.stream()
                .filter(r -> "RECEIVED".equals(r.outcome()) && r.pricePaid() != null && r.price() != null && r.price().signum() > 0).toList();
        int below = 0;
        int at = 0;
        int above = 0;
        BigDecimal totalPaid = BigDecimal.ZERO;
        BigDecimal totalOffered = BigDecimal.ZERO;
        List<BigDecimal> ratios = new ArrayList<>();
        for (Row r : paid) {
            int c = r.pricePaid().compareTo(r.price());
            if (c < 0) {
                below++;
            } else if (c == 0) {
                at++;
            } else {
                above++;
            }
            totalPaid = totalPaid.add(r.pricePaid());
            totalOffered = totalOffered.add(r.price());
            ratios.add(r.pricePaid().divide(r.price(), 6, RoundingMode.HALF_UP));
        }
        BigDecimal medianRatio = median(ratios);
        return new PriceCheck(paid.size(), below, at, above, medianRatio == null ? null : medianRatio.setScale(4, RoundingMode.HALF_UP),
                totalPaid, totalOffered);
    }

    private static ShippingCost shipping(List<Row> rows) {
        List<Row> labelled = rows.stream().filter(Row::labelled).toList();
        BigDecimal postage = labelled.stream().map(Row::postage).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal insurance = labelled.stream().map(Row::insurance).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new ShippingCost(labelled.size(), postage, insurance, labelled.isEmpty() ? null
                : postage.add(insurance).divide(BigDecimal.valueOf(labelled.size()), 2, RoundingMode.HALF_UP));
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

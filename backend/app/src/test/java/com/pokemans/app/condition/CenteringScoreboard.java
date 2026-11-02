package com.pokemans.app.condition;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Scores the centering measurer against hand labels ({@code docs/centering-training-plan.md}). Pure:
 * readings in, numbers out. Errors are in percentage points of a pair's share, so 1 point on a 2.5 mm
 * border is about 0.05 mm. A reading is one photo; a face is one side of one physical card.
 */
final class CenteringScoreboard {

    /** A number further than this from the label is a false measurement, the worst failure. */
    static final double FALSE_POINTS = 2;
    /** Photos of one face agree when they're within this of each other. */
    static final double AGREE_POINTS = 1;

    /** A person's borders in mm: a null side is "can't see it". */
    record Truth(Double left, Double right, Double top, Double bottom) {

        Double leftShare() {
            return share(left, right);
        }

        Double topShare() {
            return share(top, bottom);
        }

        boolean full() {
            return leftShare() != null && topShare() != null;
        }

        /** The worse pair's larger share, or null unless all four sides were seen. */
        Double worstShare() {
            return full() ? Math.max(larger(leftShare()), larger(topShare())) : null;
        }

        private static Double share(Double a, Double b) {
            return a == null || b == null || a + b <= 0 ? null : 100 * a / (a + b);
        }
    }

    /**
     * One photo's outcome.
     *
     * @param faceId the physical card and face
     * @param truth the label, null if that face isn't labelled yet
     * @param leftShare what was measured, null for a refusal
     * @param reason why it was refused
     */
    record Reading(String faceId, boolean back, boolean hard, String photo, Truth truth, Double leftShare, Double topShare, String reason) {

        boolean measured() {
            return leftShare != null;
        }

        /** The larger error of the pairs a person could see, or null with nothing to compare. */
        Double error() {
            if (!measured() || truth == null) {
                return null;
            }
            Double lr = truth.leftShare() == null ? null : Math.abs(leftShare - truth.leftShare());
            Double tb = truth.topShare() == null ? null : Math.abs(topShare - truth.topShare());
            return lr == null ? tb : tb == null ? lr : Double.valueOf(Math.max(lr, tb));
        }

        double worstShare() {
            return Math.max(larger(leftShare), larger(topShare));
        }

        Integer cap(double share, double margin) {
            return back ? PsaCentering.cap(null, share, margin) : PsaCentering.cap(share, null, margin);
        }
    }

    /**
     * One row of the board.
     *
     * @param photos readings in the group
     * @param measured of those, how many gave a number
     * @param faces physical faces in the group, and {@code facesMeasured} how many have a number on any photo
     * @param scored readings with a number and a label to compare it to
     * @param medianError in points, NaN with none scored
     * @param unseen numbers given on a face where the person couldn't see a side, so nothing backs them
     * @param capCompared numbers with all four sides labelled, {@code capSame} of them with the same cap as
     *                    the true borders at the same margin, and {@code capTooHigh} above the truth with no margin
     * @param agreementFaces faces measured on at least two photos
     * @param agreementMedian spread of those in points, the worse pair; {@code agreementWithin} how many are
     *                        within {@link #AGREE_POINTS}
     */
    record Row(String group, int photos, int measured, int faces, int facesMeasured, int scored, double medianError, double p95Error,
               int falseMeasurements, int unseen, int capCompared, int capSame, int capTooHigh, int agreementFaces,
               double agreementMedian, double agreementP95, int agreementWithin) {
    }

    private CenteringScoreboard() {
    }

    /** The whole set, then fronts of hard cards, other fronts and backs. Labelled faces only where a label is needed. */
    static List<Row> score(Collection<Reading> readings, double margin) {
        Map<String, Predicate<Reading>> groups = new LinkedHashMap<>();
        groups.put("all", r -> true);
        groups.put("hard fronts", r -> !r.back() && r.hard());
        groups.put("other fronts", r -> !r.back() && !r.hard());
        groups.put("backs", Reading::back);
        return groups.entrySet().stream()
                .map(g -> row(g.getKey(), readings.stream().filter(g.getValue()).toList(), margin))
                .toList();
    }

    static Row row(String group, List<Reading> readings, double margin) {
        List<Reading> measured = readings.stream().filter(Reading::measured).toList();
        Map<String, List<Reading>> faces = readings.stream().collect(Collectors.groupingBy(Reading::faceId));
        int facesMeasured = (int) faces.values().stream().filter(f -> f.stream().anyMatch(Reading::measured)).count();

        double[] errors = measured.stream().map(Reading::error).filter(e -> e != null).mapToDouble(Double::doubleValue).toArray();
        int falses = (int) Arrays.stream(errors).filter(e -> e > FALSE_POINTS).count();
        int unseen = (int) measured.stream().filter(r -> r.truth() != null && !r.truth().full()).count();

        int capCompared = 0;
        int capSame = 0;
        int capTooHigh = 0;
        for (Reading r : measured) {
            Double truth = r.truth() == null ? null : r.truth().worstShare();
            if (truth == null) {
                continue;
            }
            capCompared++;
            int cap = r.cap(r.worstShare(), margin);
            capSame += cap == r.cap(truth, margin) ? 1 : 0;
            capTooHigh += cap > r.cap(truth, 0) ? 1 : 0;
        }

        List<Double> spreads = new ArrayList<>();
        for (List<Reading> face : faces.values()) {
            List<Reading> photos = face.stream().filter(Reading::measured).toList();
            if (photos.size() >= 2) {
                spreads.add(Math.max(spread(photos.stream().mapToDouble(Reading::leftShare).toArray()),
                        spread(photos.stream().mapToDouble(Reading::topShare).toArray())));
            }
        }
        double[] agreement = spreads.stream().mapToDouble(Double::doubleValue).toArray();
        int within = (int) Arrays.stream(agreement).filter(s -> s <= AGREE_POINTS).count();

        return new Row(group, readings.size(), measured.size(), faces.size(), facesMeasured, errors.length, percentile(errors, 0.5),
                percentile(errors, 0.95), falses, unseen, capCompared, capSame, capTooHigh, agreement.length, percentile(agreement, 0.5),
                percentile(agreement, 0.95), within);
    }

    /** The board as a Markdown table, for pasting into {@code docs/centering-training-plan.md}. */
    static String table(List<Row> rows) {
        StringBuilder out = new StringBuilder("""
                | Group | Photos measured | Faces measured | Scored | Error median | Error p95 | False (>2) | Seen by nobody | Same cap | Cap too high | Faces with 2+ photos | Spread median | Spread p95 | Within 1 |
                |---|---|---|---|---|---|---|---|---|---|---|---|---|---|
                """);
        for (Row r : rows) {
            out.append(String.format(Locale.ROOT, "| %s | %s | %s | %d | %s | %s | %s | %d | %s | %d | %d | %s | %s | %s |%n", r.group(),
                    count(r.measured(), r.photos()), count(r.facesMeasured(), r.faces()), r.scored(), points(r.medianError()),
                    points(r.p95Error()), r.scored() == 0 ? "–" : count(r.falseMeasurements(), r.scored()), r.unseen(),
                    r.capCompared() == 0 ? "–" : count(r.capSame(), r.capCompared()), r.capTooHigh(), r.agreementFaces(),
                    points(r.agreementMedian()), points(r.agreementP95()),
                    r.agreementFaces() == 0 ? "–" : count(r.agreementWithin(), r.agreementFaces())));
        }
        return out.toString();
    }

    private static String count(int part, int whole) {
        return whole == 0 ? "–" : String.format(Locale.ROOT, "%d/%d (%.0f%%)", part, whole, 100.0 * part / whole);
    }

    private static String points(double value) {
        return Double.isNaN(value) ? "–" : String.format(Locale.ROOT, "%.1f", value);
    }

    private static double larger(double share) {
        return Math.max(share, 100 - share);
    }

    private static double spread(double[] values) {
        return Arrays.stream(values).max().orElse(0) - Arrays.stream(values).min().orElse(0);
    }

    private static double percentile(double[] values, double q) {
        if (values.length == 0) {
            return Double.NaN;
        }
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        double position = q * (sorted.length - 1);
        int low = (int) Math.floor(position);
        int high = Math.min(sorted.length - 1, low + 1);
        return sorted[low] + (position - low) * (sorted[high] - sorted[low]);
    }
}

package com.pokemans.app.validation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CardGrade and the claude-vision shadow side by side for every card Claude has graded, with the PSA
 * grade when one has come back (docs/claude-vision-shadow.md). Read-only SQL over the stored rows, like
 * {@link ConditionCalibrationReport}: it decides nothing, it is what Zach reads before deciding on
 * providers.
 */
@Service
class PreGradeComparisonReport {

    /** One provider's answer as stored. Grades on PSA's 1–10 scale; {@code psa} is what each predicts PSA gives. */
    record Answer(UUID predictionId, Instant at, Double psa, Double overall, Double corners, Double edges, Double surface,
                  Double centering, String limitingFactor, String spread, String costUsd, String notes) {
    }

    /** A PSA grade that came back for the card: its latest. */
    record Returned(BigDecimal grade, String label, LocalDate gradedOn, String source) {
    }

    record Row(UUID cardItemId, UUID orgId, String label, Answer cardGrade, Answer claudeVision, Returned psa) {
    }

    /**
     * @param withPsa      cards with a PSA grade back
     * @param scored       of those, the cards both providers answered: every figure below is over these alone
     * @param cardGradeMae mean absolute gap between CardGrade's PSA prediction and the returned grade, null with none
     * @param claudeMae    the same for claude-vision
     * @param cardGradeWithinOne share of returned grades within ±1 of CardGrade's prediction
     * @param claudeWithinOne    the same for claude-vision
     * @param cardGradeAbove share where CardGrade predicted higher than PSA gave: the costly direction
     * @param claudeAbove    the same for claude-vision
     */
    record Report(int cards, int withPsa, int scored, Double cardGradeMae, Double claudeMae, Double cardGradeWithinOne, Double claudeWithinOne,
                  Double cardGradeAbove, Double claudeAbove, List<Row> rows) {
    }

    /**
     * Per card: claude-vision's latest successful answer; CardGrade's latest successful answer on the same
     * photos (claude-vision is asked about the photos CardGrade just graded); and the card's latest PSA
     * grade, leaving out a buyer's that was since corrected or taken back (as in the calibration report).
     */
    private static final String SQL = """
            with claude as (
                select distinct on (p.card_item_id) p.*
                  from provider_prediction p
                 where p.provider = 'claude-vision' and p.succeeded
                 order by p.card_item_id, p.created_at desc
            ),
            psa as (
                select distinct on (g.card_item_id) g.card_item_id, g.grade, g.grade_label, g.graded_on, g.source
                  from grade_outcome g
                 where g.grader = 'PSA' and g.grade is not null
                   and (g.source <> 'BUYER'
                        or g.id = (select d.grade_outcome_id from deal_outcome d
                                    where d.listing_id in (select d2.listing_id from deal_outcome d2 where d2.grade_outcome_id = g.id)
                                    order by d.created_at desc limit 1))
                 order by g.card_item_id, g.created_at desc
            )
            select c.card_item_id, c.org_id, ci.label,
                   c.id as c_id, c.created_at as c_at, c.psa_grade as c_psa, c.overall_grade as c_overall, c.corners as c_corners,
                   c.edges as c_edges, c.surface as c_surface, c.centering as c_centering,
                   c.details ->> 'limitingFactor' as c_limiting, c.details -> 'spread' ->> 'overall' as c_spread,
                   c.details ->> 'costUsd' as c_cost, c.details ->> 'notes' as c_notes,
                   k.id as k_id, k.created_at as k_at, k.psa_grade as k_psa, k.overall_grade as k_overall, k.corners as k_corners,
                   k.edges as k_edges, k.surface as k_surface, k.centering as k_centering,
                   k.details ->> 'limitingFactor' as k_limiting,
                   psa.grade as psa_grade, psa.grade_label as psa_label, psa.graded_on as psa_graded_on, psa.source as psa_source
              from claude c
              join card_item ci on ci.id = c.card_item_id
              left join lateral (
                  select * from provider_prediction p
                   where p.card_item_id = c.card_item_id and p.provider = 'CARDGRADE' and p.succeeded
                     and p.photo_ids = c.photo_ids
                   order by p.created_at desc limit 1
              ) k on true
              left join psa on psa.card_item_id = c.card_item_id
             order by c.created_at desc
            """;

    private final JdbcTemplate jdbc;

    PreGradeComparisonReport(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    Report compute() {
        List<Row> rows = new ArrayList<>();
        jdbc.query(SQL, rs -> {
            Answer claude = new Answer(uuid(rs.getString("c_id")), rs.getTimestamp("c_at").toInstant(), dbl(rs, "c_psa"),
                    dbl(rs, "c_overall"), dbl(rs, "c_corners"), dbl(rs, "c_edges"), dbl(rs, "c_surface"), dbl(rs, "c_centering"),
                    rs.getString("c_limiting"), rs.getString("c_spread"), rs.getString("c_cost"), rs.getString("c_notes"));
            Answer cardGrade = rs.getString("k_id") == null ? null
                    : new Answer(uuid(rs.getString("k_id")), rs.getTimestamp("k_at").toInstant(), dbl(rs, "k_psa"),
                    dbl(rs, "k_overall"), dbl(rs, "k_corners"), dbl(rs, "k_edges"), dbl(rs, "k_surface"), dbl(rs, "k_centering"),
                    rs.getString("k_limiting"), null, null, null);
            Returned psa = rs.getBigDecimal("psa_grade") == null ? null
                    : new Returned(rs.getBigDecimal("psa_grade"), rs.getString("psa_label"),
                    rs.getDate("psa_graded_on") == null ? null : rs.getDate("psa_graded_on").toLocalDate(), rs.getString("psa_source"));
            rows.add(new Row(uuid(rs.getString("card_item_id")), uuid(rs.getString("org_id")), rs.getString("label"), cardGrade, claude, psa));
        });
        return report(rows);
    }

    /** Package-private for tests: the summary over the rows. */
    static Report report(List<Row> rows) {
        // Both providers are scored on the same cards: those with a PSA grade that both answered.
        List<Row> returned = rows.stream().filter(r -> r.psa() != null).toList();
        List<Row> scored = returned.stream()
                .filter(r -> gap(r.cardGrade(), r.psa()) != null && gap(r.claudeVision(), r.psa()) != null).toList();
        return new Report(rows.size(), returned.size(), scored.size(),
                mean(scored, r -> gap(r.cardGrade(), r.psa()) == null ? null : Math.abs(gap(r.cardGrade(), r.psa()))),
                mean(scored, r -> gap(r.claudeVision(), r.psa()) == null ? null : Math.abs(gap(r.claudeVision(), r.psa()))),
                mean(scored, r -> share(gap(r.cardGrade(), r.psa()), g -> Math.abs(g) <= 1)),
                mean(scored, r -> share(gap(r.claudeVision(), r.psa()), g -> Math.abs(g) <= 1)),
                mean(scored, r -> share(gap(r.cardGrade(), r.psa()), g -> g > 0)),
                mean(scored, r -> share(gap(r.claudeVision(), r.psa()), g -> g > 0)),
                rows);
    }

    /** Predicted minus returned; null when the provider gave no PSA prediction. */
    private static Double gap(Answer answer, Returned psa) {
        Double predicted = answer == null ? null : answer.psa() != null ? answer.psa() : answer.overall();
        return predicted == null ? null : predicted - psa.grade().doubleValue();
    }

    private static Double share(Double gap, Function<Double, Boolean> test) {
        return gap == null ? null : test.apply(gap) ? 1.0 : 0.0;
    }

    private static Double mean(List<Row> rows, Function<Row, Double> value) {
        List<Double> values = rows.stream().map(value).filter(java.util.Objects::nonNull).toList();
        return values.isEmpty() ? null : Math.round(values.stream().mapToDouble(Double::doubleValue).average().orElseThrow() * 1000) / 1000.0;
    }

    private static Double dbl(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static UUID uuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }
}

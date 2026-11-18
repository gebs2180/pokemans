package com.pokemans.app.validation;

import com.pokemans.valuation.Grade;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads every returned grade and the assessment in force before it, straight from the stored rows, and
 * scores them ({@link ConditionCalibration}). Read-only SQL: no module's tables are written and no
 * module's internals are used, so it sits outside them.
 */
@Service
class ConditionCalibrationReport {

    /**
     * Every returned grade, except a buyer's that the buyer has since corrected or taken back (only the
     * grade on a deal's latest outcome row counts, as in DealOutcomes#retracted), with the cert's latest
     * check and the card's latest assessment made before the card was graded: before the grading date when
     * there is one, and always before the grade was recorded. An outcome with no such assessment comes back
     * with a null assessment id.
     */
    private static final String SQL = """
            with outcome as (
                select g.id, g.card_item_id, g.source, g.grader, g.grade, g.graded_on, g.created_at,
                       (select v.status from outcome_verification v
                         where v.outcome_id = g.id order by v.created_at desc limit 1) as cert_status
                  from grade_outcome g
                 where g.source <> 'BUYER'
                    or g.id = (select d.grade_outcome_id from deal_outcome d
                                where d.listing_id in (select d2.listing_id from deal_outcome d2 where d2.grade_outcome_id = g.id)
                                order by d.created_at desc limit 1)
            )
            select o.id as outcome_id, o.card_item_id, o.source, o.grader, o.cert_status, o.grade,
                   a.id as assessment_id, a.version, a.author_type, a.confidence,
                   (select string_agg(distinct s.value, ',') from jsonb_each_text(a.basis -> 'spreadUsed') s) as spread,
                   (a.distribution ->> 'U')::numeric as p_u,  (a.distribution ->> '1')::numeric as p_1,
                   (a.distribution ->> '2')::numeric as p_2,  (a.distribution ->> '3')::numeric as p_3,
                   (a.distribution ->> '4')::numeric as p_4,  (a.distribution ->> '5')::numeric as p_5,
                   (a.distribution ->> '6')::numeric as p_6,  (a.distribution ->> '7')::numeric as p_7,
                   (a.distribution ->> '8')::numeric as p_8,  (a.distribution ->> '9')::numeric as p_9,
                   (a.distribution ->> '10')::numeric as p_10
              from outcome o
              left join lateral (
                  select * from condition_assessment ca
                   where ca.card_item_id = o.card_item_id
                     and ca.created_at < o.created_at
                     and (o.graded_on is null or ca.created_at < (o.graded_on::timestamp at time zone 'UTC'))
                   order by ca.version desc
                   limit 1
              ) a on true
             order by o.created_at
            """;

    private final JdbcTemplate jdbc;

    ConditionCalibrationReport(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    ConditionCalibration.Report compute() {
        List<ConditionCalibration.Observation> observations = new ArrayList<>();
        int[] unpredicted = new int[1];
        jdbc.query(SQL, rs -> {
            String assessmentId = rs.getString("assessment_id");
            if (assessmentId == null) {
                unpredicted[0]++;
                return;
            }
            Map<Grade, BigDecimal> distribution = new EnumMap<>(Grade.class);
            for (Grade g : Grade.values()) {
                BigDecimal p = rs.getBigDecimal("p_" + g.toString().toLowerCase());
                distribution.put(g, p == null ? BigDecimal.ZERO : p);
            }
            String spread = rs.getString("spread");
            observations.add(new ConditionCalibration.Observation(
                    UUID.fromString(rs.getString("outcome_id")), UUID.fromString(rs.getString("card_item_id")),
                    rs.getString("source"), rs.getString("grader"), rs.getString("cert_status"), rs.getBigDecimal("grade"),
                    UUID.fromString(assessmentId), rs.getInt("version"), rs.getString("author_type"), rs.getString("confidence"),
                    spread == null ? "none" : spread.contains(",") ? "mixed" : spread, distribution));
        });
        return ConditionCalibration.report(observations, unpredicted[0]);
    }
}

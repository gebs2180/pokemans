package com.pokemans.app.pricing;

import com.pokemans.valuation.Confidence;
import com.pokemans.valuation.Grade;
import com.pokemans.valuation.GradePrice;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** What other modules (valuation, M4) may know about card prices. */
@Service
public class Prices {

    /**
     * One grade's snapshot price (gross, before grading costs).
     *
     * @param n          sales in the window before outlier trimming
     * @param compIds    comps behind an observed price, after trimming
     * @param windowDays 90 or 180 for an observed price, else null
     * @param basis      grades a derived price came from
     */
    public record GradeSnapshot(Grade grade, BigDecimal price, String currency, GradePrice.Method method, int n,
                                List<UUID> compIds, Integer windowDays, List<Grade> basis, BigDecimal stepDownRatio,
                                Confidence confidence) {
    }

    /**
     * A card's price table as of a date, with its market stats.
     *
     * @param gradingCompany whose graded comps priced this table
     * @param sigma          monthly σ, or null when there were too few 30-day changes
     */
    public record CardPrices(UUID snapshotSetId, UUID catalogCardId, LocalDate asOf, String parameterSet, String gradingCompany,
                             Map<Grade, GradeSnapshot> grades, BigDecimal sigma, int sigmaChanges, BigDecimal salesPerMonth) {
    }

    private final PriceSnapshotRepository snapshots;
    private final PriceStatsRepository stats;

    Prices(PriceSnapshotRepository snapshots, PriceStatsRepository stats) {
        this.snapshots = snapshots;
        this.stats = stats;
    }

    /**
     * The card's newest price table for one grading company (e.g. "PSA"), if that company's grades
     * have been priced. Companies are priced separately and never converted into one another.
     */
    @Transactional(readOnly = true)
    public Optional<CardPrices> latest(UUID catalogCardId, String gradingCompany) {
        return snapshots.findFirstByCatalogCardIdAndGradingCompanyOrderByAsOfDescCreatedAtDesc(catalogCardId,
                        PriceSource.GradingCompany.valueOf(gradingCompany))
                .flatMap(newest -> bySet(newest.getSnapshotSetId()));
    }

    /** The card's newest price table for each grading company it has been priced for, by company. */
    @Transactional(readOnly = true)
    public Map<String, CardPrices> latestByCompany(UUID catalogCardId) {
        Map<String, CardPrices> byCompany = new TreeMap<>();
        for (PriceSource.GradingCompany company : snapshots.findGradingCompaniesByCatalogCardId(catalogCardId)) {
            latest(catalogCardId, company.name()).ifPresent(p -> byCompany.put(company.name(), p));
        }
        return byCompany;
    }

    @Transactional(readOnly = true)
    public Optional<CardPrices> bySet(UUID snapshotSetId) {
        List<PriceSnapshot> rows = snapshots.findBySnapshotSetId(snapshotSetId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<Grade, GradeSnapshot> grades = new EnumMap<>(Grade.class);
        String gradingCompany = null;
        for (PriceSnapshot row : rows) {
            Grade grade = parse(row.getGrade());
            gradingCompany = row.getGradingCompany().name(); // NOT NULL since V9
            grades.put(grade, new GradeSnapshot(grade, row.getPrice(), row.getCurrency(), row.getMethod(), row.getN(),
                    row.getCompIds().stream().map(UUID::fromString).toList(), row.getWindowDays(),
                    row.getBasisGrades().stream().map(Prices::parse).toList(), row.getStepDownRatio(), row.getConfidence()));
        }
        PriceSnapshot first = rows.getFirst();
        Optional<PriceStats> stat = stats.findBySnapshotSetId(snapshotSetId);
        return Optional.of(new CardPrices(snapshotSetId, first.getCatalogCardId(), first.getAsOf(), first.getParameterSet(),
                gradingCompany, grades, stat.map(PriceStats::getSigma).orElse(null), stat.map(PriceStats::getSigmaChanges).orElse(0),
                stat.map(PriceStats::getSalesPerMonth).orElse(BigDecimal.ZERO)));
    }

    private static Grade parse(String grade) {
        return "U".equals(grade) ? Grade.U : Grade.of(Integer.parseInt(grade));
    }
}

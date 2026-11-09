package com.pokemans.app.outcomes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * What buyers say happened to the cards they bought through instant offers. The offers module checks
 * who may record one and when; this stores it, append-only. A graded card's result is a
 * {@link GradeOutcome} like any other, so the admin checks its cert on the same page, and a checked cert
 * values the card as graded through {@link Outcomes#verifiedGrades}.
 */
@Service
public class DealOutcomes {

    public enum Kind { GRADED, RESOLD_RAW, KEPT }

    /** The deal an outcome is about. The grade is filed against the seller's org, which owns the card item. */
    public record Deal(UUID listingId, UUID offerId, UUID cardItemId, UUID sellerOrgId, UUID buyerOrgId) {
    }

    /**
     * @param grader one of {@link #graders()}
     * @param grade  as returned, half grades kept (8.5); null for a result without one ("Authentic", "Altered")
     */
    public record GradeReport(String grader, String certNumber, BigDecimal grade, String gradeLabel, LocalDate gradedOn) {
    }

    /**
     * The newest outcome recorded on a deal.
     *
     * @param certStatus UNVERIFIED until the admin checks the cert, then VERIFIED or MISMATCH; null if not graded
     * @param earlier    how many outcomes this one corrects
     */
    public record Recorded(UUID id, UUID listingId, UUID offerId, UUID cardItemId, UUID buyerOrgId, Kind kind, UUID gradeOutcomeId,
                           String grader, String certNumber, BigDecimal grade, String gradeLabel, LocalDate gradedOn, String certStatus,
                           String lookupUrl, BigDecimal resalePrice, String note, Instant recordedAt, int earlier) {
    }

    private final DealOutcomeRepository deals;
    private final GradeOutcomeRepository grades;
    private final OutcomeVerificationRepository verifications;

    DealOutcomes(DealOutcomeRepository deals, GradeOutcomeRepository grades, OutcomeVerificationRepository verifications) {
        this.deals = deals;
        this.grades = grades;
        this.verifications = verifications;
    }

    /** The graders a result can be recorded for. */
    public static List<String> graders() {
        return java.util.Arrays.stream(Grader.values()).map(Grader::name).toList();
    }

    /**
     * Appends what happened. The caller holds the card's lock and has checked the buyer's right to say.
     * Re-recording a grade exactly as it stands reuses its row, so a check the admin already made stands.
     */
    @Transactional
    public Recorded record(Deal deal, Kind kind, GradeReport graded, BigDecimal resalePrice, String note, UUID userId) {
        List<DealOutcome> history = deals.findByListingIdInOrderByCreatedAtAsc(List.of(deal.listingId()));
        DealOutcome current = history.isEmpty() ? null : history.getLast();
        UUID gradeId = null;
        BigDecimal resale = null;
        switch (kind) {
            case GRADED -> gradeId = gradeFor(deal, graded, userId);
            case RESOLD_RAW -> {
                if (resalePrice == null || resalePrice.signum() < 0) {
                    throw badRequest("Give the price you resold it for.");
                }
                resale = resalePrice;
            }
            case KEPT -> {
            }
        }
        if (current != null && current.getKind() == kind && Objects.equals(current.getGradeOutcomeId(), gradeId)
                && compare(current.getResalePrice(), resale)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That's already what's recorded for this card.");
        }
        DealOutcome saved = deals.save(new DealOutcome(deal, kind, gradeId, resale, blankToNull(note), userId));
        return view(saved, history.size());
    }

    /** The newest outcome per listing, for those that have one. */
    @Transactional(readOnly = true)
    public Map<UUID, Recorded> latest(Collection<UUID> listingIds) {
        return listingIds.isEmpty() ? Map.of() : latestOf(deals.findByListingIdInOrderByCreatedAtAsc(listingIds));
    }

    /** The newest outcome on each of this buyer org's deals. */
    @Transactional(readOnly = true)
    public List<Recorded> latestForBuyer(UUID buyerOrgId) {
        return List.copyOf(latestOf(deals.findByBuyerOrgIdOrderByCreatedAtAsc(buyerOrgId)).values());
    }

    /** The newest outcome on every deal, oldest deal first. */
    @Transactional(readOnly = true)
    public List<Recorded> latestAll() {
        return List.copyOf(latestOf(deals.findAllByOrderByCreatedAtAsc()).values());
    }

    private UUID gradeFor(Deal deal, GradeReport report, UUID userId) {
        if (report == null || report.grader() == null || report.certNumber() == null || report.certNumber().isBlank()) {
            throw badRequest("Give the grader and the cert number.");
        }
        Grader grader;
        try {
            grader = Grader.valueOf(report.grader());
        } catch (IllegalArgumentException e) {
            throw badRequest("Unknown grader: " + report.grader());
        }
        String cert = report.certNumber().trim();
        if (!cert.matches("[A-Za-z0-9-]{1,30}")) {
            throw badRequest("A cert number is letters, digits and dashes.");
        }
        BigDecimal grade = report.grade();
        String label = blankToNull(report.gradeLabel());
        if (grade == null && label == null) {
            throw badRequest("Give the grade, or the label's wording for a result without one.");
        }
        if (grade != null && (grade.compareTo(BigDecimal.ONE) < 0 || grade.compareTo(BigDecimal.TEN) > 0
                || grade.multiply(BigDecimal.TWO).stripTrailingZeros().scale() > 0)) {
            throw badRequest("A grade is 1 to 10, in whole or half steps.");
        }
        if (grades.existsByGraderAndCertNumberAndCardItemIdNot(grader, cert, deal.cardItemId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This cert is already recorded for another card.");
        }
        // The same result again (say, after recording "kept" by mistake) is the row already there, check and all.
        for (GradeOutcome existing : grades.findByCardItemIdOrderByCreatedAtDesc(deal.cardItemId())) {
            if (existing.getSource() == GradeOutcome.Source.BUYER && existing.getGrader() == grader && existing.getCertNumber().equals(cert)
                    && compare(existing.getGrade(), grade) && Objects.equals(existing.getGradeLabel(), label)
                    && Objects.equals(existing.getGradedOn(), report.gradedOn())) {
                return existing.getId();
            }
        }
        return grades.save(new GradeOutcome(deal.sellerOrgId(), deal.cardItemId(), GradeOutcome.Source.BUYER, grader, cert, grade, label,
                report.gradedOn(), userId)).getId();
    }

    private Map<UUID, Recorded> latestOf(List<DealOutcome> oldestFirst) {
        Map<UUID, List<DealOutcome>> byListing = oldestFirst.stream()
                .collect(Collectors.groupingBy(DealOutcome::getListingId, LinkedHashMap::new, Collectors.toList()));
        List<UUID> gradeIds = byListing.values().stream().map(List::getLast).map(DealOutcome::getGradeOutcomeId).filter(Objects::nonNull).toList();
        Map<UUID, GradeOutcome> gradeById = gradeIds.isEmpty() ? Map.of()
                : grades.findAllById(gradeIds).stream().collect(Collectors.toMap(GradeOutcome::getId, Function.identity()));
        Map<UUID, OutcomeVerification> checks = gradeIds.isEmpty() ? Map.of()
                : verifications.findByOutcomeIdInOrderByCreatedAtDesc(gradeIds).stream()
                .collect(Collectors.toMap(OutcomeVerification::getOutcomeId, Function.identity(), (newer, older) -> newer));
        Map<UUID, Recorded> result = new LinkedHashMap<>();
        byListing.forEach((listingId, rows) -> {
            DealOutcome last = rows.getLast();
            UUID gradeId = last.getGradeOutcomeId();
            result.put(listingId, gradeId == null ? view(last, null, null, rows.size() - 1)
                    : view(last, gradeById.get(gradeId), checks.get(gradeId), rows.size() - 1));
        });
        return result;
    }

    private Recorded view(DealOutcome saved, int earlier) {
        GradeOutcome grade = saved.getGradeOutcomeId() == null ? null : grades.findById(saved.getGradeOutcomeId()).orElseThrow();
        OutcomeVerification check = grade == null ? null
                : verifications.findByOutcomeIdInOrderByCreatedAtDesc(List.of(grade.getId())).stream().findFirst().orElse(null);
        return view(saved, grade, check, earlier);
    }

    private static Recorded view(DealOutcome d, GradeOutcome g, OutcomeVerification check, int earlier) {
        return new Recorded(d.getId(), d.getListingId(), d.getOfferId(), d.getCardItemId(), d.getBuyerOrgId(), d.getKind(), d.getGradeOutcomeId(),
                g == null ? null : g.getGrader().name(), g == null ? null : g.getCertNumber(), g == null ? null : g.getGrade(),
                g == null ? null : g.getGradeLabel(), g == null ? null : g.getGradedOn(),
                g == null ? null : check == null ? "UNVERIFIED" : check.getStatus().name(),
                g == null ? null : g.getGrader().lookupUrl(g.getCertNumber()), d.getResalePrice(), d.getNote(), d.getCreatedAt(), earlier);
    }

    /**
     * The buyer-recorded grade outcomes that a later deal outcome replaced: a typo corrected, or "graded"
     * taken back. They stay stored, but no longer say what grade the card is.
     */
    Collection<UUID> retracted(Collection<GradeOutcome> candidates) {
        List<UUID> buyerGrades = candidates.stream().filter(g -> g.getSource() == GradeOutcome.Source.BUYER).map(GradeOutcome::getId).toList();
        if (buyerGrades.isEmpty()) {
            return List.of();
        }
        List<UUID> listingIds = deals.findByGradeOutcomeIdIn(buyerGrades).stream().map(DealOutcome::getListingId).distinct().toList();
        Set<UUID> current = new HashSet<>();
        Map<UUID, List<DealOutcome>> byListing = deals.findByListingIdInOrderByCreatedAtAsc(listingIds).stream()
                .collect(Collectors.groupingBy(DealOutcome::getListingId));
        byListing.values().forEach(rows -> {
            UUID id = rows.getLast().getGradeOutcomeId();
            if (id != null) {
                current.add(id);
            }
        });
        List<UUID> out = new ArrayList<>(buyerGrades);
        out.removeAll(current);
        return out;
    }

    private static boolean compare(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}

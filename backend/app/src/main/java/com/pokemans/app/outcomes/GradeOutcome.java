package com.pokemans.app.outcomes;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * What a grading company returned for a card: the label every prediction is measured against.
 * Immutable; whether the cert checks out is recorded separately ({@link OutcomeVerification}).
 */
@Entity
@Table(name = "grade_outcome")
class GradeOutcome {

    enum Source { CONTRIBUTOR, PARTNER, ADMIN }

    @Id
    private UUID id;
    private UUID orgId;
    private UUID cardItemId;
    @Enumerated(EnumType.STRING)
    private Source source;
    @Enumerated(EnumType.STRING)
    private Grader grader;
    private String certNumber;
    private BigDecimal grade;
    private String gradeLabel;
    private LocalDate gradedOn;
    private UUID createdBy;
    private Instant createdAt;

    protected GradeOutcome() {
    }

    GradeOutcome(UUID orgId, UUID cardItemId, Source source, Grader grader, String certNumber, BigDecimal grade,
                 String gradeLabel, LocalDate gradedOn, UUID createdBy) {
        this.id = UUID.randomUUID();
        this.orgId = orgId;
        this.cardItemId = cardItemId;
        this.source = source;
        this.grader = grader;
        this.certNumber = certNumber;
        this.grade = grade;
        this.gradeLabel = gradeLabel;
        this.gradedOn = gradedOn;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    UUID getId() {
        return id;
    }

    UUID getOrgId() {
        return orgId;
    }

    UUID getCardItemId() {
        return cardItemId;
    }

    Source getSource() {
        return source;
    }

    Grader getGrader() {
        return grader;
    }

    String getCertNumber() {
        return certNumber;
    }

    BigDecimal getGrade() {
        return grade;
    }

    String getGradeLabel() {
        return gradeLabel;
    }

    LocalDate getGradedOn() {
        return gradedOn;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}

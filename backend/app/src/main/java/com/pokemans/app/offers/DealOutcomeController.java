package com.pokemans.app.offers;

import com.pokemans.app.identity.Consents;
import com.pokemans.app.identity.OrgAccess;
import com.pokemans.app.identity.OrgType;
import com.pokemans.app.outcomes.DealOutcomes;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The buyer says what happened to a card it bought (review C1, 2026-11-09). Only the buying org's own
 * members may: the admin can't say it for them, since the point is the buyer's word on the grade.
 */
@RestController
class DealOutcomeController {

    /**
     * @param grader      for GRADED: PSA, BGS, CGC, SGC or TAG
     * @param grade       for GRADED: as on the label, half grades kept; null for a result without one
     * @param gradeLabel  for GRADED: the label's wording, e.g. "GEM MT 10" or "AUTHENTIC"
     * @param resalePrice for RESOLD_RAW
     */
    record OutcomeBody(@NotNull DealOutcomes.Kind kind, @Size(max = 10) String grader, @Size(max = 30) String certNumber,
                       @DecimalMin("1.0") @DecimalMax("10.0") BigDecimal grade, @Size(max = 50) String gradeLabel, LocalDate gradedOn,
                       @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal resalePrice, @Size(max = 2000) String note) {
    }

    private final DealOutcomeService service;
    private final OrgAccess access;
    private final Consents consents;

    DealOutcomeController(DealOutcomeService service, OrgAccess access, Consents consents) {
        this.service = service;
        this.access = access;
        this.consents = consents;
    }

    @PostMapping("/api/orgs/{orgId}/deals/{listingId}/outcomes")
    @ResponseStatus(HttpStatus.CREATED)
    DealOutcomeService.BuyerRecord record(@PathVariable UUID orgId, @PathVariable UUID listingId, @Valid @RequestBody OutcomeBody body) {
        access.requireMember(orgId, OrgType.BUYER);
        consents.requireAccepted(orgId);
        DealOutcomes.GradeReport graded = body.kind() == DealOutcomes.Kind.GRADED
                ? new DealOutcomes.GradeReport(body.grader(), body.certNumber(), body.grade(), body.gradeLabel(), body.gradedOn())
                : null;
        service.record(orgId, listingId, new DealOutcomeService.OutcomeRequest(body.kind(), graded, body.resalePrice(), body.note()));
        return service.buyerRecord(orgId);
    }

    /** The buyer org's outcomes, and how often its graded cards came back within a grade of our prediction. */
    @GetMapping("/api/orgs/{orgId}/deal-outcomes")
    DealOutcomeService.BuyerRecord buyerRecord(@PathVariable UUID orgId) {
        access.require(orgId, OrgType.BUYER);
        consents.requireAccepted(orgId);
        return service.buyerRecord(orgId);
    }
}

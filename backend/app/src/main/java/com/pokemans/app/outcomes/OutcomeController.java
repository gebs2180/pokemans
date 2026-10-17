package com.pokemans.app.outcomes;

import com.pokemans.app.identity.Consents;
import com.pokemans.app.identity.CurrentUser;
import com.pokemans.app.identity.OrgAccess;
import com.pokemans.app.identity.OrgType;
import com.pokemans.app.intake.CardItems;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Grading results. In M2 contributors record them for cards they photographed raw; partners
 * recording their submissions' returned grades comes in M5. The admin checks each cert by hand.
 */
@RestController
class OutcomeController {

    /**
     * @param grade      numeric grade; null for results without one ("Authentic", "Altered")
     * @param gradeLabel as printed on the label, e.g. "GEM MT 10"
     */
    record RecordOutcome(@NotNull Grader grader,
                         @NotBlank @Size(max = 30) @Pattern(regexp = "[A-Za-z0-9-]+") String certNumber,
                         @DecimalMin("1.0") @DecimalMax("10.0") BigDecimal grade,
                         @Size(max = 50) String gradeLabel,
                         LocalDate gradedOn) {
    }

    record Verify(@NotNull OutcomeVerification.Status status, @Size(max = 2000) String note) {
    }

    /** @param status UNVERIFIED until the admin checks the cert, then the latest check */
    record OutcomeView(UUID id, UUID orgId, UUID cardId, String cardLabel, GradeOutcome.Source source, Grader grader,
                       String certNumber, BigDecimal grade, String gradeLabel, LocalDate gradedOn, String status,
                       String verificationNote, String lookupUrl, Instant createdAt) {
    }

    private final GradeOutcomeRepository outcomes;
    private final OutcomeVerificationRepository verifications;
    private final CardItems cardItems;
    private final OrgAccess access;
    private final Consents consents;
    private final CurrentUser currentUser;

    OutcomeController(GradeOutcomeRepository outcomes, OutcomeVerificationRepository verifications, CardItems cardItems,
                      OrgAccess access, Consents consents, CurrentUser currentUser) {
        this.outcomes = outcomes;
        this.verifications = verifications;
        this.cardItems = cardItems;
        this.access = access;
        this.consents = consents;
        this.currentUser = currentUser;
    }

    @PostMapping("/api/orgs/{orgId}/cards/{cardId}/outcomes")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    OutcomeView record(@PathVariable UUID orgId, @PathVariable UUID cardId, @Valid @RequestBody RecordOutcome request) {
        access.require(orgId, OrgType.CONTRIBUTOR);
        consents.requireAccepted(orgId);
        CardItems.CardItemRef card = cardItems.requireAccessible(orgId, cardId);
        if (card.lot()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A grade belongs to a single card, not a lot");
        }
        List<CardItems.PhotoRef> photos = cardItems.passedPhotos(cardId);
        if (photos.stream().noneMatch(p -> p.side().equals("FRONT")) || photos.stream().noneMatch(p -> p.side().equals("BACK"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Add passing raw photos of the front and back first");
        }
        String cert = request.certNumber().trim();
        if (outcomes.existsByGraderAndCertNumber(request.grader(), cert)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This cert has already been recorded");
        }
        GradeOutcome.Source source = currentUser.isAdmin() ? GradeOutcome.Source.ADMIN : GradeOutcome.Source.CONTRIBUTOR;
        GradeOutcome saved = outcomes.save(new GradeOutcome(orgId, cardId, source, request.grader(), cert, request.grade(),
                blankToNull(request.gradeLabel()), request.gradedOn(), currentUser.id()));
        return view(saved, card.label(), null);
    }

    @GetMapping("/api/orgs/{orgId}/cards/{cardId}/outcomes")
    @Transactional(readOnly = true)
    List<OutcomeView> forCard(@PathVariable UUID orgId, @PathVariable UUID cardId) {
        CardItems.CardItemRef card = cardItems.requireAccessible(orgId, cardId);
        List<GradeOutcome> list = outcomes.findByCardItemIdOrderByCreatedAtDesc(cardId);
        Map<UUID, OutcomeVerification> latest = latestVerifications(list);
        return list.stream().map(o -> view(o, card.label(), latest.get(o.getId()))).toList();
    }

    /** Admin: outcomes by status, oldest first. {@code status} is UNVERIFIED, VERIFIED, MISMATCH or ALL. */
    @GetMapping("/api/admin/outcomes")
    @Transactional(readOnly = true)
    List<OutcomeView> list(@RequestParam(defaultValue = "UNVERIFIED") String status) {
        List<GradeOutcome> all = outcomes.findAllByOrderByCreatedAtAsc();
        Map<UUID, OutcomeVerification> latest = latestVerifications(all);
        return all.stream()
                .map(o -> view(o, cardItems.get(o.getCardItemId()).label(), latest.get(o.getId())))
                .filter(v -> status.equals("ALL") || v.status().equals(status))
                .toList();
    }

    @PostMapping("/api/admin/outcomes/{outcomeId}/verifications")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    OutcomeView verify(@PathVariable UUID outcomeId, @Valid @RequestBody Verify request) {
        GradeOutcome outcome = outcomes.findById(outcomeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Outcome not found"));
        OutcomeVerification check = verifications.save(
                new OutcomeVerification(outcomeId, request.status(), blankToNull(request.note()), currentUser.id()));
        return view(outcome, cardItems.get(outcome.getCardItemId()).label(), check);
    }

    private Map<UUID, OutcomeVerification> latestVerifications(List<GradeOutcome> list) {
        if (list.isEmpty()) {
            return Map.of();
        }
        return verifications.findByOutcomeIdInOrderByCreatedAtDesc(list.stream().map(GradeOutcome::getId).toList()).stream()
                .collect(Collectors.toMap(OutcomeVerification::getOutcomeId, Function.identity(), (newer, older) -> newer));
    }

    private static OutcomeView view(GradeOutcome o, String cardLabel, OutcomeVerification check) {
        return new OutcomeView(o.getId(), o.getOrgId(), o.getCardItemId(), cardLabel, o.getSource(), o.getGrader(),
                o.getCertNumber(), o.getGrade(), o.getGradeLabel(), o.getGradedOn(),
                check == null ? "UNVERIFIED" : check.getStatus().name(), check == null ? null : check.getNote(),
                o.getGrader().lookupUrl(o.getCertNumber()), o.getCreatedAt());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

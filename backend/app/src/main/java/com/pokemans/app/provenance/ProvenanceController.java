package com.pokemans.app.provenance;

import com.pokemans.app.catalog.AuthenticityRisk;
import com.pokemans.app.catalog.Catalog;
import com.pokemans.app.intake.CardItems;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authenticity findings and custody per card. Recording either is admin work; members of the
 * card's org can read both.
 */
@RestController
class ProvenanceController {

    record VerifyRequest(@NotNull Provenance.Finding status, @NotNull Provenance.AuthMethod method, @Size(max = 2000) String note) {
    }

    /** @param verificationMethod how custody was checked; null records it unverified */
    record CustodyRequest(@NotNull Provenance.HolderType holderType,
                          @NotBlank @Size(max = 200) String holderName,
                          @NotBlank @Size(max = 500) String location,
                          @NotNull LocalDate heldSince,
                          Provenance.CustodyMethod verificationMethod,
                          @Size(max = 2000) String note) {
    }

    /** @param forCurrentIdentity whether the finding was made against the identity the card has now, since it got it */
    record VerificationView(UUID id, Provenance.Finding status, Provenance.AuthMethod method, String note, UUID catalogCardId,
                            boolean forCurrentIdentity, UUID verifiedBy, Instant createdAt) {
    }

    record CustodyView(UUID id, Provenance.HolderType holderType, String holderName, String location, LocalDate heldSince,
                       boolean verified, Provenance.CustodyMethod verificationMethod, UUID verifiedBy, String note,
                       UUID recordedBy, Instant createdAt) {
    }

    /**
     * @param authenticityRisk the current identity's risk category, or null if not identified
     * @param verifications    findings, newest first
     * @param custody          current custody, or null if none is recorded
     * @param custodyHistory   custody records, current first
     */
    record ProvenanceView(UUID cardId, boolean authFlagUnresolved, Provenance.AuthState authState,
                          AuthenticityRisk authenticityRisk, List<VerificationView> verifications, boolean custodyVerified,
                          Provenance.CustodyState custodyState, CustodyView custody, List<CustodyView> custodyHistory) {
    }

    private final ProvenanceService service;
    private final Provenance provenance;
    private final Catalog catalog;
    private final CardItems cardItems;

    ProvenanceController(ProvenanceService service, Provenance provenance, Catalog catalog, CardItems cardItems) {
        this.service = service;
        this.provenance = provenance;
        this.catalog = catalog;
        this.cardItems = cardItems;
    }

    @PostMapping("/api/admin/orgs/{orgId}/cards/{cardId}/authenticity-verifications")
    @ResponseStatus(HttpStatus.CREATED)
    ProvenanceView verify(@PathVariable UUID orgId, @PathVariable UUID cardId, @Valid @RequestBody VerifyRequest r) {
        service.verify(orgId, cardId, r.status(), r.method(), r.note());
        return view(cardId);
    }

    @PostMapping("/api/admin/orgs/{orgId}/cards/{cardId}/custody-records")
    @ResponseStatus(HttpStatus.CREATED)
    ProvenanceView recordCustody(@PathVariable UUID orgId, @PathVariable UUID cardId, @Valid @RequestBody CustodyRequest r) {
        service.recordCustody(orgId, cardId, new ProvenanceService.CustodyEntry(r.holderType(), r.holderName(), r.location(),
                r.heldSince(), r.verificationMethod(), r.note()));
        return view(cardId);
    }

    @GetMapping("/api/orgs/{orgId}/cards/{cardId}/provenance")
    ProvenanceView provenance(@PathVariable UUID orgId, @PathVariable UUID cardId) {
        cardItems.requireAccessible(orgId, cardId);
        return view(cardId);
    }

    private ProvenanceView view(UUID cardId) {
        Provenance.Status status = provenance.status(cardId);
        Catalog.IdentifiedCard identity = catalog.current(cardId).orElse(null);
        List<VerificationView> findings = service.verifications(cardId).stream()
                .map(v -> new VerificationView(v.getId(), v.getStatus(), v.getMethod(), v.getNote(), v.getCatalogCardId(),
                        Provenance.countsFor(v, identity), v.getVerifiedBy(), v.getCreatedAt()))
                .toList();
        List<CustodyView> custody = service.custody(cardId).stream()
                .map(c -> new CustodyView(c.getId(), c.getHolderType(), c.getHolderName(), c.getLocation(), c.getHeldSince(),
                        c.isVerified(), c.getVerificationMethod(), c.getVerifiedBy(), c.getNote(), c.getRecordedBy(),
                        c.getCreatedAt()))
                .toList();
        return new ProvenanceView(cardId, status.authFlagUnresolved(), status.authState(), status.authenticityRisk(), findings,
                status.custodyVerified(), status.custodyState(), custody.isEmpty() ? null : custody.getFirst(), custody);
    }
}

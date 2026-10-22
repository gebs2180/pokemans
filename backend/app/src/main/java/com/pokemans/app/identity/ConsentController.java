package com.pokemans.app.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** An org's terms (seller, contributor or buyer): read them, see whether you accepted, accept. */
@RestController
class ConsentController {

    record AcceptRequest(@NotBlank String version) {
    }

    /** @param acceptedAt when the caller accepted the current version for this org, or null */
    record ConsentStatus(String version, String text, Instant acceptedAt) {
    }

    private final Consents consents;
    private final OrgAccess access;

    ConsentController(Consents consents, OrgAccess access) {
        this.consents = consents;
        this.access = access;
    }

    @GetMapping("/api/orgs/{orgId}/consent")
    ConsentStatus status(@PathVariable UUID orgId) {
        Consents.Terms terms = Consents.termsFor(access.require(orgId, Consents.TERMS_ORGS).getType()).orElseThrow();
        return new ConsentStatus(terms.version(), terms.text(), consents.acceptedAt(orgId).orElse(null));
    }

    @PostMapping("/api/orgs/{orgId}/consent")
    ConsentStatus accept(@PathVariable UUID orgId, @Valid @RequestBody AcceptRequest request) {
        Instant accepted = consents.accept(orgId, request.version());
        Consents.Terms terms = Consents.termsFor(access.require(orgId, Consents.TERMS_ORGS).getType()).orElseThrow();
        return new ConsentStatus(terms.version(), terms.text(), accepted);
    }
}

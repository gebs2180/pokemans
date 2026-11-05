package com.pokemans.app.offers;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The offer terms' numbers. Public: the how-it-works page shows them before anyone signs in. */
@RestController
class OfferPolicyController {

    private final OfferPolicy policy;

    OfferPolicyController(OfferPolicy policy) {
        this.policy = policy;
    }

    @GetMapping("/api/policy")
    ResponseEntity<OfferPolicy.Terms> policy() {
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(policy.terms());
    }
}

package com.pokemans.app.identity;

import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Any signed-in user can look at and accept an invite they were given. */
@RestController
@RequestMapping("/api/invites/{token}")
class InviteController {

    record Accepted(UUID orgId, Role role) {
    }

    private final InviteService invites;

    InviteController(InviteService invites) {
        this.invites = invites;
    }

    @GetMapping
    InviteService.InvitePreview preview(@PathVariable String token) {
        return invites.preview(token);
    }

    @PostMapping("/accept")
    Accepted accept(@PathVariable String token) {
        Membership membership = invites.accept(token);
        return new Accepted(membership.getOrgId(), membership.getRole());
    }
}

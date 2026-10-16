package com.pokemans.app.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Admin-only (enforced by path in SecurityConfig): orgs and invite links. */
@RestController
@RequestMapping("/api/admin/orgs")
class AdminOrgController {

    record CreateOrg(@NotBlank @Size(max = 200) String name, @NotNull OrgType type) {
    }

    record OrgView(UUID id, String name, OrgType type, Instant createdAt, int members) {
    }

    record CreateInvite(@Email String email) {
    }

    record InviteView(UUID id, String token, String acceptPath, Instant expiresAt) {
    }

    private final OrgRepository orgs;
    private final MembershipRepository memberships;
    private final InviteService invites;

    AdminOrgController(OrgRepository orgs, MembershipRepository memberships, InviteService invites) {
        this.orgs = orgs;
        this.memberships = memberships;
        this.invites = invites;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    OrgView create(@Valid @RequestBody CreateOrg request) {
        return view(orgs.save(new Org(request.name().trim(), request.type())));
    }

    @GetMapping
    List<OrgView> list() {
        return orgs.findAllByOrderByNameAsc().stream().map(this::view).toList();
    }

    /** The token is returned once; the admin shares the accept link with the invitee. */
    @PostMapping("/{orgId}/invites")
    @ResponseStatus(HttpStatus.CREATED)
    InviteView invite(@PathVariable UUID orgId, @Valid @RequestBody(required = false) CreateInvite request) {
        var created = invites.create(orgId, request == null ? null : request.email());
        return new InviteView(created.inviteId(), created.token(), "/invite/" + created.token(), created.expiresAt());
    }

    private OrgView view(Org org) {
        return new OrgView(org.getId(), org.getName(), org.getType(), org.getCreatedAt(), memberships.findByOrgId(org.getId()).size());
    }
}

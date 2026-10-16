package com.pokemans.app.identity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Who the caller is, as the API sees them. Lets the SPA (and you) check the auth wiring. */
@RestController
class MeController {

    /**
     * @param roles        ADMIN (from the token, MFA-gated) plus the roles from org memberships
     * @param claimedRoles roles as they arrived in the token's roles claim (empty if the claim is missing)
     * @param mfa          whether the token shows an MFA login
     */
    record Me(String subject, String issuer, List<String> roles, List<String> claimedRoles, boolean mfa,
              List<MembershipView> memberships) {
    }

    record MembershipView(UUID orgId, String orgName, OrgType orgType, Role role) {
    }

    private final SecurityProperties properties;
    private final CurrentUser currentUser;
    private final MembershipRepository memberships;
    private final OrgRepository orgs;

    MeController(SecurityProperties properties, CurrentUser currentUser, MembershipRepository memberships, OrgRepository orgs) {
        this.properties = properties;
        this.currentUser = currentUser;
        this.memberships = memberships;
        this.orgs = orgs;
    }

    @GetMapping("/api/me")
    @Transactional(readOnly = true)
    Me me(JwtAuthenticationToken auth) {
        Jwt jwt = auth.getToken();
        AppUser user = currentUser.get();

        List<MembershipView> views = memberships.findByUserId(user.getId()).stream()
                .map(m -> {
                    Org org = orgs.findById(m.getOrgId()).orElseThrow();
                    return new MembershipView(org.getId(), org.getName(), org.getType(), m.getRole());
                })
                .toList();

        List<String> roles = new ArrayList<>();
        if (currentUser.isAdmin()) {
            roles.add(Role.ADMIN.name());
        }
        views.stream().map(v -> v.role().name()).distinct().sorted().forEach(roles::add);

        List<String> claimed = jwt.hasClaim(properties.rolesClaim()) ? jwt.getClaimAsStringList(properties.rolesClaim()) : null;
        Object issuer = jwt.getClaims().get("iss");
        return new Me(auth.getName(), issuer == null ? null : issuer.toString(), roles,
                claimed == null ? List.of() : claimed, properties.mfa(jwt), views);
    }
}

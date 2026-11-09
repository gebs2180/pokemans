package com.pokemans.app.identity;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Tenancy gate for org-scoped endpoints: the caller must be a member of the org (or ADMIN), and the
 * org must be of the expected type. Anything else is a 404, so org ids are not confirmed to outsiders.
 */
@Component
public class OrgAccess {

    private final OrgRepository orgs;
    private final MembershipRepository memberships;
    private final CurrentUser currentUser;

    OrgAccess(OrgRepository orgs, MembershipRepository memberships, CurrentUser currentUser) {
        this.orgs = orgs;
        this.memberships = memberships;
        this.currentUser = currentUser;
    }

    /** The org, if the caller may use it and it is one of the expected types; otherwise 404. */
    @Transactional(readOnly = true)
    public Org require(UUID orgId, OrgType... expectedTypes) {
        Org org = orgs.findById(orgId).orElseThrow(OrgAccess::notFound);
        if (!Arrays.asList(expectedTypes).contains(org.getType())) {
            throw notFound();
        }
        if (!currentUser.isAdmin() && memberships.findByOrgIdAndUserId(orgId, currentUser.id()).isEmpty()) {
            throw notFound();
        }
        return org;
    }

    /**
     * Like {@link #require}, but only for the org's own members: the admin gets no pass. For things only the
     * org can say about itself, such as what a buyer did with a card it bought.
     */
    @Transactional(readOnly = true)
    public Org requireMember(UUID orgId, OrgType... expectedTypes) {
        Org org = orgs.findById(orgId).orElseThrow(OrgAccess::notFound);
        if (!Arrays.asList(expectedTypes).contains(org.getType())
                || memberships.findByOrgIdAndUserId(orgId, currentUser.id()).isEmpty()) {
            throw notFound();
        }
        return org;
    }

    /** Whether one login is a member of both orgs, so a sale between them would be a person selling to themself. */
    @Transactional(readOnly = true)
    public boolean shareMember(UUID orgId, UUID otherOrgId) {
        Set<UUID> members = memberships.findByOrgId(orgId).stream().map(Membership::getUserId).collect(Collectors.toSet());
        return memberships.findByOrgId(otherOrgId).stream().anyMatch(m -> members.contains(m.getUserId()));
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Org not found");
    }
}

package com.pokemans.app.identity;

import java.util.Arrays;
import java.util.UUID;
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

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Org not found");
    }
}

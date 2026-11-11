package com.pokemans.app.identity;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Names and contact details other modules may show, for the people at either end of a deal. No
 * access checks: callers decide who may see whom.
 */
@Service
public class Directory {

    /** @param email the user's login email, or null if the identity provider didn't share one */
    public record Contact(String orgName, String email) {
    }

    private final OrgRepository orgs;
    private final AppUserRepository users;
    private final MembershipRepository memberships;

    Directory(OrgRepository orgs, AppUserRepository users, MembershipRepository memberships) {
        this.orgs = orgs;
        this.users = users;
        this.memberships = memberships;
    }

    /** Every org of the type, by name. */
    @Transactional(readOnly = true)
    public List<UUID> orgsOfType(OrgType type) {
        return orgs.findAllByOrderByNameAsc().stream().filter(o -> o.getType() == type).map(Org::getId).toList();
    }

    /** The users who are members of the org. */
    @Transactional(readOnly = true)
    public List<UUID> members(UUID orgId) {
        return memberships.findByOrgId(orgId).stream().map(Membership::getUserId).distinct().toList();
    }

    @Transactional(readOnly = true)
    public String orgName(UUID orgId) {
        return orgs.findById(orgId).map(Org::getName).orElse(null);
    }

    /** A user's login email, or null. */
    @Transactional(readOnly = true)
    public String email(UUID userId) {
        return users.findById(userId).map(AppUser::getEmail).orElse(null);
    }

    @Transactional(readOnly = true)
    public Contact contact(UUID orgId, UUID userId) {
        return new Contact(orgs.findById(orgId).map(Org::getName).orElse(null),
                users.findById(userId).map(AppUser::getEmail).orElse(null));
    }
}

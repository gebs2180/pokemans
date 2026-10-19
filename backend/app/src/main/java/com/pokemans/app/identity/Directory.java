package com.pokemans.app.identity;

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

    Directory(OrgRepository orgs, AppUserRepository users) {
        this.orgs = orgs;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public Contact contact(UUID orgId, UUID userId) {
        return new Contact(orgs.findById(orgId).map(Org::getName).orElse(null),
                users.findById(userId).map(AppUser::getEmail).orElse(null));
    }
}

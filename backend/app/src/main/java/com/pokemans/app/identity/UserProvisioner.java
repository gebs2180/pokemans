package com.pokemans.app.identity;

import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates a user on first login in its own transaction, so it also works when the caller is inside
 * a read-only transaction (e.g. GET /api/me on the very first request).
 */
@Component
class UserProvisioner {

    private final AppUserRepository users;

    UserProvisioner(AppUserRepository users) {
        this.users = users;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void createIfAbsent(String subject, String email) {
        users.insertIfAbsent(UUID.randomUUID(), subject, email);
    }
}

package com.pokemans.app.identity;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** The signed-in user for the current request, created in the database on first use. */
@Component
public class CurrentUser {

    private final AppUserRepository users;
    private final UserProvisioner provisioner;

    CurrentUser(AppUserRepository users, UserProvisioner provisioner) {
        this.users = users;
        this.provisioner = provisioner;
    }

    public AppUser get() {
        JwtAuthenticationToken token = token();
        String subject = token.getName();
        String email = token.getToken().getClaimAsString("email");
        AppUser user = users.findBySubject(subject).orElseGet(() -> {
            provisioner.createIfAbsent(subject, email);
            return users.findBySubject(subject).orElseThrow();
        });
        // The email is how the two sides of a deal reach each other, so it follows the login.
        if (email != null && !email.equals(user.getEmail())) {
            provisioner.updateEmail(user.getId(), email);
        }
        return user;
    }

    public UUID id() {
        return get().getId();
    }

    /** ADMIN comes only from the token, and only after an MFA login (see RoleClaimConverter). */
    public boolean isAdmin() {
        return token().getAuthorities().stream().anyMatch(a -> a.getAuthority().equals(Role.ADMIN.authority()));
    }

    private static JwtAuthenticationToken token() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            return jwt;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
}

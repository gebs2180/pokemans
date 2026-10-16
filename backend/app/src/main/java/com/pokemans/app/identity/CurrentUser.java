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
        return users.findBySubject(subject).orElseGet(() -> {
            provisioner.createIfAbsent(subject, token.getToken().getClaimAsString("email"));
            return users.findBySubject(subject).orElseThrow();
        });
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

package com.pokemans.app.security;

import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Who the caller is, as the API sees them. Lets the SPA (and you) check the auth wiring. */
@RestController
public class MeController {

    public record Me(String subject, String issuer, List<String> roles) {
    }

    @GetMapping("/api/me")
    public Me me(JwtAuthenticationToken auth) {
        List<String> roles = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring("ROLE_".length()))
                .sorted()
                .toList();
        Object issuer = auth.getToken().getClaims().get("iss");
        return new Me(auth.getName(), issuer == null ? null : issuer.toString(), roles);
    }
}

package com.pokemans.app.security;

import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Who the caller is, as the API sees them. Lets the SPA (and you) check the auth wiring. */
@RestController
public class MeController {

    private final SecurityProperties properties;

    public MeController(SecurityProperties properties) {
        this.properties = properties;
    }

    /**
     * @param roles        roles granted after filtering
     * @param claimedRoles roles as they arrived in the token's roles claim (empty if the claim is missing)
     * @param mfa          whether the token shows an MFA login
     */
    public record Me(String subject, String issuer, List<String> roles, List<String> claimedRoles, boolean mfa) {
    }

    @GetMapping("/api/me")
    public Me me(JwtAuthenticationToken auth) {
        Jwt jwt = auth.getToken();
        List<String> roles = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring("ROLE_".length()))
                .sorted()
                .toList();
        List<String> claimed = jwt.hasClaim(properties.rolesClaim()) ? jwt.getClaimAsStringList(properties.rolesClaim()) : null;
        Object issuer = jwt.getClaims().get("iss");
        return new Me(auth.getName(), issuer == null ? null : issuer.toString(), roles,
                claimed == null ? List.of() : claimed, properties.mfa(jwt));
    }
}

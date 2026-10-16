package com.pokemans.app.identity;

import java.util.Collection;
import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Reads ADMIN from the access token's roles claim, and only for an MFA login (see
 * {@link SecurityProperties#mfa}) unless that check is turned off. PARTNER and COLLECTOR in the
 * token are ignored: that access comes from org membership in the database (see {@link OrgAccess}).
 */
public class RoleClaimConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private final SecurityProperties properties;

    public RoleClaimConverter(SecurityProperties properties) {
        this.properties = properties;
    }

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        List<String> claimed = jwt.hasClaim(properties.rolesClaim())
                ? jwt.getClaimAsStringList(properties.rolesClaim())
                : null;
        boolean admin = claimed != null && claimed.stream()
                .map(Role::parse)
                .anyMatch(role -> role.orElse(null) == Role.ADMIN);
        if (admin && (properties.mfa(jwt) || !properties.requireAdminMfa())) {
            return List.of(new SimpleGrantedAuthority(Role.ADMIN.authority()));
        }
        return List.of();
    }
}

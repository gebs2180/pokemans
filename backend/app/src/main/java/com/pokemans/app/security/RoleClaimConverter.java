package com.pokemans.app.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Maps the roles claim of an access token to Spring authorities. Unknown roles are dropped, and
 * ADMIN requires an MFA login (see {@link SecurityProperties#mfa}) unless that check is turned off.
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
                : List.of();
        boolean mfa = properties.mfa(jwt);

        List<GrantedAuthority> authorities = new ArrayList<>();
        for (String value : claimed == null ? List.<String>of() : claimed) {
            Role.parse(value)
                    .filter(role -> role != Role.ADMIN || mfa || !properties.requireAdminMfa())
                    .map(role -> new SimpleGrantedAuthority(role.authority()))
                    .filter(authority -> !authorities.contains(authority))
                    .ifPresent(authorities::add);
        }
        return authorities;
    }
}

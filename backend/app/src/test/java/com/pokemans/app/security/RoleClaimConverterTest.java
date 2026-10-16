package com.pokemans.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class RoleClaimConverterTest {

    private static final String CLAIM = "https://pokemans.app/roles";
    private static final String MFA_CLAIM = "https://pokemans.app/mfa";

    private static Jwt jwt(List<String> roles, List<String> amr) {
        Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "RS256").subject("s")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (roles != null) {
            builder.claim(CLAIM, roles);
        }
        if (amr != null) {
            builder.claim("amr", amr);
        }
        return builder.build();
    }

    private static List<String> authorities(boolean requireMfa, List<String> roles, List<String> amr) {
        return new RoleClaimConverter(new SecurityProperties(CLAIM, MFA_CLAIM, requireMfa)).convert(jwt(roles, amr)).stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
    }

    @Test
    void mapsKnownRolesCaseInsensitivelyAndDropsUnknownOnes() {
        assertThat(authorities(true, List.of("partner", "COLLECTOR", "superuser", "Partner"), null))
                .containsExactly("ROLE_PARTNER", "ROLE_COLLECTOR");
    }

    @Test
    void adminNeedsMfaWhenRequired() {
        assertThat(authorities(true, List.of("ADMIN"), List.of("pwd"))).isEmpty();
        assertThat(authorities(true, List.of("ADMIN"), null)).isEmpty();
        assertThat(authorities(true, List.of("ADMIN"), List.of("pwd", "mfa"))).containsExactly("ROLE_ADMIN");
        assertThat(authorities(false, List.of("ADMIN"), null)).containsExactly("ROLE_ADMIN");
    }

    @Test
    void customMfaClaimCountsAsMfa() {
        Jwt token = Jwt.withTokenValue("t").header("alg", "RS256").subject("s")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                .claim(CLAIM, List.of("ADMIN")).claim(MFA_CLAIM, true).build();
        Jwt falseClaim = Jwt.withTokenValue("t").header("alg", "RS256").subject("s")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                .claim(CLAIM, List.of("ADMIN")).claim(MFA_CLAIM, false).build();
        RoleClaimConverter converter = new RoleClaimConverter(new SecurityProperties(CLAIM, MFA_CLAIM, true));

        assertThat(converter.convert(token)).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_ADMIN");
        assertThat(converter.convert(falseClaim)).isEmpty();
    }

    @Test
    void noRolesClaimMeansNoRoles() {
        assertThat(authorities(true, null, List.of("mfa"))).isEmpty();
    }
}

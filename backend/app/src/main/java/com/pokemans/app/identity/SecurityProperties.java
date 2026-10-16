package com.pokemans.app.identity;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Provider-agnostic settings for reading roles and MFA from the OIDC access token.
 *
 * @param rolesClaim      JWT claim holding the user's roles (a list of strings)
 * @param mfaClaim        custom boolean claim set by the provider when the login used MFA. Auth0 puts
 *                        {@code amr} only in the ID token, so a post-login Action sets this instead.
 * @param requireAdminMfa grant ADMIN only when the token shows an MFA login
 */
@ConfigurationProperties("pokemans.security")
public record SecurityProperties(
        @DefaultValue("https://pokemans.app/roles") String rolesClaim,
        @DefaultValue("https://pokemans.app/mfa") String mfaClaim,
        @DefaultValue("true") boolean requireAdminMfa) {

    /** True when {@code amr} contains {@code mfa} or the custom MFA claim is true. */
    public boolean mfa(Jwt jwt) {
        List<String> amr = jwt.hasClaim("amr") ? jwt.getClaimAsStringList("amr") : null;
        if (amr != null && amr.contains("mfa")) {
            return true;
        }
        return jwt.hasClaim(mfaClaim) && Boolean.TRUE.equals(jwt.getClaimAsBoolean(mfaClaim));
    }
}

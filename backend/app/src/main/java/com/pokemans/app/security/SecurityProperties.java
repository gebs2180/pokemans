package com.pokemans.app.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Provider-agnostic settings for reading roles from the OIDC access token.
 *
 * @param rolesClaim      JWT claim holding the user's roles (a list of strings)
 * @param requireAdminMfa grant ADMIN only when the token's {@code amr} claim includes {@code mfa}
 */
@ConfigurationProperties("pokemans.security")
public record SecurityProperties(
        @DefaultValue("https://pokemans.app/roles") String rolesClaim,
        @DefaultValue("true") boolean requireAdminMfa) {
}

package com.pokemans.app.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

/**
 * The API is an OAuth2 resource server: the SPA logs in with the OIDC provider and sends the access
 * token as a bearer token. Stateless, so no sessions and no CSRF tokens.
 *
 * <p>Set {@code SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI} to the provider's issuer URL to
 * turn on token validation. Without it the app still starts, but rejects every token (fail closed).
 * With an issuer, {@code SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES} is required too: without
 * an audience check any token from the same tenant would pass, ID tokens and other APIs' tokens included.
 */
@Configuration
@EnableConfigurationProperties({SecurityProperties.class, InviteProperties.class})
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    SecurityFilterChain api(HttpSecurity http, SecurityProperties properties, Environment env) throws Exception {
        requireAudienceWithIssuer(env);
        JwtAuthenticationConverter jwtConverter = new JwtAuthenticationConverter();
        jwtConverter.setJwtGrantedAuthoritiesConverter(new RoleClaimConverter(properties));

        http
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info", "/error").permitAll()
                        .requestMatchers("/api/admin/**").hasRole(Role.ADMIN.name())
                        // Org-scoped endpoints (/api/orgs/{orgId}/...) check membership in OrgAccess.
                        .requestMatchers("/api/**").authenticated()
                        // Opened up for the built SPA's static files once the app serves them.
                        .anyRequest().denyAll())
                .oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter)));
        return http.build();
    }

    /** Refuses to start with an issuer but no audience, rather than accept tokens meant for something else. */
    static void requireAudienceWithIssuer(Environment env) {
        boolean issuer = StringUtils.hasText(env.getProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri"))
                || StringUtils.hasText(env.getProperty("spring.security.oauth2.resourceserver.jwt.jwk-set-uri"));
        if (issuer && !StringUtils.hasText(env.getProperty("spring.security.oauth2.resourceserver.jwt.audiences"))
                && !StringUtils.hasText(env.getProperty("spring.security.oauth2.resourceserver.jwt.audiences[0]"))) {
            throw new IllegalStateException("An OIDC issuer is configured without an audience: set "
                    + "SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES to the API identifier");
        }
    }

    /** Used only when no issuer or JWK set is configured: rejects every token. */
    @Bean
    @Conditional(NoTokenIssuerConfigured.class)
    JwtDecoder rejectAllJwtDecoder() {
        log.warn("No OIDC issuer configured (SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI); all bearer tokens will be rejected");
        return token -> {
            throw new BadJwtException("No OIDC issuer configured");
        };
    }

    static class NoTokenIssuerConfigured implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            var env = context.getEnvironment();
            return !StringUtils.hasText(env.getProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri"))
                    && !StringUtils.hasText(env.getProperty("spring.security.oauth2.resourceserver.jwt.jwk-set-uri"));
        }
    }
}

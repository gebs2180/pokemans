package com.pokemans.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The production edge (deploy/Caddyfile) and images, as the security review of 2026-10-26 left them.
 * Read as text: Caddy itself isn't run here.
 */
class DeployConfigTest {

    /** Tests run in backend/app. */
    private static final Path REPO = Path.of("../..");

    @Test
    void theSiteSendsAContentSecurityPolicy() throws Exception {
        // The SPA keeps its Auth0 refresh tokens in localStorage, so injected script would take over the account,
        // the admin's included. The policy allows our own scripts only.
        String caddy = Files.readString(REPO.resolve("deploy/Caddyfile"));
        Matcher csp = Pattern.compile("Content-Security-Policy \"([^\"]+)\"").matcher(caddy);
        assertThat(csp.find()).as("a Content-Security-Policy header").isTrue();
        String policy = csp.group(1);
        assertThat(policy).contains("default-src 'self'", "script-src 'self'", "object-src 'none'", "base-uri 'self'",
                "frame-ancestors 'none'", "form-action 'self'");
        assertThat(policy).doesNotContain("unsafe-eval");
        assertThat(directive(policy, "script-src")).doesNotContain("unsafe-inline", "*", "data:");
        // Photos come straight from storage (presigned), the login from Auth0.
        assertThat(directive(policy, "img-src")).contains("{$PHOTO_ORIGIN}");
        assertThat(directive(policy, "connect-src")).contains("{$PHOTO_ORIGIN}", "https://{$AUTH0_DOMAIN}");
        assertThat(caddy).contains("Strict-Transport-Security", "X-Content-Type-Options nosniff");
    }

    @Test
    void onlyHealthIsReachableFromActuator() throws Exception {
        String caddy = Files.readString(REPO.resolve("deploy/Caddyfile"));
        assertThat(caddy).contains("handle /actuator/health {").contains("handle /actuator/* {\n\t\trespond 404");
    }

    @Test
    void theCaddyContainerGetsTheHostsThePolicyNames() throws Exception {
        String compose = Files.readString(REPO.resolve("deploy/compose.prod.yml"));
        String caddy = compose.substring(compose.indexOf("\n  caddy:"), compose.indexOf("\n  backup:"));
        assertThat(caddy).contains("PHOTO_ORIGIN:", "AUTH0_DOMAIN:");
    }

    @Test
    void theApiDoesNotRunAsRoot() throws Exception {
        String dockerfile = Files.readString(REPO.resolve("backend/Dockerfile"));
        String runtime = dockerfile.substring(dockerfile.lastIndexOf("FROM "));
        assertThat(runtime).containsPattern("(?m)^USER (?!root)\\w+");
    }

    private static String directive(String policy, String name) {
        for (String d : policy.split(";")) {
            if (d.strip().startsWith(name + " ")) {
                return d.strip();
            }
        }
        return "";
    }
}

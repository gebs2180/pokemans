package com.pokemans.app.identity;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.pokemans.app.TestcontainersConfiguration;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class SecurityConfigTest {

    private static final String ROLES = "https://pokemans.app/roles";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityProperties properties;

    /** A token run through the real role converter, as a validated provider token would be. */
    private JwtRequestPostProcessor token(List<String> roles, List<String> amr) {
        return jwt()
                .jwt(builder -> builder.subject("auth0|zach").issuer("https://example.test/").claim(ROLES, roles).claim("amr", amr))
                .authorities(new RoleClaimConverter(properties)::convert);
    }

    @Test
    void healthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void apiRequiresAToken() throws Exception {
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void fakeBearerTokensAreRejectedWithoutAnIssuer() throws Exception {
        mvc.perform(get("/api/me").header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meReturnsSubjectAndRoles() throws Exception {
        mvc.perform(get("/api/me").with(token(List.of("ADMIN", "partner"), List.of("mfa"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("auth0|zach"))
                .andExpect(jsonPath("$.issuer").value("https://example.test/"))
                .andExpect(jsonPath("$.roles[0]").value("ADMIN"))
                .andExpect(jsonPath("$.roles.length()").value(1))
                .andExpect(jsonPath("$.claimedRoles.length()").value(2))
                .andExpect(jsonPath("$.mfa").value(true))
                .andExpect(jsonPath("$.memberships.length()").value(0));
    }

    @Test
    void partnerCannotReachAdminApi() throws Exception {
        mvc.perform(get("/api/admin/anything").with(token(List.of("PARTNER"), List.of("pwd"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminWithoutMfaIsNotAdmin() throws Exception {
        mvc.perform(get("/api/admin/anything").with(token(List.of("ADMIN"), List.of("pwd"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminWithMfaReachesAdminApi() throws Exception {
        mvc.perform(get("/api/admin/orgs").with(token(List.of("ADMIN"), List.of("pwd", "mfa"))))
                .andExpect(status().isOk());
    }

    @Test
    void nonMemberCannotSeeAnOrg() throws Exception {
        mvc.perform(get("/api/orgs/00000000-0000-0000-0000-000000000000/batches").with(token(List.of(), List.of("pwd"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void nonApiPathsAreDenied() throws Exception {
        mvc.perform(get("/actuator/env").with(token(List.of("ADMIN"), List.of("mfa"))))
                .andExpect(status().isForbidden());
    }
}

package com.pokemans.app.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.pokemans.app.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The email on a deal is the only way buyer and seller reach each other. Auth0 access tokens carry none
 * until the post-login Action adds it, and anyone who logged in before that has an empty row.
 */
@SpringBootTest(properties = "pokemans.condition.worker.enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class ContactEmailTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AppUserRepository users;

    @Test
    void aTokenThatBringsAnEmailFillsItIn() throws Exception {
        String subject = "auth0|no-email-" + UUID.randomUUID();
        mvc.perform(get("/api/me").with(jwt().jwt(j -> j.subject(subject))));
        assertThat(users.findBySubject(subject).orElseThrow().getEmail()).isNull();

        mvc.perform(get("/api/me").with(jwt().jwt(j -> j.subject(subject).claim("email", "sam@example.com"))));
        assertThat(users.findBySubject(subject).orElseThrow().getEmail()).isEqualTo("sam@example.com");

        mvc.perform(get("/api/me").with(jwt().jwt(j -> j.subject(subject).claim("email", "sam@new.example"))));
        assertThat(users.findBySubject(subject).orElseThrow().getEmail()).isEqualTo("sam@new.example");

        // A token without one leaves what we have.
        mvc.perform(get("/api/me").with(jwt().jwt(j -> j.subject(subject))));
        assertThat(users.findBySubject(subject).orElseThrow().getEmail()).isEqualTo("sam@new.example");
    }
}

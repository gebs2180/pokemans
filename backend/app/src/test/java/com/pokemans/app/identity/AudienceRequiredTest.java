package com.pokemans.app.identity;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class AudienceRequiredTest {

    private static final String ISSUER = "spring.security.oauth2.resourceserver.jwt.issuer-uri";
    private static final String AUDIENCES = "spring.security.oauth2.resourceserver.jwt.audiences";

    @Test
    void anIssuerWithoutAnAudienceRefusesToStart() {
        MockEnvironment env = new MockEnvironment().withProperty(ISSUER, "https://tenant.example/");
        assertThatThrownBy(() -> SecurityConfig.requireAudienceWithIssuer(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUDIENCES");
    }

    @Test
    void anIssuerWithAnAudienceStarts() {
        MockEnvironment env = new MockEnvironment().withProperty(ISSUER, "https://tenant.example/")
                .withProperty(AUDIENCES, "https://api.pokemans.app");
        assertThatCode(() -> SecurityConfig.requireAudienceWithIssuer(env)).doesNotThrowAnyException();
    }

    @Test
    void noIssurerAtAllStillStartsAndRejectsEveryToken() {
        assertThatCode(() -> SecurityConfig.requireAudienceWithIssuer(new MockEnvironment())).doesNotThrowAnyException();
    }
}

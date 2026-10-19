package com.pokemans.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.DockerClientFactory;

/**
 * The flow tests skip themselves when Docker is missing, which would leave CI green with nothing run.
 * CI sets {@code POKEMANS_REQUIRE_DOCKER}, so a runner without Docker fails here instead.
 */
@EnabledIfEnvironmentVariable(named = "POKEMANS_REQUIRE_DOCKER", matches = "true")
class DockerRequiredTest {

    @Test
    void dockerIsAvailable() {
        assertThat(DockerClientFactory.instance().isDockerAvailable())
                .as("Docker is required here (POKEMANS_REQUIRE_DOCKER=true), or every Testcontainers test is skipped")
                .isTrue();
    }
}

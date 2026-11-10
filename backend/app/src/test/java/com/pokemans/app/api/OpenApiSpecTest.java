package com.pokemans.app.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pokemans.app.TestApi;
import com.pokemans.app.TestcontainersConfiguration;
import com.pokemans.app.identity.SecurityProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The web app's API types are generated from {@code web/openapi.json} (architecture review #32), so this
 * fails when the API has changed and the file hasn't. Run {@code ./gradlew test -Dopenapi.update=true} to
 * rewrite it, then {@code npm run api:types} in web/ (docs/api-types.md).
 */
@SpringBootTest(properties = {
        "pokemans.condition.worker.enabled=false",
        "springdoc.api-docs.enabled=true",
        // The paused lender and insurer side too, so the web app's valuation and report types are checked.
        "pokemans.collateral.enabled=true",
        // Under /api/admin, so the security rules already in place let only an admin read it.
        "springdoc.api-docs.path=/api/admin/openapi"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class OpenApiSpecTest {

    private static final Path SPEC = Path.of("../../web/openapi.json");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityProperties security;

    @Test
    void webOpenapiJsonIsTheApiAsItIs() throws Exception {
        TestApi api = new TestApi(mvc, security);
        String served = api.get("/api/admin/openapi", api.admin()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonMapper json = JsonMapper.builder()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .build();
        Map<String, Object> spec = json.readValue(served, new TypeReference<>() { });
        String text = json.writeValueAsString(spec).replace("\r\n", "\n") + "\n";

        if (Boolean.getBoolean("openapi.update") || !Files.exists(SPEC)) {
            Files.writeString(SPEC, text, StandardCharsets.UTF_8);
        }
        assertThat(text)
                .as("web/openapi.json is out of date: run ./gradlew test -Dopenapi.update=true, then npm run api:types in web/")
                .isEqualTo(Files.readString(SPEC, StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }
}

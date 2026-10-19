package com.pokemans.app.reporting;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pokemans.app.TestApi;
import com.pokemans.app.TestcontainersConfiguration;
import com.pokemans.app.identity.SecurityProperties;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/** With the lender and insurer side paused (the default), its routes don't exist, even for the admin. */
@SpringBootTest(properties = "pokemans.condition.worker.enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class CollateralSwitchedOffTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityProperties security;

    @Test
    void valuationAndReportRoutesAreNotRegistered() throws Exception {
        TestApi api = new TestApi(mvc, security);
        String id = UUID.randomUUID().toString();
        api.get("/api/admin/valuation/defaults", api.admin()).andExpect(status().isNotFound());
        api.postJson("/api/admin/orgs/" + id + "/collections/" + id + "/valuation-runs", "{}", api.admin()).andExpect(status().isNotFound());
        api.get("/api/admin/valuation-runs/" + id, api.admin()).andExpect(status().isNotFound());
        api.postJson("/api/admin/valuation-runs/" + id + "/reports", "{\"type\":\"LENDER\"}", api.admin()).andExpect(status().isNotFound());
        api.get("/api/orgs/" + id + "/collections/" + id + "/reports", api.admin()).andExpect(status().isNotFound());
    }
}

package com.pokemans.app.provenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pokemans.app.TestApi;
import com.pokemans.app.TestcontainersConfiguration;
import com.pokemans.app.identity.SecurityProperties;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Authenticity flags stay unresolved until an admin verifies the card as the identity it has, and
 * custody counts only when its latest record was checked. Both histories are kept.
 */
@SpringBootTest(properties = "pokemans.condition.worker.enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class ProvenanceFlowTest {

    /** A Japanese promo: always an authenticity risk. */
    private static final String JP_PROMO = "{\"provider\":\"MANUAL\",\"language\":\"ja\",\"edition\":\"UNLIMITED\",\"variant\":\"HOLO\","
            + "\"promo\":true,\"setName\":\"Pokemon Center Promo\",\"setReleaseDate\":\"2023-01-01\",\"number\":\"%s\",\"name\":\"Pikachu\"}";
    /** A common modern English card: no risk category. */
    private static final String COMMON = "{\"provider\":\"MANUAL\",\"language\":\"en\",\"edition\":\"UNLIMITED\",\"variant\":\"NORMAL\","
            + "\"setName\":\"Roaring Skies\",\"setReleaseDate\":\"2015-05-06\",\"number\":\"1/108\",\"name\":\"Ledyba\",\"rarity\":\"Common\"}";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityProperties security;

    @Autowired
    private Provenance provenance;

    @Autowired
    private JdbcTemplate jdbc;

    private TestApi api;
    private String orgId;
    private String cardId;
    private JwtRequestPostProcessor collector;

    @BeforeEach
    void setUp() throws Exception {
        api = new TestApi(mvc, security);
        String subject = "auth0|provenance-" + UUID.randomUUID();
        orgId = api.collectorOrg("Provenance " + UUID.randomUUID(), subject);
        collector = api.user(subject, List.of());
        cardId = api.card(orgId, "Promo Pikachu", collector);
    }

    @Test
    void aFlaggedCardIsUnresolvedUntilVerifiedAsTheIdentityItHas() throws Exception {
        // Nothing known yet: unresolved, and no custody.
        read().andExpect(jsonPath("$.authFlagUnresolved").value(true))
                .andExpect(jsonPath("$.authState").value("NOT_IDENTIFIED"))
                .andExpect(jsonPath("$.custodyVerified").value(false))
                .andExpect(jsonPath("$.custodyState").value("NOT_RECORDED"));
        verify("VERIFIED_AUTHENTIC", "IN_HAND_INSPECTION").andExpect(status().isConflict());

        identify(JP_PROMO.formatted("001/SV-P"));
        read().andExpect(jsonPath("$.authState").value("AWAITING_VERIFICATION"))
                .andExpect(jsonPath("$.authenticityRisk").value("JAPANESE_PROMO"))
                .andExpect(jsonPath("$.authFlagUnresolved").value(true));

        // Members can read but not verify; photos can't clear a flag.
        api.postJson(adminBase() + "/authenticity-verifications",
                "{\"status\":\"VERIFIED_AUTHENTIC\",\"method\":\"IN_HAND_INSPECTION\"}", collector).andExpect(status().isForbidden());
        verify("VERIFIED_AUTHENTIC", "PHOTO_REVIEW").andExpect(status().isBadRequest());

        verify("VERIFIED_AUTHENTIC", "IN_HAND_INSPECTION")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.authState").value("VERIFIED_AUTHENTIC"))
                .andExpect(jsonPath("$.authFlagUnresolved").value(false))
                .andExpect(jsonPath("$.verifications[0].method").value("IN_HAND_INSPECTION"))
                .andExpect(jsonPath("$.verifications[0].verifiedBy").isNotEmpty())
                .andExpect(jsonPath("$.verifications[0].forCurrentIdentity").value(true));
        assertThat(provenance.status(UUID.fromString(cardId)).authFlagUnresolved()).isFalse();

        // Confirming the same identity again keeps the verification.
        identify(JP_PROMO.formatted("001/SV-P"));
        read().andExpect(jsonPath("$.authState").value("VERIFIED_AUTHENTIC"));

        // Re-identified as a different card: the verification was about the other one.
        identify(JP_PROMO.formatted("002/SV-P"));
        read().andExpect(jsonPath("$.authState").value("AWAITING_VERIFICATION"))
                .andExpect(jsonPath("$.authFlagUnresolved").value(true))
                .andExpect(jsonPath("$.verifications.length()").value(1))
                .andExpect(jsonPath("$.verifications[0].forCurrentIdentity").value(false));
        // And back again: the card was something else in between, so it needs checking again.
        identify(JP_PROMO.formatted("001/SV-P"));
        read().andExpect(jsonPath("$.authState").value("AWAITING_VERIFICATION"));

        // A doubt sticks to the physical card, even once it's identified as a card with no risk category.
        verify("SUSPECTED_COUNTERFEIT", "PHOTO_REVIEW").andExpect(jsonPath("$.authState").value("SUSPECTED_COUNTERFEIT"));
        identify(COMMON);
        read().andExpect(jsonPath("$.authenticityRisk").value("NONE"))
                .andExpect(jsonPath("$.authState").value("SUSPECTED_COUNTERFEIT"))
                .andExpect(jsonPath("$.authFlagUnresolved").value(true));
        verify("VERIFIED_AUTHENTIC", "GRADER_SLAB")
                .andExpect(jsonPath("$.authState").value("VERIFIED_AUTHENTIC"))
                .andExpect(jsonPath("$.verifications.length()").value(3))
                .andExpect(jsonPath("$.verifications[0].method").value("GRADER_SLAB"))
                .andExpect(jsonPath("$.verifications[1].status").value("SUSPECTED_COUNTERFEIT"));

        // History is evidence: the database refuses to change it.
        assertThatThrownBy(() -> jdbc.update("update authenticity_verification set status = 'COUNTERFEIT' where card_item_id = ?",
                UUID.fromString(cardId))).hasMessageContaining("append-only");
    }

    @Test
    void aCardWithNoRiskCategoryIsResolvedOnceIdentified() throws Exception {
        identify(COMMON);
        read().andExpect(jsonPath("$.authState").value("NOT_FLAGGED"))
                .andExpect(jsonPath("$.authFlagUnresolved").value(false));
        verify("COUNTERFEIT", "THIRD_PARTY_AUTHENTICATION").andExpect(jsonPath("$.authState").value("COUNTERFEIT"))
                .andExpect(jsonPath("$.authFlagUnresolved").value(true));
    }

    @Test
    void custodyIsVerifiedOnlyWhileItsLatestRecordWasChecked() throws Exception {
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        custody("OWNER", "Sam", "Home safe", today, null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.custodyState").value("UNVERIFIED"))
                .andExpect(jsonPath("$.custodyVerified").value(false))
                .andExpect(jsonPath("$.custody.verifiedBy").isEmpty());

        custody("VAULT", "Card Vault Co", "Unit 4", today, "VAULT_STATEMENT")
                .andExpect(jsonPath("$.custodyState").value("VERIFIED"))
                .andExpect(jsonPath("$.custodyVerified").value(true))
                .andExpect(jsonPath("$.custody.verificationMethod").value("VAULT_STATEMENT"))
                .andExpect(jsonPath("$.custody.verifiedBy").isNotEmpty());
        assertThat(provenance.status(UUID.fromString(cardId)).custodyVerified()).isTrue();

        // Sent to a grader: a new holder nobody has checked yet.
        custody("GRADER", "PSA", "Santa Ana, CA", today, null)
                .andExpect(jsonPath("$.custodyVerified").value(false))
                .andExpect(jsonPath("$.custody.holderType").value("GRADER"))
                .andExpect(jsonPath("$.custodyHistory.length()").value(3))
                .andExpect(jsonPath("$.custodyHistory[1].holderName").value("Card Vault Co"));
        assertThat(provenance.status(UUID.fromString(cardId)).custodyVerified()).isFalse();

        custody("OWNER", "Sam", "Home", LocalDate.now(ZoneOffset.UTC).plusDays(5).toString(), null).andExpect(status().isBadRequest());
        custody("OWNER", " ", "Home", today, null).andExpect(status().isBadRequest());
        api.postJson(adminBase() + "/custody-records", custodyJson("OWNER", "Sam", "Home", today, "IN_PERSON"), collector)
                .andExpect(status().isForbidden());

        // Members read it; outsiders and other orgs' paths get nothing.
        api.get("/api/orgs/" + orgId + "/cards/" + cardId + "/provenance", collector)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.custody.holderName").value("PSA"));
        api.get("/api/orgs/" + orgId + "/cards/" + cardId + "/provenance", api.user("auth0|provenance-outsider", List.of()))
                .andExpect(status().isNotFound());
        String otherOrg = api.collectorOrg("Other " + UUID.randomUUID(), "auth0|provenance-other-" + UUID.randomUUID());
        api.postJson("/api/admin/orgs/" + otherOrg + "/cards/" + cardId + "/custody-records",
                custodyJson("OWNER", "Sam", "Home", today, null), api.admin()).andExpect(status().isNotFound());

        assertThatThrownBy(() -> jdbc.update("update custody_record set verified = true where card_item_id = ?",
                UUID.fromString(cardId))).hasMessageContaining("append-only");
    }

    @Test
    void cardsWithNothingRecordedAreNotEligible() {
        UUID unknown = UUID.randomUUID();
        Map<UUID, Provenance.Status> status = provenance.status(List.of(unknown));
        assertThat(status.get(unknown).authFlagUnresolved()).isTrue();
        assertThat(status.get(unknown).custodyVerified()).isFalse();
    }

    private String adminBase() {
        return "/api/admin/orgs/" + orgId + "/cards/" + cardId;
    }

    private ResultActions read() throws Exception {
        return api.get("/api/orgs/" + orgId + "/cards/" + cardId + "/provenance", api.admin()).andExpect(status().isOk());
    }

    private void identify(String json) throws Exception {
        api.postJson(adminBase() + "/identifications", json, api.admin()).andExpect(status().isCreated());
    }

    private ResultActions verify(String finding, String method) throws Exception {
        return api.postJson(adminBase() + "/authenticity-verifications",
                "{\"status\":\"" + finding + "\",\"method\":\"" + method + "\",\"note\":\"checked\"}", api.admin());
    }

    private ResultActions custody(String holderType, String holder, String location, String since, String method) throws Exception {
        return api.postJson(adminBase() + "/custody-records", custodyJson(holderType, holder, location, since, method), api.admin());
    }

    private static String custodyJson(String holderType, String holder, String location, String since, String method) {
        return "{\"holderType\":\"" + holderType + "\",\"holderName\":\"" + holder + "\",\"location\":\"" + location
                + "\",\"heldSince\":\"" + since + "\"" + (method == null ? "" : ",\"verificationMethod\":\"" + method + "\"") + "}";
    }
}

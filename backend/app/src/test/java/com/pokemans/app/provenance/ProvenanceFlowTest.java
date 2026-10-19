package com.pokemans.app.provenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pokemans.app.TestApi;
import com.pokemans.app.TestcontainersConfiguration;
import com.pokemans.app.identity.SecurityProperties;
import com.pokemans.app.intake.TestImages;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
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
 * Authenticity flags stay unresolved until an admin verifies the card as the identity it has, by a
 * method its risk category allows, and custody counts only while its latest record's check is
 * recent. Both histories are kept.
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

    /** A recent top-rarity card: an authenticity risk, but not a high one. */
    private static final String CHASE = "{\"provider\":\"MANUAL\",\"language\":\"en\",\"edition\":\"UNLIMITED\","
            + "\"variant\":\"HOLO\",\"setName\":\"Recent Set\",\"setReleaseDate\":\""
            + LocalDate.now(ZoneOffset.UTC).minusMonths(6) + "\",\"number\":\"199/165\",\"name\":\"Charizard ex\","
            + "\"rarity\":\"Special Illustration Rare\"}";

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

        // Members can read but not verify; photos can't clear a high-risk card.
        api.postJson(adminBase() + "/authenticity-verifications",
                "{\"status\":\"VERIFIED_AUTHENTIC\",\"method\":\"IN_HAND_INSPECTION\"}", collector).andExpect(status().isForbidden());
        verify("VERIFIED_AUTHENTIC", "PHOTO_REVIEW").andExpect(status().isBadRequest());
        read().andExpect(jsonPath("$.verifyMethods.length()").value(3))
                .andExpect(jsonPath("$.verifyMethods[?(@ == 'PHOTO_REVIEW')]").isEmpty());

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

        // Any method can raise a doubt. Like a verification, it was about the identity the card had then.
        verify("SUSPECTED_COUNTERFEIT", "PHOTO_REVIEW", photo("FRONT", TestImages.goodFront()))
                .andExpect(jsonPath("$.authState").value("SUSPECTED_COUNTERFEIT"))
                .andExpect(jsonPath("$.authFlagUnresolved").value(true))
                .andExpect(jsonPath("$.verifications[0].photoIds.length()").value(1));
        identify(COMMON);
        read().andExpect(jsonPath("$.authenticityRisk").value("NONE"))
                .andExpect(jsonPath("$.authState").value("NOT_FLAGGED"))
                .andExpect(jsonPath("$.authFlagUnresolved").value(false));
        identify(JP_PROMO.formatted("001/SV-P"));
        read().andExpect(jsonPath("$.authState").value("AWAITING_VERIFICATION"));
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
    void aRecentChaseCardCanBeVerifiedFromAPassingCloseUp() throws Exception {
        identify(CHASE);
        read().andExpect(jsonPath("$.authenticityRisk").value("RECENT_CHASE"))
                .andExpect(jsonPath("$.authState").value("AWAITING_VERIFICATION"))
                .andExpect(jsonPath("$.verifyMethods.length()").value(4));
        String front = photo("FRONT", TestImages.goodFront());
        // A photo review names its photos, all of them this card's, with a close-up among them.
        verify("VERIFIED_AUTHENTIC", "PHOTO_REVIEW").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Name the photos")));
        verify("VERIFIED_AUTHENTIC", "PHOTO_REVIEW", front).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("close-up")));
        verify("VERIFIED_AUTHENTIC", "PHOTO_REVIEW", front, UUID.randomUUID().toString()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("passing photos")));

        String closeUp = photo("DETAIL", TestImages.goodBack());
        verify("VERIFIED_AUTHENTIC", "PHOTO_REVIEW", front, closeUp).andExpect(status().isCreated())
                .andExpect(jsonPath("$.authState").value("VERIFIED_AUTHENTIC"))
                .andExpect(jsonPath("$.verifications[0].photoIds.length()").value(2))
                .andExpect(jsonPath("$.verifications[0].photoIds", org.hamcrest.Matchers.containsInAnyOrder(front, closeUp)));
        assertThatThrownBy(() -> jdbc.update("update authenticity_verification_photo set photo_id = ? where photo_id = ?",
                UUID.fromString(front), UUID.fromString(closeUp))).hasMessageContaining("append-only");
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
                .andExpect(jsonPath("$.custody.verifiedBy").isNotEmpty())
                .andExpect(jsonPath("$.custodyVerifiedUntil").isNotEmpty());
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

        // Members record custody, but only an admin can record it as checked.
        api.postJson(memberBase() + "/custody-records", custodyJson("OWNER", "Sam", "Home", today, "IN_PERSON"), collector)
                .andExpect(status().isForbidden());
        api.postJson(memberBase() + "/custody-records", custodyJson("OWNER", "Sam", "Home safe", today, null), collector)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.custodyState").value("UNVERIFIED"))
                .andExpect(jsonPath("$.custody.holderName").value("Sam"));

        // Members read it; outsiders and other orgs' paths get nothing.
        api.get("/api/orgs/" + orgId + "/cards/" + cardId + "/provenance", collector)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.custody.holderName").value("Sam"));
        api.get("/api/orgs/" + orgId + "/cards/" + cardId + "/provenance", api.user("auth0|provenance-outsider", List.of()))
                .andExpect(status().isNotFound());
        String otherOrg = api.collectorOrg("Other " + UUID.randomUUID(), "auth0|provenance-other-" + UUID.randomUUID());
        api.postJson("/api/orgs/" + otherOrg + "/cards/" + cardId + "/custody-records",
                custodyJson("OWNER", "Sam", "Home", today, null), api.admin()).andExpect(status().isNotFound());

        assertThatThrownBy(() -> jdbc.update("update custody_record set verified = true where card_item_id = ?",
                UUID.fromString(cardId))).hasMessageContaining("append-only");
    }

    @Test
    void aCustodyCheckLapsesAfterNinetyDays() throws Exception {
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        custody("VAULT", "Card Vault Co", "Unit 4", today, "VAULT_STATEMENT").andExpect(jsonPath("$.custodyState").value("VERIFIED"));
        // The same check made 91 days ago instead. The API stamps rows with the current time, and rows
        // can't be updated, so the old one is written directly in its place.
        UUID card = UUID.fromString(cardId);
        UUID admin = jdbc.queryForObject("select verified_by from custody_record where card_item_id = ?", UUID.class, card);
        jdbc.update("delete from custody_record where card_item_id = ?", card);
        jdbc.update("""
                insert into custody_record (id, org_id, card_item_id, holder_type, holder_name, location, held_since, verified,
                    verification_method, verified_by, recorded_by, created_at)
                values (?, ?, ?, 'VAULT', 'Card Vault Co', 'Unit 4', ?, true, 'VAULT_STATEMENT', ?, ?, now() - interval '91 days')""",
                UUID.randomUUID(), UUID.fromString(orgId), card, LocalDate.now(ZoneOffset.UTC).minusDays(100), admin, admin);

        read().andExpect(jsonPath("$.custodyState").value("LAPSED"))
                .andExpect(jsonPath("$.custodyVerified").value(false))
                .andExpect(jsonPath("$.custodyVerifiedUntil").isEmpty());
        Provenance.Status status = provenance.status(UUID.fromString(cardId));
        assertThat(status.custodyVerified()).isFalse();
        assertThat(status.custodyState()).isEqualTo(Provenance.CustodyState.LAPSED);
    }

    @Test
    void cardsWithNothingRecordedAreNotEligible() {
        UUID unknown = UUID.randomUUID();
        Map<UUID, Provenance.Status> status = provenance.status(List.of(unknown));
        assertThat(status.get(unknown).authFlagUnresolved()).isTrue();
        assertThat(status.get(unknown).custodyVerified()).isFalse();
    }

    private String memberBase() {
        return "/api/orgs/" + orgId + "/cards/" + cardId;
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

    private ResultActions verify(String finding, String method, String... photoIds) throws Exception {
        String photos = Arrays.stream(photoIds).map(id -> "\"" + id + "\"").collect(Collectors.joining(","));
        return api.postJson(adminBase() + "/authenticity-verifications", "{\"status\":\"" + finding + "\",\"method\":\"" + method
                + "\",\"note\":\"checked\",\"photoIds\":[" + photos + "]}", api.admin());
    }

    /** Uploads a passing photo of the card and returns its id. */
    private String photo(String side, byte[] png) throws Exception {
        return TestApi.read(api.uploadPhoto(orgId, cardId, side, png, collector).andExpect(jsonPath("$.status").value("PASSED")), "$.id");
    }

    private ResultActions custody(String holderType, String holder, String location, String since, String method) throws Exception {
        return api.postJson(memberBase() + "/custody-records", custodyJson(holderType, holder, location, since, method), api.admin());
    }

    private static String custodyJson(String holderType, String holder, String location, String since, String method) {
        return "{\"holderType\":\"" + holderType + "\",\"holderName\":\"" + holder + "\",\"location\":\"" + location
                + "\",\"heldSince\":\"" + since + "\"" + (method == null ? "" : ",\"verificationMethod\":\"" + method + "\"") + "}";
    }
}

package com.pokemans.app.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The terms texts live in resources/terms (review finding 57). Members accepted each version by its hash, so a text
 * that changes without a new version would quietly invalidate every acceptance of it.
 */
class TermsTextsTest {

    /** The hashes members accepted. A new text gets a new version, and a new line here. */
    private static final Map<String, String> ACCEPTED = Map.of(
            "seller-2026-11-12", "c2d9e2a6dc2f539ba145ceb051b4215bfc836843ac9ef7fa6e0d418445dd6f0b",
            "contributor-2026-11-12", "106cb0d79d6258a301081545c144dd4e9d56097dcfb6c6a7b8599906a94a42cc",
            "buyer-firm-2026-11-12", "93bf02523edd5dae4e194b665cc3043dfd0e0bedf1faa0583730879918fbb57e");

    @Test
    void eachVersionsTextIsTheOneMembersAccepted() {
        for (OrgType type : Consents.TERMS_ORGS) {
            Consents.Terms terms = Consents.termsFor(type).orElseThrow();
            assertThat(terms.sha256()).as(terms.version() + ": a changed text needs a new version")
                    .isEqualTo(ACCEPTED.get(terms.version()));
            assertThat(terms.sha256()).isEqualTo(Consents.sha256(terms.text()));
        }
    }

    @Test
    void theTextsReadAsWritten() {
        assertThat(Consents.SELLER_TERMS_TEXT).startsWith("By posting a card for sale here you agree that:\n\n1. Each card")
                .endsWith("\n\nAsk Zach if anything here is unclear.");
        assertThat(Consents.CONTRIBUTOR_TERMS_TEXT).contains("you also agree that:\n\n1. Each card you post")
                .endsWith(Consents.SELLER_TERMS_BODY + "\n\nAsk Zach if anything here is unclear.");
        assertThat(Consents.BUYER_TERMS_TEXT).startsWith("By accepting an instant offer").doesNotContain("\r")
                .doesNotEndWith("\n");
    }

    @Test
    void aMissingTextFails() {
        assertThatThrownBy(() -> Consents.text("seller-1999-01-01")).isInstanceOf(IllegalStateException.class);
    }
}

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
            "seller-2026-11-01", "8f8566fd573bf57051d19b21493d8ac161cfde0809c32510b27ce33bfed914cf",
            "contributor-2026-11-01", "ed5b002960e5a9efaf20c1b4bb900a010c1588674437da29da9d159bd74a6335",
            "buyer-firm-2026-11-01", "9aa63f25d016621a0e5a8f85dc951874b15d3d9735c68028b5d744f6f616e0a3");

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

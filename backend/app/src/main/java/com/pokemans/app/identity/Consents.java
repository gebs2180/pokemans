package com.pokemans.app.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Terms an org's members accept before taking part: data-use terms for contributors, and the terms
 * buyers commit to when they take up an instant offer. Who accepted which version is kept. A new
 * version of a text needs a new version string: acceptances are tied to the exact text by its hash.
 */
@Service
public class Consents {

    /** The terms a contributor accepts before sharing photos and grades. */
    public record Terms(String version, String text, String sha256) {
    }

    public static final String CONTRIBUTOR_TERMS_VERSION = "contributor-2026-10-16";

    static final String CONTRIBUTOR_TERMS_TEXT = """
            By sharing photos and grading results here you agree that:

            1. You took the photos, or have the right to share them.
            2. We store the photos, the grading company, cert number and grade, and when and by whom they were shared.
            3. We use them to build, test and calibrate our own card condition models and valuation methods, \
            including commercially. We may send the photos to pre-grading services we use, under their terms.
            4. We don't publish your photos or show them to other users, and never sell them. Results built from \
            many cards (for example accuracy figures) may be shared without your photos or details.
            5. You can ask us to delete what you shared; we will, except where it is already part of a model or \
            figure that has been trained or published.

            You get a free collection report in return. Ask Zach if anything here is unclear.""";

    private static final Terms CONTRIBUTOR_TERMS = new Terms(CONTRIBUTOR_TERMS_VERSION, CONTRIBUTOR_TERMS_TEXT, sha256(CONTRIBUTOR_TERMS_TEXT));

    /** Firm offers (2026-10-19): one price, no grade settled on arrival. */
    public static final String BUYER_TERMS_VERSION = "buyer-firm-2026-10-19";

    /** Draft for Zach to review: plain language, not reviewed legal wording. */
    static final String BUYER_TERMS_TEXT = """
            By accepting an instant offer here you agree that:

            1. Accepting is a commitment to buy the card at the offer price. The price is fixed: it doesn't \
            change with the grade you think the card is when it arrives.
            2. You pay the seller the offer price within 7 days of the card arriving, if it is genuine, is \
            the card listed and matches its photos. Otherwise you send it back, at your cost, and record why.
            3. We buy an insured, prepaid shipping label for the card when you accept, from the seller to the \
            address you gave us, and bill you for it (postage and insurance) each month.
            4. You record in the app when the card arrives and what you paid, or why you returned it. We \
            keep these records and use them to check and improve our offers.
            5. You use the seller's contact details only for this sale.
            6. We are not a party to the sale. We don't hold cards or money, and payment is between you and \
            the seller. A posted card can be taken by another buyer first.

            Ask Zach if anything here is unclear.""";

    private static final Terms BUYER_TERMS = new Terms(BUYER_TERMS_VERSION, BUYER_TERMS_TEXT, sha256(BUYER_TERMS_TEXT));

    private final ConsentRecordRepository records;
    private final OrgAccess access;
    private final CurrentUser currentUser;

    Consents(ConsentRecordRepository records, OrgAccess access, CurrentUser currentUser) {
        this.records = records;
        this.access = access;
        this.currentUser = currentUser;
    }

    public Terms contributorTerms() {
        return CONTRIBUTOR_TERMS;
    }

    /** The terms members of this kind of org accept, if it has any. */
    public static Optional<Terms> termsFor(OrgType type) {
        return switch (type) {
            case CONTRIBUTOR -> Optional.of(CONTRIBUTOR_TERMS);
            case BUYER -> Optional.of(BUYER_TERMS);
            case PARTNER, COLLECTOR -> Optional.empty();
        };
    }

    /** When the current user accepted the current terms for this org, if they did. */
    @Transactional(readOnly = true)
    public Optional<Instant> acceptedAt(UUID orgId) {
        Optional<Terms> terms = termsFor(access.require(orgId, OrgType.values()).getType());
        return terms.flatMap(t -> records.findFirstByOrgIdAndUserIdAndTermsVersionOrderByAcceptedAtDesc(orgId, currentUser.id(), t.version()))
                .map(ConsentRecord::getAcceptedAt);
    }

    /**
     * 403 unless the current user accepted the current terms for this org. The admin is exempt:
     * acting on a member's behalf happens after they agreed in writing.
     */
    @Transactional(readOnly = true)
    public void requireAccepted(UUID orgId) {
        if (!currentUser.isAdmin() && acceptedAt(orgId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Accept the data-use terms first");
        }
    }

    /** Records acceptance of the version the user was shown; a stale version is refused. */
    @Transactional
    public Instant accept(UUID orgId, String version) {
        Terms terms = termsFor(access.require(orgId, OrgType.CONTRIBUTOR, OrgType.BUYER).getType()).orElseThrow();
        if (!terms.version().equals(version)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The terms have changed; reload and read them again");
        }
        return records.save(new ConsentRecord(orgId, currentUser.id(), version, terms.sha256())).getAcceptedAt();
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

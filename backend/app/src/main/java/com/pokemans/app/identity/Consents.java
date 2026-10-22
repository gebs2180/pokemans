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
 * Terms an org's members accept before taking part: seller terms for collectors, data-use terms
 * (plus the seller terms) for contributors, and the terms buyers commit to when they take up an
 * instant offer. Who accepted which version is kept. A new version of a text needs a new version
 * string: acceptances are tied to the exact text by its hash, and an older acceptance no longer counts.
 * Every text is a plain-language draft for Zach, not reviewed legal wording.
 */
@Service
public class Consents {

    /** The terms an org's members accept before taking part. */
    public record Terms(String version, String text, String sha256) {
    }

    /** What a seller commits to by posting a card (2026-10-21). Shared by collectors and contributors. */
    static final String SELLER_TERMS_BODY = """
            1. Each card you post is yours to sell, genuine, and the card you listed, as its photos show it. \
            You tell us about anything the photos don't show, such as a crease, writing, or a trimmed or altered card.
            2. While a card is posted you don't sell it anywhere else. You can withdraw it until a buyer accepts. \
            Once a buyer accepts, the sale is firm at the offer price for both of you, whatever grade the card later gets.
            3. You ship the card within 3 days of its prepaid label showing in the app, with that label, in a \
            sleeve and toploader in a bubble mailer. Don't ship before the label shows. If you can't ship, tell us \
            and we cancel the deal.
            4. The buyer pays you the offer price, off the app, within 7 days of the card arriving as shown. They \
            can send it back only if it isn't genuine, isn't the listed card, or doesn't match its photos.
            5. If a parcel is lost or damaged on the way, we claim on the label's insurance and pass what the \
            insurer pays to you. The buyer owes nothing for a card that didn't arrive as shown.
            6. Buyers see the photos of the cards you post. We store your photos, your cards' details and what \
            happens to them (the offer, the sale, and any grade a card later gets), and use them to build and check \
            our condition models and offers, including commercially. We may send the photos to pre-grading \
            services we use, under their terms. We never sell your photos.
            7. After a sale each side sees the other's name and email, and uses them only for that sale. Buyers \
            never see your address, except as the return address on the parcel. During the test you don't sell to \
            a buyer you met here outside the app.
            8. We are not a party to the sale and don't hold cards or money. If buyer and seller disagree about \
            whether a card matches its photos, Zach decides from the photos and what each of you says. We can void \
            an offer or cancel a deal that went wrong; each is recorded with who, when and why.
            9. This is a test. Offers can be wrong and the app can stop working or be paused. Offers go up to $500 \
            for now. Breaking these terms can end your part in the test.""";

    public static final String SELLER_TERMS_VERSION = "seller-2026-10-21";

    static final String SELLER_TERMS_TEXT = "By posting a card for sale here you agree that:\n\n" + SELLER_TERMS_BODY
            + "\n\nAsk Zach if anything here is unclear.";

    private static final Terms SELLER_TERMS = new Terms(SELLER_TERMS_VERSION, SELLER_TERMS_TEXT, sha256(SELLER_TERMS_TEXT));

    /** Contributors can sell too, so their terms carry the seller terms (2026-10-21). */
    public static final String CONTRIBUTOR_TERMS_VERSION = "contributor-2026-10-21";

    static final String CONTRIBUTOR_TERMS_TEXT = """
            By sharing photos and grading results here you agree that:

            1. You took the photos, or have the right to share them.
            2. We store the photos, the grading company, cert number and grade, and when and by whom they were shared.
            3. We use them to build, test and calibrate our own card condition models and valuation methods, \
            including commercially. We may send the photos to pre-grading services we use, under their terms.
            4. We don't publish your photos or show them to other users, except the photos of a card you post for \
            sale, which buyers see. We never sell them. Results built from many cards (for example accuracy \
            figures) may be shared without your photos or details.
            5. You can ask us to delete what you shared; we will, except where it is already part of a model or \
            figure that has been trained or published.

            You get a free collection report in return.

            If you post a card for sale here, you also agree that:

            """ + SELLER_TERMS_BODY + "\n\nAsk Zach if anything here is unclear.";

    private static final Terms CONTRIBUTOR_TERMS = new Terms(CONTRIBUTOR_TERMS_VERSION, CONTRIBUTOR_TERMS_TEXT, sha256(CONTRIBUTOR_TERMS_TEXT));

    /**
     * Firm offers: one price, no grade settled on arrival. 2026-10-21 adds transit loss, cancelled
     * deals, cards subject to authentication, disputes, non-payment and dealing around the app.
     */
    public static final String BUYER_TERMS_VERSION = "buyer-firm-2026-10-21";

    static final String BUYER_TERMS_TEXT = """
            By accepting an instant offer here you agree that:

            1. Accepting is a commitment to buy the card at the offer price. The price is fixed: it doesn't \
            change with the grade you think the card is when it arrives, or the grade it later gets.
            2. You pay the seller the offer price, off the app, within 7 days of the card arriving, if it is \
            genuine, is the card listed and matches the photos you saw when you accepted. Otherwise you send it \
            back within those 7 days, tracked and at your cost, and record why. Those are the only reasons to send \
            a card back.
            3. A card marked "not yet authenticated" is offered subject to authentication in hand: check it when \
            it arrives, and send it back as not genuine if it isn't.
            4. We buy an insured, prepaid shipping label for the card when you accept, from the seller to the \
            address you gave us, and bill you for it (postage and insurance) each month. If we cancel a deal \
            before the card arrives, its label isn't billed to you.
            5. If a parcel is lost or damaged on the way, you owe nothing for it. We claim on the label's \
            insurance and pass what the insurer pays to the seller.
            6. You record in the app when the card arrives and what you paid, or why you sent it back. We keep \
            these records and use them to check and improve our offers.
            7. After a sale each side sees the other's name and email, and uses them only for that sale. During \
            the test you don't buy from a seller you met here outside the app.
            8. We are not a party to the sale and don't hold cards or money. If buyer and seller disagree about \
            whether a card matches its photos, Zach decides from the photos and what each of you says. We can void \
            an offer or cancel a deal that went wrong; each is recorded with who, when and why. A posted card can \
            be taken by another buyer first.
            9. This is a test. Offers can be wrong and the app can stop working or be paused. Not paying for, or \
            not sending back, a card within 7 days of it arriving ends your part in the test, and we tell the seller.

            Ask Zach if anything here is unclear.""";

    private static final Terms BUYER_TERMS = new Terms(BUYER_TERMS_VERSION, BUYER_TERMS_TEXT, sha256(BUYER_TERMS_TEXT));

    /** The kinds of org whose members accept terms. */
    static final OrgType[] TERMS_ORGS = {OrgType.COLLECTOR, OrgType.CONTRIBUTOR, OrgType.BUYER};

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
            case COLLECTOR -> Optional.of(SELLER_TERMS);
            case PARTNER -> Optional.empty();
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
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Accept the terms on the org page first");
        }
    }

    /** Records acceptance of the version the user was shown; a stale version is refused. */
    @Transactional
    public Instant accept(UUID orgId, String version) {
        Terms terms = termsFor(access.require(orgId, TERMS_ORGS).getType()).orElseThrow();
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

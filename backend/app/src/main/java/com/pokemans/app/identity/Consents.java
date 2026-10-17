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
 * Data-use terms for contributors, and who accepted which version. A new version of the text needs
 * a new version string: acceptances are tied to the exact text by its hash.
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

    /** When the current user accepted the current contributor terms for this org, if they did. */
    @Transactional(readOnly = true)
    public Optional<Instant> acceptedAt(UUID orgId) {
        return records.findFirstByOrgIdAndUserIdAndTermsVersionOrderByAcceptedAtDesc(orgId, currentUser.id(), CONTRIBUTOR_TERMS_VERSION)
                .map(ConsentRecord::getAcceptedAt);
    }

    /**
     * 403 unless the current user accepted the current contributor terms for this org. The admin is
     * exempt: entering a contributor's data on their behalf happens after they agreed in writing.
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
        access.require(orgId, OrgType.CONTRIBUTOR);
        if (!CONTRIBUTOR_TERMS_VERSION.equals(version)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The terms have changed; reload and read them again");
        }
        return records.save(new ConsentRecord(orgId, currentUser.id(), version, CONTRIBUTOR_TERMS.sha256())).getAcceptedAt();
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

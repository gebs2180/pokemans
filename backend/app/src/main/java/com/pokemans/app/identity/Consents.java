package com.pokemans.app.identity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Terms an org's members accept before taking part: seller terms for collectors, data-use terms
 * (plus the seller terms) for contributors, and the terms buyers commit to when they take up an
 * instant offer. Who accepted which version is kept. A new version of a text needs a new version
 * string: acceptances are tied to the exact text by its hash, and an older acceptance no longer counts.
 * The texts and their history are in {@code resources/terms}; see its CHANGELOG.md.
 */
@Service
public class Consents {

    /** The terms an org's members accept before taking part. */
    public record Terms(String version, String text, String sha256) {
    }

    /** A member accepted their org's terms, so it can start taking part. Published inside the transaction. */
    public record TermsAccepted(UUID orgId, OrgType orgType, UUID userId) {
    }

    private static final String FOOTER = "\n\nAsk Zach if anything here is unclear.";

    /** What a seller commits to by posting a card. Shared by collectors and contributors. */
    static final String SELLER_TERMS_BODY = text("seller-2026-11-12");

    public static final String SELLER_TERMS_VERSION = "seller-2026-11-12";

    static final String SELLER_TERMS_TEXT = "By posting a card for sale here you agree that:\n\n" + SELLER_TERMS_BODY + FOOTER;

    private static final Terms SELLER_TERMS = new Terms(SELLER_TERMS_VERSION, SELLER_TERMS_TEXT, sha256(SELLER_TERMS_TEXT));

    /** Contributors can sell too, so their terms carry the seller terms. */
    public static final String CONTRIBUTOR_TERMS_VERSION = "contributor-2026-11-12";

    static final String CONTRIBUTOR_TERMS_TEXT = text("contributor-2026-11-12") + "\n\n" + SELLER_TERMS_BODY + FOOTER;

    private static final Terms CONTRIBUTOR_TERMS = new Terms(CONTRIBUTOR_TERMS_VERSION, CONTRIBUTOR_TERMS_TEXT, sha256(CONTRIBUTOR_TERMS_TEXT));

    /** Firm offers: one price, no grade settled on arrival. */
    public static final String BUYER_TERMS_VERSION = "buyer-firm-2026-11-12";

    static final String BUYER_TERMS_TEXT = text("buyer-firm-2026-11-12");

    private static final Terms BUYER_TERMS = new Terms(BUYER_TERMS_VERSION, BUYER_TERMS_TEXT, sha256(BUYER_TERMS_TEXT));

    /** The kinds of org whose members accept terms. */
    static final OrgType[] TERMS_ORGS = {OrgType.COLLECTOR, OrgType.CONTRIBUTOR, OrgType.BUYER};

    private final ConsentRecordRepository records;
    private final OrgAccess access;
    private final CurrentUser currentUser;
    private final ApplicationEventPublisher events;

    Consents(ConsentRecordRepository records, OrgAccess access, CurrentUser currentUser, ApplicationEventPublisher events) {
        this.records = records;
        this.access = access;
        this.currentUser = currentUser;
        this.events = events;
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
        OrgType type = access.require(orgId, TERMS_ORGS).getType();
        Terms terms = termsFor(type).orElseThrow();
        if (!terms.version().equals(version)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The terms have changed; reload and read them again");
        }
        Instant accepted = records.save(new ConsentRecord(orgId, currentUser.id(), version, terms.sha256())).getAcceptedAt();
        events.publishEvent(new TermsAccepted(orgId, type, currentUser.id()));
        return accepted;
    }

    /**
     * A terms text from {@code resources/terms}, with line endings as LF and the file's final newline dropped, so
     * how the file was checked out or saved can't change its hash.
     */
    static String text(String name) {
        try (var in = Consents.class.getResourceAsStream("/terms/" + name + ".txt")) {
            if (in == null) {
                throw new IllegalStateException("No terms text " + name);
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
            return text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

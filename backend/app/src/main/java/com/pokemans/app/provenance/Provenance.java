package com.pokemans.app.provenance;

import com.pokemans.app.catalog.AuthenticityRisk;
import com.pokemans.app.catalog.Catalog;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What other modules may know about a card's provenance: whether an authenticity flag is still
 * unresolved and whether custody is verified, the two gates M4 eligibility needs (CLAUDE.md
 * pipeline step 1). Conservative by default: a card nobody has looked at is unresolved and has no
 * verified custody. No access checks: callers have done them.
 */
@Service
@EnableConfigurationProperties(ProvenanceProperties.class)
public class Provenance {

    /** What an authenticity check found. */
    public enum Finding {
        VERIFIED_AUTHENTIC, SUSPECTED_COUNTERFEIT, COUNTERFEIT
    }

    /**
     * How a card's authenticity was checked. Which methods can verify a card depends on its risk
     * category ({@link #canVerify}); any method can record a doubt.
     */
    public enum AuthMethod {
        IN_HAND_INSPECTION, GRADER_SLAB, THIRD_PARTY_AUTHENTICATION, PHOTO_REVIEW
    }

    public enum HolderType {
        OWNER, PARTNER, VAULT, GRADER, LENDER, OTHER
    }

    /** How custody was checked. */
    public enum CustodyMethod {
        IN_PERSON, VAULT_STATEMENT, GRADER_RECEIPT, VIDEO_CALL
    }

    /** Where a card stands on authenticity. Only NOT_FLAGGED and VERIFIED_AUTHENTIC are resolved. */
    public enum AuthState {
        /** Identified as a card outside the authenticity-risk categories, and nobody has doubted it. */
        NOT_FLAGGED,
        /** Checked and found genuine, as the identity it has now. */
        VERIFIED_AUTHENTIC,
        /** No confirmed identity, so the risk category isn't known. */
        NOT_IDENTIFIED,
        /** In a risk category and not yet verified as its current identity. */
        AWAITING_VERIFICATION,
        SUSPECTED_COUNTERFEIT,
        COUNTERFEIT;

        public boolean unresolved() {
            return this != NOT_FLAGGED && this != VERIFIED_AUTHENTIC;
        }
    }

    public enum CustodyState {
        NOT_RECORDED,
        UNVERIFIED,
        VERIFIED,
        /** Checked, but longer ago than a check stays valid for: check again. */
        LAPSED
    }

    /**
     * A card's provenance gates.
     *
     * @param authFlagUnresolved   true means zero collateral value
     * @param authState            why it is or isn't resolved
     * @param authenticityRisk     the current identity's risk category, or null if not identified
     * @param custodyVerified      false means no advance
     * @param custodyVerifiedUntil when the current custody check lapses, or null if custody isn't verified
     */
    public record Status(UUID cardItemId, boolean authFlagUnresolved, AuthState authState, AuthenticityRisk authenticityRisk,
                         boolean custodyVerified, CustodyState custodyState, Instant custodyVerifiedUntil) {
    }

    /**
     * High risk: vintage holos and 1st Editions, and Japanese promos. They are the most faked and the
     * hardest to judge from photos, so only an in-hand inspection, a grader's slab or a third-party
     * authentication verifies them. Recent chase cards are lower risk: a review of a passing close-up
     * photo can verify them too (Zach, 2026-10-18).
     */
    public static boolean highRisk(AuthenticityRisk risk) {
        return risk == AuthenticityRisk.VINTAGE_HOLO || risk == AuthenticityRisk.JAPANESE_PROMO;
    }

    /** Whether this method can verify a card in this risk category as genuine. */
    public static boolean canVerify(AuthenticityRisk risk, AuthMethod method) {
        return method != AuthMethod.PHOTO_REVIEW || !highRisk(risk);
    }

    private final Catalog catalog;
    private final AuthenticityVerificationRepository verifications;
    private final CustodyRecordRepository custody;
    private final ProvenanceProperties properties;

    Provenance(Catalog catalog, AuthenticityVerificationRepository verifications, CustodyRecordRepository custody,
               ProvenanceProperties properties) {
        this.catalog = catalog;
        this.verifications = verifications;
        this.custody = custody;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public Status status(UUID cardItemId) {
        return status(List.of(cardItemId)).get(cardItemId);
    }

    /** A status for every card asked about, including ones with nothing recorded. */
    @Transactional(readOnly = true)
    public Map<UUID, Status> status(Collection<UUID> cardItemIds) {
        if (cardItemIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Catalog.IdentifiedCard> identities = catalog.current(cardItemIds);
        Map<UUID, AuthenticityVerification> latestFinding = latest(verifications.findByCardItemIdInOrderByCreatedAtDesc(cardItemIds),
                AuthenticityVerification::getCardItemId);
        Map<UUID, CustodyRecord> latestCustody = latest(custody.findByCardItemIdInOrderByCreatedAtDesc(cardItemIds),
                CustodyRecord::getCardItemId);
        Instant now = Instant.now();
        Map<UUID, Status> result = new LinkedHashMap<>();
        for (UUID id : cardItemIds) {
            Catalog.IdentifiedCard identity = identities.get(id);
            AuthState auth = authState(identity, latestFinding.get(id));
            CustodyRecord held = latestCustody.get(id);
            CustodyState custodyState = custodyState(held, now);
            boolean verified = custodyState == CustodyState.VERIFIED;
            result.put(id, new Status(id, auth.unresolved(), auth, identity == null ? null : identity.authenticityRisk(),
                    verified, custodyState, verified ? checkLapsesAt(held) : null));
        }
        return result;
    }

    /**
     * The authenticity lifecycle. A finding, verification or doubt, counts only for the identity it
     * was made against, from when the card got that identity: re-identifying the card as a different
     * catalog card starts again from its risk category (Zach, 2026-10-18).
     *
     * @param identity the card's current identity, or null
     * @param latest   the latest finding on the card, or null
     */
    static AuthState authState(Catalog.IdentifiedCard identity, AuthenticityVerification latest) {
        if (identity == null) {
            return AuthState.NOT_IDENTIFIED;
        }
        if (latest != null && countsFor(latest, identity)) {
            return switch (latest.getStatus()) {
                case VERIFIED_AUTHENTIC -> AuthState.VERIFIED_AUTHENTIC;
                case SUSPECTED_COUNTERFEIT -> AuthState.SUSPECTED_COUNTERFEIT;
                case COUNTERFEIT -> AuthState.COUNTERFEIT;
            };
        }
        return identity.authenticityRisk() == AuthenticityRisk.NONE ? AuthState.NOT_FLAGGED : AuthState.AWAITING_VERIFICATION;
    }

    /** Whether a finding was made against the card's current identity, since it got that identity. */
    static boolean countsFor(AuthenticityVerification finding, Catalog.IdentifiedCard identity) {
        return identity != null && finding.getCatalogCardId().equals(identity.catalogCardId())
                && !finding.getCreatedAt().isBefore(identity.identifiedSince());
    }

    /** A check stays valid for a set time (90 days by default), then custody has to be checked again. */
    CustodyState custodyState(CustodyRecord latest, Instant now) {
        if (latest == null) {
            return CustodyState.NOT_RECORDED;
        }
        if (!latest.isVerified()) {
            return CustodyState.UNVERIFIED;
        }
        return now.isBefore(checkLapsesAt(latest)) ? CustodyState.VERIFIED : CustodyState.LAPSED;
    }

    Instant checkLapsesAt(CustodyRecord checked) {
        return checked.getCreatedAt().plus(properties.custodyCheckValidFor());
    }

    /** The first row per card from a newest-first list. */
    private static <T> Map<UUID, T> latest(List<T> newestFirst, Function<T, UUID> card) {
        return newestFirst.stream().collect(Collectors.toMap(card, Function.identity(), (newer, older) -> newer));
    }
}

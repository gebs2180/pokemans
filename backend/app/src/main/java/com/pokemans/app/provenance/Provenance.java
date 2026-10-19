package com.pokemans.app.provenance;

import com.pokemans.app.catalog.AuthenticityRisk;
import com.pokemans.app.catalog.Catalog;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What other modules may know about a card's provenance: whether an authenticity flag is still
 * unresolved and whether custody is verified, the two gates M4 eligibility needs (CLAUDE.md
 * pipeline step 1). Conservative by default: a card nobody has looked at is unresolved and has no
 * verified custody. No access checks: callers have done them.
 */
@Service
public class Provenance {

    /** What an authenticity check found. */
    public enum Finding {
        VERIFIED_AUTHENTIC, SUSPECTED_COUNTERFEIT, COUNTERFEIT
    }

    /** How a card's authenticity was checked. A photo review can raise a doubt but can't clear one. */
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
        NOT_RECORDED, UNVERIFIED, VERIFIED
    }

    /**
     * A card's provenance gates.
     *
     * @param authFlagUnresolved true means zero collateral value
     * @param authState          why it is or isn't resolved
     * @param authenticityRisk   the current identity's risk category, or null if not identified
     * @param custodyVerified    false means no advance
     */
    public record Status(UUID cardItemId, boolean authFlagUnresolved, AuthState authState, AuthenticityRisk authenticityRisk,
                         boolean custodyVerified, CustodyState custodyState) {
    }

    private final Catalog catalog;
    private final AuthenticityVerificationRepository verifications;
    private final CustodyRecordRepository custody;

    Provenance(Catalog catalog, AuthenticityVerificationRepository verifications, CustodyRecordRepository custody) {
        this.catalog = catalog;
        this.verifications = verifications;
        this.custody = custody;
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
        Map<UUID, Status> result = new LinkedHashMap<>();
        for (UUID id : cardItemIds) {
            Catalog.IdentifiedCard identity = identities.get(id);
            AuthState auth = authState(identity, latestFinding.get(id));
            CustodyState held = custodyState(latestCustody.get(id));
            result.put(id, new Status(id, auth.unresolved(), auth, identity == null ? null : identity.authenticityRisk(),
                    held == CustodyState.VERIFIED, held));
        }
        return result;
    }

    /**
     * The authenticity lifecycle. A doubt sticks to the physical card whatever it is identified as,
     * until someone verifies it genuine. A verification only counts for the identity it was made
     * against: re-identifying the card as a different catalog card makes it unresolved again.
     *
     * @param identity the card's current identity, or null
     * @param latest   the latest finding on the card, or null
     */
    static AuthState authState(Catalog.IdentifiedCard identity, AuthenticityVerification latest) {
        if (latest != null && latest.getStatus() == Finding.SUSPECTED_COUNTERFEIT) {
            return AuthState.SUSPECTED_COUNTERFEIT;
        }
        if (latest != null && latest.getStatus() == Finding.COUNTERFEIT) {
            return AuthState.COUNTERFEIT;
        }
        if (identity == null) {
            return AuthState.NOT_IDENTIFIED;
        }
        if (latest != null && countsFor(latest, identity)) {
            return AuthState.VERIFIED_AUTHENTIC;
        }
        return identity.authenticityRisk() == AuthenticityRisk.NONE ? AuthState.NOT_FLAGGED : AuthState.AWAITING_VERIFICATION;
    }

    /** Whether a finding was made against the card's current identity, since it got that identity. */
    static boolean countsFor(AuthenticityVerification finding, Catalog.IdentifiedCard identity) {
        return identity != null && finding.getCatalogCardId().equals(identity.catalogCardId())
                && !finding.getCreatedAt().isBefore(identity.identifiedSince());
    }

    static CustodyState custodyState(CustodyRecord latest) {
        if (latest == null) {
            return CustodyState.NOT_RECORDED;
        }
        return latest.isVerified() ? CustodyState.VERIFIED : CustodyState.UNVERIFIED;
    }

    /** The first row per card from a newest-first list. */
    private static <T> Map<UUID, T> latest(List<T> newestFirst, Function<T, UUID> card) {
        return newestFirst.stream().collect(Collectors.toMap(card, Function.identity(), (newer, older) -> newer));
    }
}

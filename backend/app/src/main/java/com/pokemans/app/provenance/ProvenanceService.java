package com.pokemans.app.provenance;

import com.pokemans.app.catalog.Catalog;
import com.pokemans.app.identity.CurrentUser;
import com.pokemans.app.intake.CardItems;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Recording authenticity findings and custody, and reading a card's history of both. */
@Service
class ProvenanceService {

    /** @param verificationMethod how custody was checked; null records it unverified */
    record CustodyEntry(Provenance.HolderType holderType, String holderName, String location, LocalDate heldSince,
                        Provenance.CustodyMethod verificationMethod, String note) {
    }

    private final AuthenticityVerificationRepository verifications;
    private final CustodyRecordRepository custody;
    private final CardItems cardItems;
    private final Catalog catalog;
    private final CurrentUser currentUser;

    ProvenanceService(AuthenticityVerificationRepository verifications, CustodyRecordRepository custody, CardItems cardItems,
                      Catalog catalog, CurrentUser currentUser) {
        this.verifications = verifications;
        this.custody = custody;
        this.cardItems = cardItems;
        this.catalog = catalog;
        this.currentUser = currentUser;
    }

    /** Records a finding against the card's current identity. The card must belong to the org. */
    @Transactional
    AuthenticityVerification verify(UUID orgId, UUID cardId, Provenance.Finding finding, Provenance.AuthMethod method, String note) {
        cardItems.requireAccessible(orgId, cardId);
        Catalog.IdentifiedCard identity = catalog.current(cardId).orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                "Confirm the card's identity first: a verification is made against it"));
        if (finding == Provenance.Finding.VERIFIED_AUTHENTIC && method == Provenance.AuthMethod.PHOTO_REVIEW) {
            if (!Provenance.canVerify(identity.authenticityRisk(), method)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A high-risk card (vintage holo, 1st Edition or Japanese "
                        + "promo) can't be verified from photos: inspect it in hand, or use a slab or an authentication service");
            }
            if (cardItems.passedPhotos(cardId).stream().noneMatch(p -> p.side().equals("DETAIL"))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Verifying from photos needs a passing close-up photo of the card");
            }
        }
        return verifications.save(new AuthenticityVerification(orgId, cardId, identity.catalogCardId(), finding, method,
                blankToNull(note), currentUser.id()));
    }

    /**
     * Records who holds the card. Members of the card's org may record custody; only an admin can
     * record it as checked.
     */
    @Transactional
    CustodyRecord recordCustody(UUID orgId, UUID cardId, CustodyEntry entry) {
        cardItems.requireAccessible(orgId, cardId);
        if (entry.verificationMethod() != null && !currentUser.isAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only an admin can record custody as checked");
        }
        // A day's grace for the reviewer's time zone being ahead of UTC.
        if (entry.heldSince().isAfter(LocalDate.now(ZoneOffset.UTC).plusDays(1))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Custody can't start in the future");
        }
        return custody.save(new CustodyRecord(orgId, cardId, entry.holderType(), entry.holderName().trim(),
                entry.location().trim(), entry.heldSince(), entry.verificationMethod(), blankToNull(entry.note()),
                currentUser.id()));
    }

    /** Findings on the card, newest first. */
    @Transactional(readOnly = true)
    List<AuthenticityVerification> verifications(UUID cardId) {
        return verifications.findByCardItemIdInOrderByCreatedAtDesc(List.of(cardId));
    }

    /** Custody records of the card, current first. */
    @Transactional(readOnly = true)
    List<CustodyRecord> custody(UUID cardId) {
        return custody.findByCardItemIdInOrderByCreatedAtDesc(List.of(cardId));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

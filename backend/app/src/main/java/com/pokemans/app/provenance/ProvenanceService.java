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
        if (finding == Provenance.Finding.VERIFIED_AUTHENTIC && method == Provenance.AuthMethod.PHOTO_REVIEW) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Photos can raise a doubt but can't verify a card: inspect it in hand, or use a slab or an authentication service");
        }
        Catalog.IdentifiedCard identity = catalog.current(cardId).orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                "Confirm the card's identity first: a verification is made against it"));
        return verifications.save(new AuthenticityVerification(orgId, cardId, identity.catalogCardId(), finding, method,
                blankToNull(note), currentUser.id()));
    }

    /** Records who holds the card. The card must belong to the org. */
    @Transactional
    CustodyRecord recordCustody(UUID orgId, UUID cardId, CustodyEntry entry) {
        cardItems.requireAccessible(orgId, cardId);
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

package com.pokemans.app.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The collector's card picker: catalog search for any signed-in user, rate-limited and capped, with
 * only the fields the picker shows; and storing their pick as the card's proposed identity.
 */
@RestController
@Validated
class CardPickerController {

    /**
     * What the collector says the card is.
     *
     * @param source       SUGGESTION (the reader's suggestion, by {@code suggestionId}), SEARCH (a picked
     *                     catalog card, the default) or DESCRIBED (in their own words, {@code description})
     * @param externalId   the catalog card; for a suggestion it may be left out, and must match if given
     */
    record ProposeRequest(IdentityProposal.Source source,
                          UUID suggestionId,
                          @Size(max = 100) String externalId,
                          @Pattern(regexp = "[a-z]{2}(-[a-z]{2,4})?") String language,
                          Edition edition,
                          Variant variant,
                          @Size(max = 100) String stamp,
                          Boolean stampUnsure,
                          @Size(max = 300) String description) {
    }

    private final CardPicker picker;
    private final IdentifyService identify;

    CardPickerController(CardPicker picker, IdentifyService identify) {
        this.picker = picker;
        this.identify = identify;
    }

    @GetMapping("/api/catalog/search")
    List<CardPicker.Pick> search(@RequestParam @NotBlank @Size(max = 100) String q,
                                 @RequestParam(defaultValue = "en") @Pattern(regexp = "[a-z]{2}(-[a-z]{2,4})?") String language) {
        return picker.search(q, language);
    }

    @GetMapping("/api/catalog/cards/{externalId}")
    CardPicker.PickDetail card(@PathVariable @Size(max = 100) String externalId,
                               @RequestParam(defaultValue = "en") @Pattern(regexp = "[a-z]{2}(-[a-z]{2,4})?") String language) {
        return picker.detail(externalId, language);
    }

    @PostMapping("/api/orgs/{orgId}/cards/{cardId}/proposed-identity")
    @ResponseStatus(HttpStatus.CREATED)
    CatalogController.ProposalView propose(@PathVariable UUID orgId, @PathVariable UUID cardId, @Valid @RequestBody ProposeRequest r) {
        return CatalogController.view(picker.propose(orgId, cardId, new CardPicker.Proposal(r.source(), r.suggestionId(),
                r.externalId(), r.language(), r.edition(), r.variant(), r.stamp(), Boolean.TRUE.equals(r.stampUnsure()),
                r.description())));
    }

    /**
     * Where the collector is in identifying a card added photos first: taking photos, waiting for the
     * reader, choosing among its suggestions, or searching. {@code again} leaves out an earlier answer,
     * for a collector changing it.
     */
    @GetMapping("/api/orgs/{orgId}/cards/{cardId}/identify")
    IdentifyService.IdentifyView identify(@PathVariable UUID orgId, @PathVariable UUID cardId,
                                          @RequestParam(defaultValue = "false") boolean again) {
        return identify.view(orgId, cardId, again);
    }
}

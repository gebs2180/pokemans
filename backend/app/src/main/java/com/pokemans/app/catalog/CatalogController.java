package com.pokemans.app.catalog;

import com.pokemans.app.intake.CardItems;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Catalog search and card identification. Search and identifying are admin work (the reviewer);
 * members of the card's org can read its identification.
 */
@RestController
@EnableConfigurationProperties(CatalogProperties.class)
class CatalogController {

    record IdentifyRequest(@NotNull CatalogCard.Provider provider,
                           @Size(max = 100) String externalId,
                           @NotBlank @Pattern(regexp = "[a-z]{2}(-[a-z]{2,4})?") String language,
                           @NotNull Edition edition,
                           @NotNull Variant variant,
                           @Size(max = 100) String stamp,
                           Boolean promo,
                           @Size(max = 200) String setName,
                           @Size(max = 30) String number,
                           @Size(max = 200) String name,
                           @Size(max = 100) String rarity,
                           LocalDate setReleaseDate,
                           @Size(max = 2000) String note) {
    }

    record CatalogCardView(UUID id, CatalogCard.Provider provider, String externalId, String language, String setId,
                           String setName, String seriesName, LocalDate setReleaseDate, String number, String name,
                           String rarity, Edition edition, Variant variant, String stamp, boolean promo,
                           AuthenticityRisk authenticityRisk, String imageUrl) {
    }

    record IdentificationView(UUID id, CardIdentification.Source source, CardIdentification.Status status,
                              CatalogCardView card, String note, UUID createdBy, Instant createdAt) {
    }

    /** @param current the latest confirmed identification, or null if the card hasn't been identified */
    record CardIdentificationView(UUID cardId, IdentificationView current, List<IdentificationView> history) {
    }

    /** A provider card without the raw payload. */
    record ProviderCardView(String externalId, String language, String setId, String setName, String seriesName,
                            LocalDate setReleaseDate, String number, String name, String rarity, List<Edition> editions,
                            List<Variant> variants, List<String> stamps, boolean promo, String imageUrl) {
    }

    private final IdentificationService identification;
    private final CardItems cardItems;

    CatalogController(IdentificationService identification, CardItems cardItems) {
        this.identification = identification;
        this.cardItems = cardItems;
    }

    @GetMapping("/api/admin/catalog/search")
    List<CatalogProvider.Hit> search(@RequestParam @NotBlank @Size(max = 100) String name,
                                     @RequestParam(required = false) @Size(max = 30) String number,
                                     @RequestParam(defaultValue = "en") @Pattern(regexp = "[a-z]{2}(-[a-z]{2,4})?") String language) {
        return identification.search(name, number, language);
    }

    @GetMapping("/api/admin/catalog/cards/{externalId}")
    ProviderCardView providerCard(@PathVariable String externalId,
                                  @RequestParam(defaultValue = "en") @Pattern(regexp = "[a-z]{2}(-[a-z]{2,4})?") String language) {
        CatalogProvider.ProviderCard c = identification.providerCard(externalId, language);
        return new ProviderCardView(c.externalId(), c.language(), c.setId(), c.setName(), c.seriesName(), c.setReleaseDate(),
                c.number(), c.name(), c.rarity(), c.editions(), c.variants(), c.stamps(), c.promo(), c.imageUrl());
    }

    @PostMapping("/api/admin/orgs/{orgId}/cards/{cardId}/identifications")
    @ResponseStatus(HttpStatus.CREATED)
    CardIdentificationView identify(@PathVariable UUID orgId, @PathVariable UUID cardId, @Valid @RequestBody IdentifyRequest r) {
        identification.identify(orgId, cardId, new IdentificationService.Choice(r.provider(), r.externalId(), r.language(),
                r.edition(), r.variant(), r.stamp(), r.promo(), r.setName(), r.number(), r.name(), r.rarity(),
                r.setReleaseDate(), r.note()));
        return view(cardId);
    }

    @GetMapping("/api/orgs/{orgId}/cards/{cardId}/identification")
    CardIdentificationView identification(@PathVariable UUID orgId, @PathVariable UUID cardId) {
        cardItems.requireAccessible(orgId, cardId);
        return view(cardId);
    }

    private CardIdentificationView view(UUID cardId) {
        List<IdentificationView> history = identification.history(cardId).stream().map(this::view).toList();
        IdentificationView current = history.stream()
                .filter(i -> i.status() == CardIdentification.Status.CONFIRMED)
                .findFirst().orElse(null);
        return new CardIdentificationView(cardId, current, history);
    }

    private IdentificationView view(CardIdentification i) {
        CatalogCard c = identification.catalogCard(i.getCatalogCardId()).orElseThrow();
        return new IdentificationView(i.getId(), i.getSource(), i.getStatus(), view(c), i.getNote(), i.getCreatedBy(), i.getCreatedAt());
    }

    static CatalogCardView view(CatalogCard c) {
        return new CatalogCardView(c.getId(), c.getProvider(), c.getExternalId(), c.getLanguage(), c.getSetId(), c.getSetName(),
                c.getSeriesName(), c.getSetReleaseDate(), c.getNumber(), c.getName(), c.getRarity(), c.getEdition(),
                c.getVariant(), c.getStamp(), c.isPromo(), c.getAuthenticityRisk(), c.getImageUrl());
    }
}

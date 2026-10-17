package com.pokemans.app.catalog;

import java.net.http.HttpClient;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * <a href="https://tcgdex.dev">TCGdex</a>: free, no key, many languages including Japanese, card data
 * under the MIT licence. Card images are Pokémon artwork: shown to the reviewer by link, not stored.
 */
@Component
@ConditionalOnBooleanProperty(name = "pokemans.catalog.tcgdex.enabled", matchIfMissing = true)
class TcgdexCatalogProvider implements CatalogProvider {

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<Map<String, Object>> OBJECT = new ParameterizedTypeReference<>() {
    };

    private final RestClient http;
    private final Map<String, Map<String, Object>> sets = new ConcurrentHashMap<>();

    TcgdexCatalogProvider(CatalogProperties properties) {
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(properties.tcgdex().timeout()).build());
        requests.setReadTimeout(properties.tcgdex().timeout());
        this.http = RestClient.builder().baseUrl(properties.tcgdex().baseUrl().toString()).requestFactory(requests).build();
    }

    @Override
    public CatalogCard.Provider id() {
        return CatalogCard.Provider.TCGDEX;
    }

    @Override
    public List<Hit> search(String name, String number, String language) {
        // TCGdex matches the name as one phrase, so "metagross delta" or "charizard base set holo"
        // find nothing. Query by the first word and keep names containing every other word.
        List<String> words = Arrays.stream(name.trim().toLowerCase(Locale.ROOT).split("\\s+"))
                .map(TcgdexCatalogProvider::normalizeWord)
                .filter(w -> !w.isEmpty())
                .toList();
        if (words.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> results = http.get()
                .uri("/{lang}/cards?name={name}", language, words.getFirst())
                .retrieve()
                .body(LIST);
        if (results == null) {
            return List.of();
        }
        String wantedNumber = normalizeNumber(number);
        List<Hit> hits = new ArrayList<>();
        for (Map<String, Object> r : results) {
            String id = string(r, "id");
            String localId = string(r, "localId");
            String cardName = string(r, "name");
            if (wantedNumber != null && !wantedNumber.equals(normalizeNumber(localId))) {
                continue;
            }
            String haystack = cardName == null ? "" : cardName.toLowerCase(Locale.ROOT);
            if (!words.stream().skip(1).allMatch(haystack::contains)) {
                continue;
            }
            hits.add(new Hit(id, localId, cardName, setIdOf(id, localId), string(r, "image")));
        }
        return hits;
    }

    /** "delta" and "δ" both mean the Delta Species symbol TCGdex puts in names. */
    private static String normalizeWord(String word) {
        return word.equals("delta") ? "δ" : word;
    }

    /**
     * The collector number as TCGdex stores it: "11/113" (as printed) becomes "11", "011" becomes
     * "11". Null when none was given.
     */
    static String normalizeNumber(String number) {
        if (number == null || number.isBlank()) {
            return null;
        }
        String beforeSlash = number.trim().split("/")[0].trim();
        return beforeSlash.replaceFirst("^0+(?=.)", "").toLowerCase(Locale.ROOT);
    }

    @Override
    public Optional<ProviderCard> card(String externalId, String language) {
        Map<String, Object> card;
        try {
            card = http.get().uri("/{lang}/cards/{id}", language, externalId).retrieve().body(OBJECT);
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
        if (card == null) {
            return Optional.empty();
        }
        Map<String, Object> set = map(card, "set");
        String setId = string(set, "id");
        Map<String, Object> setDetail = setId == null ? Map.of() : set(setId, language);
        String release = string(setDetail, "releaseDate");
        Map<String, Object> serie = map(setDetail, "serie");

        Map<String, Object> variants = map(card, "variants");
        List<Edition> editions = new ArrayList<>(List.of(Edition.UNLIMITED));
        if (Boolean.TRUE.equals(variants.get("firstEdition"))) {
            editions.add(Edition.FIRST_EDITION);
        }
        if ("base1".equals(setId)) {
            editions.add(Edition.SHADOWLESS); // TCGdex doesn't model Shadowless; only Base Set had it
        }
        List<Variant> finishes = new ArrayList<>();
        if (Boolean.TRUE.equals(variants.get("normal"))) {
            finishes.add(Variant.NORMAL);
        }
        if (Boolean.TRUE.equals(variants.get("holo"))) {
            finishes.add(Variant.HOLO);
        }
        if (Boolean.TRUE.equals(variants.get("reverse"))) {
            finishes.add(Variant.REVERSE_HOLO);
        }
        if (finishes.isEmpty()) {
            finishes.add(Variant.NORMAL);
        }
        Set<String> stamps = new LinkedHashSet<>();
        if (card.get("variants_detailed") instanceof List<?> detailed) {
            for (Object d : detailed) {
                if (d instanceof Map<?, ?> m && m.get("stamp") instanceof List<?> s) {
                    s.stream().filter(Objects::nonNull).map(Object::toString).forEach(stamps::add);
                }
            }
        }
        String setName = string(set, "name");
        boolean promo = Boolean.TRUE.equals(variants.get("wPromo"))
                || (setName != null && setName.toLowerCase(Locale.ROOT).contains("promo"));

        return Optional.of(new ProviderCard(externalId, language, setId, setName, string(serie, "name"),
                release == null ? null : LocalDate.parse(release.substring(0, 10)), string(card, "localId"),
                string(card, "name"), string(card, "rarity"), editions, finishes, List.copyOf(stamps), promo,
                string(card, "image"), card));
    }

    private Map<String, Object> set(String setId, String language) {
        return sets.computeIfAbsent(language + "/" + setId, key -> {
            try {
                Map<String, Object> body = http.get().uri("/{lang}/sets/{id}", language, setId).retrieve().body(OBJECT);
                return body == null ? Map.of() : body;
            } catch (HttpClientErrorException.NotFound e) {
                return Map.of();
            }
        });
    }

    /** TCGdex card ids are "{setId}-{localId}". */
    private static String setIdOf(String id, String localId) {
        if (id != null && localId != null && id.endsWith("-" + localId)) {
            return id.substring(0, id.length() - localId.length() - 1);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> source, String key) {
        return source.get(key) instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static String string(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value == null ? null : value.toString();
    }
}

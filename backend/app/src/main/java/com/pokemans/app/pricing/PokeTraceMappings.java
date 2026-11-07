package com.pokemans.app.pricing;

import com.pokemans.app.catalog.Catalog;
import com.pokemans.app.catalog.Edition;
import com.pokemans.app.catalog.Variant;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which PokeTrace card each catalog card is. An admin confirms every mapping: PokeTrace's search
 * doesn't reliably split editions (Base Set Charizard comes back as one "Holofoil" card), and pricing
 * a 1st Edition from Unlimited sales is exactly the identity error that must not happen. A mapping
 * whose number, set, edition, variant or language disagrees with ours is refused unless the admin
 * gives a reason, which is stored.
 *
 * <p>Comps are kept when a card is re-mapped or unmapped (store everything), but only the current
 * PokeTrace card's comps price it: a finding counts only for the identity it was made against.
 */
@Service
class PokeTraceMappings {

    private final PokeTraceCardRepository mappings;
    private final JdbcClient jdbc;

    PokeTraceMappings(PokeTraceCardRepository mappings, JdbcClient jdbc) {
        this.mappings = mappings;
        this.jdbc = jdbc;
    }

    /** The card's current mapping row, if it was ever mapped (its PokeTrace id is null once unmapped). */
    Optional<PokeTraceCard> current(UUID catalogCardId) {
        return mappings.findFirstByCatalogCardIdOrderByCreatedAtDesc(catalogCardId);
    }

    /** The PokeTrace card the catalog card is now mapped to, if any. */
    Optional<String> poketraceId(UUID catalogCardId) {
        return current(catalogCardId).map(PokeTraceCard::getPoketraceId);
    }

    /** Every currently mapped catalog card's PokeTrace id. */
    Map<UUID, String> all() {
        Map<UUID, String> ids = new LinkedHashMap<>();
        jdbc.sql("""
                        select distinct on (catalog_card_id) catalog_card_id, poketrace_id
                        from poketrace_card order by catalog_card_id, created_at desc, id desc""")
                .query((rs, i) -> {
                    String id = rs.getString("poketrace_id");
                    if (id != null) {
                        ids.put(rs.getObject("catalog_card_id", UUID.class), id);
                    }
                    return null;
                })
                .list();
        return ids;
    }

    /**
     * Maps the card to the PokeTrace card {@code detail} describes.
     *
     * @param overrideReason why to map despite disagreements; required when there are any
     * @throws IllegalArgumentException listing the disagreements, if there is no reason
     */
    @Transactional
    PokeTraceCard map(Catalog.CardSummary card, String poketraceId, Map<String, Object> detail, String overrideReason, UUID by) {
        List<String> disagreements = disagreements(card, detail);
        String reason = overrideReason == null || overrideReason.isBlank() ? null : overrideReason.trim();
        if (!disagreements.isEmpty() && reason == null) {
            throw new IllegalArgumentException("PokeTrace's card doesn't match ours: " + String.join("; ", disagreements)
                    + ". Give a reason to map it anyway.");
        }
        Map<String, Object> seen = new LinkedHashMap<>();
        for (String key : List.of("id", "name", "cardNumber", "variant", "rarity", "language", "set", "refs")) {
            if (detail.get(key) != null) {
                seen.put(key, detail.get(key));
            }
        }
        seen.put("disagreements", disagreements);
        return mappings.save(new PokeTraceCard(card.catalogCardId(), poketraceId, seen, disagreements.isEmpty() ? null : reason, by));
    }

    @Transactional
    PokeTraceCard unmap(UUID catalogCardId, UUID by) {
        return mappings.save(new PokeTraceCard(catalogCardId, null, null, null, by));
    }

    /** Where PokeTrace's card and ours disagree; empty when they agree on everything we can check. */
    static List<String> disagreements(Catalog.CardSummary card, Map<String, Object> detail) {
        List<String> out = new ArrayList<>();
        String theirNumber = number(string(detail.get("cardNumber")));
        String ourNumber = number(card.number());
        if (!ourNumber.isEmpty() && !ourNumber.equals(theirNumber)) {
            out.add("number " + string(detail.get("cardNumber")) + ", ours " + card.number());
        }
        String theirSet = norm(detail.get("set") instanceof Map<?, ?> s ? string(s.get("name")) : null);
        String ourSet = norm(card.setName());
        if (!ourSet.isEmpty() && (theirSet.isEmpty() || !(theirSet.contains(ourSet) || ourSet.contains(theirSet)))) {
            out.add("set " + (theirSet.isEmpty() ? "missing" : string(((Map<?, ?>) detail.get("set")).get("name"))) + ", ours "
                    + card.setName());
        }
        String variant = norm(string(detail.get("variant")));
        boolean first = variant.contains("1st");
        boolean shadowless = variant.contains("shadowless");
        Edition edition = card.edition() == null ? Edition.UNLIMITED : card.edition();
        switch (edition) {
            case FIRST_EDITION -> {
                if (!first) {
                    out.add("edition: ours is 1st Edition, PokeTrace's variant is " + variantName(detail));
                }
            }
            case SHADOWLESS -> {
                if (!shadowless) {
                    out.add("edition: ours is Shadowless, PokeTrace's variant is " + variantName(detail));
                }
            }
            case UNLIMITED -> {
                if (first || shadowless) {
                    out.add("edition: ours is Unlimited, PokeTrace's variant is " + variantName(detail));
                }
            }
        }
        boolean reverse = variant.contains("reverse");
        boolean holo = variant.contains("holo");
        if (card.variant() == Variant.REVERSE_HOLO && !reverse
                || card.variant() == Variant.HOLO && (!holo || reverse)
                || card.variant() == Variant.NORMAL && holo) {
            out.add("variant: ours is " + card.variant() + ", PokeTrace's is " + variantName(detail));
        }
        String theirLanguage = norm(string(detail.get("language")));
        String ourLanguage = norm(card.language());
        if (!ourLanguage.isEmpty() && !ourLanguage.equals("en") && !ourLanguage.equals(theirLanguage)) {
            out.add("language: ours is " + card.language() + ", PokeTrace's US market is English cards");
        }
        if (card.stamp() != null && !card.stamp().isBlank()) {
            out.add("stamp: ours has \"" + card.stamp() + "\", which PokeTrace doesn't record");
        }
        return out;
    }

    private static String variantName(Map<String, Object> detail) {
        String v = string(detail.get("variant"));
        return v == null || v.isBlank() ? "not given" : v;
    }

    /** "004/102", "4" and "04" are all 4; "SV49" stays sv49; "TG05/TG30" is tg05. */
    static String number(String n) {
        if (n == null) {
            return "";
        }
        String head = n.trim().toLowerCase(Locale.ROOT);
        int slash = head.indexOf('/');
        if (slash >= 0) {
            head = head.substring(0, slash);
        }
        head = head.replaceAll("[^a-z0-9]", "");
        return head.matches("\\d+") ? head.replaceFirst("^0+(?=\\d)", "") : head;
    }

    private static String norm(String s) {
        if (s == null) {
            return "";
        }
        String plain = Normalizer.normalize(s, Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
        return plain.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static String string(Object o) {
        return o == null ? null : o.toString();
    }
}

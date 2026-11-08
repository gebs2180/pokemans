package com.pokemans.app.pricing;

import com.pokemans.app.catalog.Catalog;
import com.pokemans.app.catalog.Edition;
import com.pokemans.app.catalog.Variant;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * PokeTrace candidates for a catalog card, searched automatically when a card is identified, and the
 * admin's queue of cards still to map. A suggestion never prices anything: only a mapping does, and
 * every mapping is an admin's ({@link PokeTraceMappings#map}). A card gets a one-click suggestion only
 * when exactly one candidate agrees with ours on everything we check; mismatches and several matches
 * are decided on the card page.
 */
@Service
class PokeTraceSuggestions {

    private static final Logger log = LoggerFactory.getLogger(PokeTraceSuggestions.class);

    /** Cards one "Find suggestions" click searches, against PokeTrace's 10,000 requests a day. */
    static final int MAX_SEARCH = 50;
    static final int MAX_APPROVE = 100;
    private static final int MAX_CANDIDATES = 25;

    record Candidate(String poketraceId, String name, String cardNumber, String variant, String setName, List<String> disagreements) {

        Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("poketraceId", poketraceId);
            m.put("name", name);
            m.put("cardNumber", cardNumber);
            m.put("variant", variant);
            m.put("setName", setName);
            m.put("disagreements", disagreements);
            return m;
        }
    }

    /**
     * A catalog card some physical card is identified as, with no PokeTrace mapping yet.
     *
     * @param searchedAt null when PokeTrace hasn't been searched for it
     * @param suggestedId the one clean match, if there is exactly one
     */
    record QueueItem(UUID catalogCardId, UUID orgId, UUID cardItemId, String name, String setName, String number, Edition edition,
                     Variant variant, String language, Instant searchedAt, String suggestedId,
                     List<Map<String, Object>> candidates, String error) {
    }

    record Failure(UUID catalogCardId, String error) {
    }

    record Approval(List<UUID> mapped, List<Failure> failures) {
    }

    private final ObjectProvider<PokeTraceApi> api;
    private final PokeTraceMappings mappings;
    private final PokeTraceSuggestionRepository suggestions;
    private final Catalog catalog;
    private final TaskExecutor executor;
    private final AtomicBoolean searching = new AtomicBoolean();

    PokeTraceSuggestions(ObjectProvider<PokeTraceApi> api, PokeTraceMappings mappings, PokeTraceSuggestionRepository suggestions,
                         Catalog catalog, @Qualifier("applicationTaskExecutor") TaskExecutor executor) {
        this.api = api;
        this.mappings = mappings;
        this.suggestions = suggestions;
        this.catalog = catalog;
        this.executor = executor;
    }

    /**
     * PokeTrace's single cards with the card's name, narrowed to our number and then our set when any
     * match, so a common name (a dozen Victinis) shows only the card itself. With no match at a step
     * the wider list stays, and each row's disagreements say why it isn't ours. Fewest disagreements first.
     */
    List<Candidate> candidates(PokeTraceApi api, Catalog.CardSummary card) {
        List<Candidate> all = api.search(card.name()).stream()
                .filter(c -> c.get("id") != null && "single".equals(String.valueOf(c.getOrDefault("productType", "single"))))
                .map(c -> new Candidate(c.get("id").toString(), str(c.get("name")), str(c.get("cardNumber")), str(c.get("variant")),
                        c.get("set") instanceof Map<?, ?> s ? str(s.get("name")) : null, PokeTraceMappings.disagreements(card, c)))
                .toList();
        return narrow(narrow(all, "number "), "set ").stream()
                .sorted(Comparator.comparingInt((Candidate c) -> c.disagreements().size()))
                .limit(MAX_CANDIDATES)
                .toList();
    }

    /** The candidates without a disagreement of this kind, or all of them when every one has it. */
    private static List<Candidate> narrow(List<Candidate> candidates, String kind) {
        List<Candidate> matching = candidates.stream()
                .filter(c -> c.disagreements().stream().noneMatch(d -> d.startsWith(kind)))
                .toList();
        return matching.isEmpty() ? candidates : matching;
    }

    /** Searches PokeTrace in the background once a newly identified card's identity has committed. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onIdentified(Catalog.CardIdentified event) {
        PokeTraceApi a = api.getIfAvailable();
        if (a == null) {
            return;
        }
        executor.execute(() -> {
            try {
                UUID id = event.catalogCardId();
                if (mappings.poketraceId(id).isEmpty() && !suggestions.existsByCatalogCardId(id)) {
                    catalog.cards(List.of(id)).forEach(card -> suggest(a, card));
                }
            } catch (RuntimeException e) {
                log.warn("Suggesting a PokeTrace card for {} failed; Find suggestions on the queue retries it", event.catalogCardId(), e);
            }
        });
    }

    /** Searches PokeTrace for the card and stores what it found, or why it failed. */
    PokeTraceSuggestion suggest(PokeTraceApi api, Catalog.CardSummary card) {
        try {
            List<Candidate> found = candidates(api, card);
            List<Candidate> clean = found.stream().filter(c -> c.disagreements().isEmpty()).toList();
            String suggested = clean.size() == 1 ? clean.getFirst().poketraceId() : null;
            return suggestions.save(new PokeTraceSuggestion(card.catalogCardId(), suggested,
                    found.stream().map(Candidate::asMap).toList(), null));
        } catch (RuntimeException e) {
            log.warn("PokeTrace search for {} failed", card.catalogCardId(), e);
            String error = String.valueOf(e.getMessage());
            return suggestions.save(new PokeTraceSuggestion(card.catalogCardId(), null, List.of(),
                    error.length() > 1000 ? error.substring(0, 1000) : error));
        }
    }

    /**
     * Starts searching, in the background, up to {@link #MAX_SEARCH} unmapped identified cards that
     * were never searched or whose last search failed.
     *
     * @return how many cards it started on; 0 when there are none, or a search is already running
     */
    int suggestMissing(PokeTraceApi api) {
        if (!searching.compareAndSet(false, true)) {
            return 0;
        }
        List<Catalog.CardSummary> todo;
        try {
            Set<UUID> mapped = mappings.all().keySet();
            Map<UUID, PokeTraceSuggestion> latest = latest();
            todo = catalog.identifiedCards().stream()
                    .filter(c -> !mapped.contains(c.catalogCardId()))
                    .filter(c -> !latest.containsKey(c.catalogCardId()) || latest.get(c.catalogCardId()).getError() != null)
                    .limit(MAX_SEARCH)
                    .toList();
        } catch (RuntimeException e) {
            searching.set(false);
            throw e;
        }
        if (todo.isEmpty()) {
            searching.set(false);
            return 0;
        }
        executor.execute(() -> {
            try {
                todo.forEach(card -> suggest(api, card));
            } finally {
                searching.set(false);
            }
        });
        return todo.size();
    }

    boolean searching() {
        return searching.get();
    }

    /** Identified catalog cards with no PokeTrace mapping, clean suggestions first. */
    List<QueueItem> queue() {
        Set<UUID> mapped = mappings.all().keySet();
        List<Catalog.IdentifiedItem> items = catalog.identifiedItems().stream()
                .filter(i -> !mapped.contains(i.catalogCardId()))
                .toList();
        Map<UUID, Catalog.CardSummary> cards = catalog.cards(items.stream().map(Catalog.IdentifiedItem::catalogCardId).toList())
                .stream().collect(Collectors.toMap(Catalog.CardSummary::catalogCardId, Function.identity()));
        Map<UUID, PokeTraceSuggestion> latest = latest();
        List<QueueItem> queue = new ArrayList<>();
        for (Catalog.IdentifiedItem item : items) {
            Catalog.CardSummary c = cards.get(item.catalogCardId());
            if (c == null) {
                continue;
            }
            PokeTraceSuggestion s = latest.get(item.catalogCardId());
            queue.add(new QueueItem(c.catalogCardId(), item.orgId(), item.cardItemId(), c.name(), c.setName(), c.number(), c.edition(),
                    c.variant(), c.language(), s == null ? null : s.getCreatedAt(), s == null ? null : s.getSuggestedId(),
                    s == null ? List.of() : s.getCandidates(), s == null ? null : s.getError()));
        }
        queue.sort(Comparator.comparing((QueueItem q) -> q.suggestedId() == null)
                .thenComparing(QueueItem::name, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        return queue;
    }

    /**
     * Maps each card to its clean suggestion, checked again against PokeTrace's card now. A card that
     * is already mapped, has no clean suggestion, or no longer agrees fails on its own; the rest go through.
     */
    Approval approve(PokeTraceApi api, List<UUID> catalogCardIds, UUID by) {
        Set<UUID> ids = new LinkedHashSet<>(catalogCardIds);
        Map<UUID, Catalog.CardSummary> cards = catalog.cards(ids).stream()
                .collect(Collectors.toMap(Catalog.CardSummary::catalogCardId, Function.identity()));
        List<UUID> mapped = new ArrayList<>();
        List<Failure> failures = new ArrayList<>();
        for (UUID id : ids) {
            Catalog.CardSummary card = cards.get(id);
            if (card == null) {
                failures.add(new Failure(id, "No catalog card " + id));
                continue;
            }
            if (mappings.poketraceId(id).isPresent()) {
                failures.add(new Failure(id, "Already mapped"));
                continue;
            }
            String suggested = suggestions.findFirstByCatalogCardIdOrderByCreatedAtDesc(id)
                    .map(PokeTraceSuggestion::getSuggestedId).orElse(null);
            if (suggested == null) {
                failures.add(new Failure(id, "No clean PokeTrace match: decide it on the card page"));
                continue;
            }
            try {
                Map<String, Object> detail = api.card(suggested);
                if (detail.isEmpty() || !suggested.equals(str(detail.get("id")))) {
                    failures.add(new Failure(id, "PokeTrace has no card " + suggested + " now"));
                    continue;
                }
                mappings.map(card, suggested, detail, null, by);
                mapped.add(id);
            } catch (IllegalArgumentException e) {
                failures.add(new Failure(id, e.getMessage()));
            } catch (RuntimeException e) {
                log.warn("Approving PokeTrace card {} for {} failed", suggested, id, e);
                failures.add(new Failure(id, "PokeTrace failed: " + e.getMessage()));
            }
        }
        return new Approval(mapped, failures);
    }

    private Map<UUID, PokeTraceSuggestion> latest() {
        return suggestions.findLatestPerCard().stream()
                .collect(Collectors.toMap(PokeTraceSuggestion::getCatalogCardId, Function.identity()));
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}

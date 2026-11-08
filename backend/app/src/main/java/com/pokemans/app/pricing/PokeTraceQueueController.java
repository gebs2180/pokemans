package com.pokemans.app.pricing;

import com.pokemans.app.identity.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Admin-only (by path): identified cards still to map on PokeTrace, with what the automatic search
 * suggested. Approving maps a card exactly as the card page would; approving doesn't pull sales, the
 * nightly run does.
 */
@RestController
@RequestMapping("/api/admin/poketrace/queue")
class PokeTraceQueueController {

    record QueueView(boolean enabled, boolean searching, List<PokeTraceSuggestions.QueueItem> items) {
    }

    record SearchView(int started, boolean searching) {
    }

    record ApproveRequest(@NotEmpty @Size(max = PokeTraceSuggestions.MAX_APPROVE) List<UUID> catalogCardIds) {
    }

    private final ObjectProvider<PokeTraceApi> api;
    private final PokeTraceSuggestions suggestions;
    private final CurrentUser currentUser;

    PokeTraceQueueController(ObjectProvider<PokeTraceApi> api, PokeTraceSuggestions suggestions, CurrentUser currentUser) {
        this.api = api;
        this.suggestions = suggestions;
        this.currentUser = currentUser;
    }

    /** The queue; empty and not enabled when PokeTrace is off, so the admin page can still count it. */
    @GetMapping
    QueueView queue() {
        if (api.getIfAvailable() == null) {
            return new QueueView(false, false, List.of());
        }
        return new QueueView(true, suggestions.searching(), suggestions.queue());
    }

    /** Starts searching unsearched (or failed) cards in the background. */
    @PostMapping("/search")
    SearchView search() {
        int started = suggestions.suggestMissing(api());
        return new SearchView(started, suggestions.searching());
    }

    @PostMapping("/approve")
    PokeTraceSuggestions.Approval approve(@Valid @RequestBody ApproveRequest request) {
        return suggestions.approve(api(), request.catalogCardIds(), currentUser.id());
    }

    private PokeTraceApi api() {
        PokeTraceApi a = api.getIfAvailable();
        if (a == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PokeTrace isn't enabled on this server");
        }
        return a;
    }
}

package com.pokemans.app.pricing;

import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin-only (by path): mapped cards whose PokeTrace pulls have stopped ({@link PokeTracePullGaps}). */
@RestController
@RequestMapping("/api/admin/poketrace/pull-gaps")
class PokeTracePullGapController {

    /**
     * @param enabled false when PokeTrace is off on this server, and then there are no gaps
     * @param gapDays days without a successful pull before a mapped card is flagged
     */
    record PullGapsView(boolean enabled, int gapDays, List<PokeTracePullGaps.Gap> gaps) {
    }

    private final ObjectProvider<PokeTraceApi> api;
    private final PokeTracePullGaps pullGaps;

    PokeTracePullGapController(ObjectProvider<PokeTraceApi> api, PokeTracePullGaps pullGaps) {
        this.api = api;
        this.pullGaps = pullGaps;
    }

    @GetMapping
    PullGapsView gaps() {
        if (api.getIfAvailable() == null) {
            return new PullGapsView(false, pullGaps.gapDays(), List.of());
        }
        return new PullGapsView(true, pullGaps.gapDays(), pullGaps.gaps(Instant.now()));
    }
}

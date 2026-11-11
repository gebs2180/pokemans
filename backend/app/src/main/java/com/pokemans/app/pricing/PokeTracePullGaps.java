package com.pokemans.app.pricing;

import com.pokemans.app.catalog.Catalog;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mapped cards whose PokeTrace pulls have stopped (a PR #114 follow-up): an identified card whose current
 * mapping names a PokeTrace card, with no successful pull for {@code pokemans.pricing.poketrace.pull-gap-days}
 * since it was mapped. A card mapped that recently isn't flagged yet. Its price table would otherwise only
 * show the gap by going stale.
 */
@Service
class PokeTracePullGaps {

    /**
     * @param lastPulledAt the newest successful pull since the card was mapped, or null if none
     * @param lastError    the newest pull's error, when that pull failed
     * @param daysSince    whole days since the later of the mapping and the last successful pull
     */
    record Gap(UUID catalogCardId, UUID orgId, UUID cardItemId, String name, String number, String setName,
               String poketraceId, Instant mappedAt, Instant lastPulledAt, String lastError, long daysSince) {
    }

    private final JdbcClient jdbc;
    private final Catalog catalog;
    private final PricingProperties properties;

    PokeTracePullGaps(JdbcClient jdbc, Catalog catalog, PricingProperties properties) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.properties = properties;
    }

    int gapDays() {
        return properties.poketrace().pullGapDays();
    }

    /** The gaps as of {@code now}, longest first. */
    @Transactional(readOnly = true)
    List<Gap> gaps(Instant now) {
        Instant cutoff = now.minus(Duration.ofDays(gapDays()));
        List<Gap> found = new ArrayList<>();
        Map<UUID, Catalog.IdentifiedItem> items = new LinkedHashMap<>();
        catalog.identifiedItems().forEach(i -> items.putIfAbsent(i.catalogCardId(), i));
        List<Row> rows = jdbc.sql("""
                        with mapping as (
                            select distinct on (catalog_card_id) catalog_card_id, poketrace_id, created_at
                            from poketrace_card order by catalog_card_id, created_at desc, id desc
                        )
                        select m.catalog_card_id, m.poketrace_id, m.created_at as mapped_at,
                               (select max(p.pulled_at) from comp_pull p
                                where p.source = :source and p.catalog_card_id = m.catalog_card_id and p.ok
                                  and p.pulled_at >= m.created_at) as last_ok,
                               (select case when p.ok then null else coalesce(p.error, 'failed') end from comp_pull p
                                where p.source = :source and p.catalog_card_id = m.catalog_card_id
                                  and p.pulled_at >= m.created_at
                                order by p.pulled_at desc limit 1) as last_error
                        from mapping m where m.poketrace_id is not null""")
                .param("source", PokeTraceSource.NAME)
                .query((rs, i) -> new Row(rs.getObject("catalog_card_id", UUID.class), rs.getString("poketrace_id"),
                        rs.getObject("mapped_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("last_ok", OffsetDateTime.class) == null ? null
                                : rs.getObject("last_ok", OffsetDateTime.class).toInstant(),
                        rs.getString("last_error")))
                .list();
        List<Row> stopped = rows.stream()
                .filter(r -> items.containsKey(r.catalogCardId()))
                .filter(r -> r.since().isBefore(cutoff))
                .toList();
        Map<UUID, Catalog.CardSummary> cards = catalog.cards(stopped.stream().map(Row::catalogCardId).toList()).stream()
                .collect(Collectors.toMap(Catalog.CardSummary::catalogCardId, Function.identity()));
        for (Row r : stopped) {
            Catalog.IdentifiedItem item = items.get(r.catalogCardId());
            Catalog.CardSummary card = cards.get(r.catalogCardId());
            found.add(new Gap(r.catalogCardId(), item.orgId(), item.cardItemId(), card == null ? null : card.name(),
                    card == null ? null : card.number(), card == null ? null : card.setName(), r.poketraceId(), r.mappedAt(),
                    r.lastOk(), r.lastError(), Duration.between(r.since(), now).toDays()));
        }
        found.sort(Comparator.comparingLong(Gap::daysSince).reversed().thenComparing(g -> g.catalogCardId().toString()));
        return found;
    }

    private record Row(UUID catalogCardId, String poketraceId, Instant mappedAt, Instant lastOk, String lastError) {
        Instant since() {
            return lastOk == null || lastOk.isBefore(mappedAt) ? mappedAt : lastOk;
        }
    }
}

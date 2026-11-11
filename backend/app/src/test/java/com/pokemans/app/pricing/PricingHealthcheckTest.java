package com.pokemans.app.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pokemans.app.catalog.Catalog;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** The nightly pricing run's healthcheck ping (deep review A6): off when unset, fail on a run that inserts nothing. */
class PricingHealthcheckTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 9);
    private static final String URL = "https://hc-ping.com/test-check";

    private record Ping(URI uri, String body) {
    }

    private final List<Ping> pings = new ArrayList<>();
    private final PriceSource source = mock(PriceSource.class);
    private final CompIngestionService ingestion = mock(CompIngestionService.class);
    private final PriceSnapshotService snapshots = mock(PriceSnapshotService.class);
    private final PokeTracePullGaps pullGaps = mock(PokeTracePullGaps.class);

    @SuppressWarnings("unchecked")
    private PricingRefresh refresh(String url) {
        ObjectProvider<PriceSource> sources = mock(ObjectProvider.class);
        when(sources.orderedStream()).thenAnswer(inv -> Stream.of(source));
        Catalog catalog = mock(Catalog.class);
        when(catalog.identifiedCards()).thenReturn(List.of());
        PricingLock lock = mock(PricingLock.class);
        when(lock.run(any())).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());
        when(snapshots.snapshotAll(TODAY)).thenReturn(new PriceSnapshotService.Result(TODAY, Map.of(), Map.of()));
        PricingProperties properties = new PricingProperties("USD", 3, 12, null,
                new PricingProperties.Ingestion(true, "0 30 6 * * *", Duration.ofDays(400)), null);
        PricingHealthcheck healthcheck = new PricingHealthcheck(url, (uri, body) -> pings.add(new Ping(uri, body)));
        when(pullGaps.gapDays()).thenReturn(2);
        return new PricingRefresh(sources, ingestion, snapshots, catalog, properties, lock, healthcheck, pullGaps);
    }

    private void ingests(int inserted) {
        CompIngestRun run = new CompIngestRun("POKETRACE", TODAY.minusDays(400), TODAY, 3);
        for (int i = 0; i < inserted; i++) {
            run.inserted();
        }
        run.finish();
        when(ingestion.ingest(any(), any(), any(), any())).thenReturn(run);
    }

    @Test
    void sendsNothingWhenNoUrlIsSet() {
        ingests(0);
        refresh("").runOnce(TODAY);
        refresh(null).runOnce(TODAY);
        assertThat(pings).isEmpty();
    }

    @Test
    void failsARunThatInsertsNoRows() {
        ingests(0);
        refresh(URL).runOnce(TODAY);
        assertThat(pings).singleElement().satisfies(ping -> {
            assertThat(ping.uri()).isEqualTo(URI.create(URL + "/fail"));
            assertThat(ping.body()).contains("0 rows inserted").contains("no rows inserted from POKETRACE");
        });
    }

    @Test
    void pingsSuccessWithTheRowCount() {
        ingests(7);
        refresh(URL + "/").runOnce(TODAY);
        assertThat(pings).singleElement().satisfies(ping -> {
            assertThat(ping.uri()).isEqualTo(URI.create(URL));
            assertThat(ping.body()).contains("7 rows inserted");
        });
    }

    @Test
    void failsARunWhenAMappedCardsPullsHaveStopped() {
        ingests(7);
        when(pullGaps.gaps(any())).thenReturn(List.of(new PokeTracePullGaps.Gap(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "Charizard", "4", "Base Set", "pt-1", Instant.parse("2026-11-01T00:00:00Z"),
                Instant.parse("2026-11-05T00:00:00Z"), "timeout", 4)));
        refresh(URL).runOnce(TODAY);
        assertThat(pings).singleElement().satisfies(ping -> {
            assertThat(ping.uri()).isEqualTo(URI.create(URL + "/fail"));
            assertThat(ping.body()).contains("7 rows inserted")
                    .contains("1 mapped card(s) with no PokeTrace pull for 2+ days: Charizard (4 days)");
        });
    }

    @Test
    void failsARunThatThrows() {
        ingests(5);
        PricingRefresh refresh = refresh(URL);
        when(snapshots.snapshotAll(TODAY)).thenThrow(new IllegalStateException("out of memory"));
        assertThatThrownBy(() -> refresh.runOnce(TODAY)).hasMessage("out of memory");
        assertThat(pings).singleElement().satisfies(ping -> {
            assertThat(ping.uri()).isEqualTo(URI.create(URL + "/fail"));
            assertThat(ping.body()).contains("out of memory");
        });
    }

    @Test
    void aFailedPingNeverFailsTheRun() {
        PricingHealthcheck healthcheck = new PricingHealthcheck(URL, (uri, body) -> {
            throw new java.io.IOException("unreachable");
        });
        healthcheck.success("ok");
        healthcheck.fail("bad");
    }
}

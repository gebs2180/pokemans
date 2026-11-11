package com.pokemans.app.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import com.pokemans.valuation.ParameterSet;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Revised PokeTrace days (a PR #114 follow-up): which row prices a day, by parameter set. */
class PokeTraceRevisionsTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 1);
    private static final UUID CARD = UUID.randomUUID();
    private static final String DAY_ID = "pt-x|PSA_10|ebay|" + DAY;

    private static SoldComp row(String source, String externalSaleId, String avg, String low, String high) {
        Map<String, Object> raw = new HashMap<>();
        if (low != null) {
            raw.put("low", new BigDecimal(low));
            raw.put("high", new BigDecimal(high));
        }
        return new SoldComp(new PriceSource.Sale(source, externalSaleId, CARD, PriceSource.GradingCompany.PSA, BigDecimal.TEN,
                null, new BigDecimal(avg), "USD", DAY, null, PriceSource.ListingType.UNKNOWN, raw), "test", Instant.now(), null);
    }

    private static PriceSource.Sale sale(String avg, String low, String high, int count) {
        Map<String, Object> raw = new HashMap<>();
        raw.put("low", new BigDecimal(low));
        raw.put("high", new BigDecimal(high));
        raw.put("saleCount", count);
        return new PriceSource.Sale(PokeTraceSource.NAME, DAY_ID, CARD, PriceSource.GradingCompany.PSA, BigDecimal.TEN, null,
                new BigDecimal(avg), "USD", DAY, null, PriceSource.ListingType.UNKNOWN, raw);
    }

    @Test
    void revisionIdsRoundTrip() {
        assertThat(PokeTraceRevisions.revisionId(DAY_ID, 0)).isEqualTo(DAY_ID);
        assertThat(PokeTraceRevisions.revisionId(DAY_ID, 3)).isEqualTo(DAY_ID + "#r3");
        assertThat(PokeTraceRevisions.day(DAY_ID + "#r3")).isEqualTo(DAY_ID);
        assertThat(PokeTraceRevisions.revision(DAY_ID + "#r3")).isEqualTo(3);
        assertThat(PokeTraceRevisions.day(DAY_ID)).isEqualTo(DAY_ID);
        assertThat(PokeTraceRevisions.revision(DAY_ID)).isZero();
    }

    @Test
    void aChangedAverageLowOrHighIsARevisionButTheNoisedCountIsNot() {
        Map<String, Object> stored = Map.of("low", 90, "high", 110, "saleCount", 2);
        BigDecimal price = new BigDecimal("100.00");

        assertThat(PokeTraceRevisions.changed(price, stored, sale("100", "90", "110", 7))).isFalse();
        assertThat(PokeTraceRevisions.changed(price, stored, sale("100.00", "90.00", "110.00", 2))).isFalse();
        assertThat(PokeTraceRevisions.changed(price, stored, sale("101", "90", "110", 2))).isTrue();
        assertThat(PokeTraceRevisions.changed(price, stored, sale("100", "80", "110", 2))).isTrue();
        assertThat(PokeTraceRevisions.changed(price, stored, sale("100", "90", "120", 2))).isTrue();
        assertThat(PokeTraceRevisions.changed(price, Map.of(), sale("100", "90", "110", 2))).isTrue();
    }

    @Test
    void from170TheNewestRevisionPricesTheDayAndTheOthersAreSuperseded() {
        SoldComp first = row(PokeTraceSource.NAME, DAY_ID, "100", "90", "110");
        SoldComp second = row(PokeTraceSource.NAME, DAY_ID + "#r1", "120", "90", "150");
        SoldComp third = row(PokeTraceSource.NAME, DAY_ID + "#r2", "130", null, null);
        SoldComp otherDay = row(PokeTraceSource.NAME, "pt-x|PSA_10|ebay|2026-10-31", "100", null, null);
        SoldComp manual = row(CompIngestionService.MANUAL, "manual:" + UUID.randomUUID(), "90", null, null);

        PokeTraceRevisions.Result r = PokeTraceRevisions.apply(List.of(third, first, otherDay, second, manual),
                ParameterSet.load("1.7.0"));

        assertThat(r.current()).containsExactly(third, otherDay, manual);
        assertThat(r.superseded()).extracting(PokeTraceDayGuard.Excluded::comp).containsExactly(first, second);
        assertThat(r.superseded()).allSatisfy(e -> {
            assertThat(e.rule()).isEqualTo(PokeTraceRevisions.SUPERSEDED);
            assertThat(e.reason()).contains("replaced by 130 (").contains("revision 2").contains(third.getId().toString());
        });
        assertThat(r.superseded().get(0).reason()).contains("average 100 (90.00 to 110.00)");
    }

    @Test
    void before170ADaysFirstRowPricesItAndRevisionsAreIgnored() {
        SoldComp first = row(PokeTraceSource.NAME, DAY_ID, "100", "90", "110");
        SoldComp second = row(PokeTraceSource.NAME, DAY_ID + "#r1", "120", "90", "150");

        PokeTraceRevisions.Result r = PokeTraceRevisions.apply(List.of(first, second), ParameterSet.load("1.6.0"));

        assertThat(r.current()).containsExactly(first);
        assertThat(r.superseded()).isEmpty();
    }
}

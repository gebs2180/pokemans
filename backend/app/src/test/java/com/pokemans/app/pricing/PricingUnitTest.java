package com.pokemans.app.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Sale validation (sold sales only) and era keys. The pricing rules are tested in valuation-core. */
class PricingUnitTest {

    private static final UUID CARD = UUID.randomUUID();
    private static final LocalDate SOLD = LocalDate.of(2026, 9, 1);

    private static PriceSource.Sale sale(PriceSource.GradingCompany company, String grade, PriceSource.RawCondition raw,
                                         String price, LocalDate soldOn) {
        return new PriceSource.Sale("X", "1", CARD, company, grade == null ? null : new BigDecimal(grade), raw,
                new BigDecimal(price), "USD", soldOn, null, PriceSource.ListingType.AUCTION, Map.of());
    }

    @Test
    void aSaleWithoutASoldDateCannotBeBuilt() {
        // An asking price has no sold date, so it can never become a comp.
        assertThatThrownBy(() -> sale(PriceSource.GradingCompany.PSA, "9", null, "100", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("never asking prices");
    }

    @Test
    void aSaleIsEitherGradedOrRaw() {
        assertThatThrownBy(() -> sale(null, "9", null, "100", SOLD)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sale(PriceSource.GradingCompany.PSA, "9", PriceSource.RawCondition.NEAR_MINT, "100", SOLD))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sale(null, null, null, "100", SOLD)).isInstanceOf(IllegalArgumentException.class);
        assertThat(sale(null, null, PriceSource.RawCondition.DAMAGED, "5", SOLD).rawCondition())
                .isEqualTo(PriceSource.RawCondition.DAMAGED);
    }

    @Test
    void rejectsImpossibleGradesAndPrices() {
        assertThatThrownBy(() -> sale(PriceSource.GradingCompany.PSA, "11", null, "100", SOLD)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sale(PriceSource.GradingCompany.PSA, "0.5", null, "100", SOLD)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sale(PriceSource.GradingCompany.PSA, "9", null, "0", SOLD)).isInstanceOf(IllegalArgumentException.class);
        assertThat(sale(PriceSource.GradingCompany.PSA, "8.5", null, "100", SOLD).grade()).isEqualByComparingTo("8.5");
    }

    @Test
    void erasSplitByLanguageAndReleaseDate() {
        PricingProperties properties = new PricingProperties("1.0.0", PriceSource.GradingCompany.PSA, "USD", BigDecimal.ONE, 3, 12,
                null, new PricingProperties.Ingestion(false, "0 0 0 * * *", Duration.ofDays(400)));

        assertThat(properties.eraKey("en", LocalDate.of(1999, 1, 9))).isEqualTo("en:WOTC");
        assertThat(properties.eraKey("en", LocalDate.of(2003, 6, 1))).isEqualTo("en:EX_TO_BW");
        assertThat(properties.eraKey("ja", LocalDate.of(2016, 3, 1))).isEqualTo("ja:XY_TO_SM");
        assertThat(properties.eraKey("en", LocalDate.of(2023, 3, 31))).isEqualTo("en:SWSH_ON");
        assertThat(properties.eraKey("en", null)).isEqualTo("en:UNKNOWN");
    }
}

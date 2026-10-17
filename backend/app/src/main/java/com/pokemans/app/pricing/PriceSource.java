package com.pokemans.app.pricing;

import com.pokemans.app.catalog.Catalog;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A source of sold comps (HLD: {@code PriceDataProvider}). Sold sales only: a sale without a sold
 * date can't be built, and there is no listing type for an asking price.
 *
 * <p>No source is licensed yet (docs/price-data-sources.md). An adapter is only added, and only
 * enabled, once its licence covers storing comps and showing them in lender and insurer reports.
 */
interface PriceSource {

    /** Stable name stored on every comp, e.g. "MANUAL". */
    String name();

    /** Licence basis stored on every comp, e.g. the plan name and terms date. */
    String licence();

    /** Sold sales of one catalog card with a sold date in {@code [from, to]}. */
    List<Sale> soldSales(Catalog.CardSummary card, LocalDate from, LocalDate to);

    enum GradingCompany { PSA, BGS, CGC, SGC, TAG, ACE }

    /** Condition of a raw (ungraded) sale. DAMAGED and ALTERED price the U outcome. */
    enum RawCondition { NEAR_MINT, LIGHTLY_PLAYED, MODERATELY_PLAYED, HEAVILY_PLAYED, DAMAGED, ALTERED }

    /** How the sale closed. There is deliberately no value for a listing that hasn't sold. */
    enum ListingType { AUCTION, FIXED_PRICE, BEST_OFFER_ACCEPTED, PRIVATE_SALE, UNKNOWN }

    /**
     * One sold sale. Graded sales have a grading company and grade (half grades as sold, e.g. 8.5);
     * raw sales have a raw condition instead.
     *
     * @param raw the source's record as received, kept for provenance
     */
    record Sale(String source, String externalSaleId, UUID catalogCardId, GradingCompany gradingCompany, BigDecimal grade,
                RawCondition rawCondition, BigDecimal price, String currency, LocalDate soldDate, URI url,
                ListingType listingType, Map<String, Object> raw) {

        public Sale {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(externalSaleId, "externalSaleId");
            Objects.requireNonNull(catalogCardId, "catalogCardId");
            Objects.requireNonNull(price, "price");
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(soldDate, "soldDate: only sold sales are comps, never asking prices");
            Objects.requireNonNull(listingType, "listingType");
            if (price.signum() <= 0) {
                throw new IllegalArgumentException("Sale price must be positive: " + price);
            }
            if ((gradingCompany == null) != (grade == null)) {
                throw new IllegalArgumentException("A graded sale needs both grading company and grade");
            }
            if ((grade == null) == (rawCondition == null)) {
                throw new IllegalArgumentException("A sale is either graded or raw with a condition");
            }
            if (grade != null && (grade.compareTo(BigDecimal.ONE) < 0 || grade.compareTo(BigDecimal.TEN) > 0)) {
                throw new IllegalArgumentException("Grade must be 1 to 10: " + grade);
            }
            raw = raw == null ? Map.of() : Map.copyOf(raw);
        }
    }
}

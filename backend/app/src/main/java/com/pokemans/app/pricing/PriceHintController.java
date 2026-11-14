package com.pokemans.app.pricing;

import com.pokemans.app.catalog.Catalog;
import com.pokemans.app.catalog.Edition;
import com.pokemans.app.catalog.Variant;
import com.pokemans.valuation.Grade;
import com.pokemans.valuation.ParameterSet;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tells a collector early, before photos, when a card they picked is clearly under the FMV minimum.
 * Only a hint: the card can still be added, and with no price table we say nothing.
 */
@RestController
@Validated
class PriceHintController {

    /**
     * @param underMinimum true only when every grade of the card's PSA price table is under the
     *                     minimum, so its FMV is under it whatever its condition
     * @param minFmv       the minimum, in dollars
     */
    record PriceHint(boolean underMinimum, BigDecimal minFmv) {
    }

    private final Catalog catalog;
    private final Prices prices;
    private final ParameterSet params;

    PriceHintController(Catalog catalog, Prices prices, ParameterSet params) {
        this.catalog = catalog;
        this.prices = prices;
        this.params = params;
    }

    @GetMapping("/api/catalog/price-hint")
    PriceHint hint(@RequestParam @NotBlank @Size(max = 100) String externalId,
                   @RequestParam(defaultValue = "en") @Pattern(regexp = "[a-z]{2}(-[a-z]{2,4})?") String language,
                   @RequestParam Edition edition,
                   @RequestParam Variant variant,
                   @RequestParam(required = false) @Size(max = 100) String stamp) {
        List<java.util.UUID> ids = catalog.printsOf(externalId, language, edition, variant,
                stamp == null || stamp.isBlank() ? null : stamp.trim());
        boolean under = !ids.isEmpty() && ids.stream().allMatch(id -> clearlyUnder(prices.latest(id, "PSA"), params.minFmv()));
        return new PriceHint(under, params.minFmv().stripTrailingZeros());
    }

    /**
     * FMV is a probability-weighted average of the grades' net prices, and each net price is at most
     * the larger of the grade's gross price and its raw floor. So when that larger figure is under
     * the minimum at every grade, the FMV is too, whatever the condition. A grade without a price
     * leaves it open: no hint.
     */
    static boolean clearlyUnder(Optional<Prices.CardPrices> table, BigDecimal minFmv) {
        if (table.isEmpty()) {
            return false;
        }
        var grades = table.get().grades();
        for (Grade g : Grade.values()) {
            Prices.GradeSnapshot s = grades.get(g);
            if (s == null || s.price() == null) {
                if (g == Grade.U) {
                    continue; // U is $0 without damaged sales
                }
                return false;
            }
            BigDecimal top = s.rawFloor() != null && s.rawFloor().price() != null && s.rawFloor().price().compareTo(s.price()) > 0
                    ? s.rawFloor().price() : s.price();
            if (top.compareTo(minFmv) >= 0) {
                return false;
            }
        }
        return true;
    }
}

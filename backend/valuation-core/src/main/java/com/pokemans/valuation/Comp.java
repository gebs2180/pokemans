package com.pokemans.valuation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/** One sold comp. Asking prices are never comps. */
public record Comp(String id, LocalDate soldDate, BigDecimal price) {

    public Comp {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(soldDate, "soldDate");
        Objects.requireNonNull(price, "price");
        if (price.signum() <= 0) {
            throw new IllegalArgumentException("Comp price must be positive: " + price);
        }
    }
}

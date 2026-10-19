package com.pokemans.valuation;

import java.math.BigDecimal;

/**
 * Cost of getting a card graded: fee, shipping and insurance per card, plus a time-to-cash
 * discount rate for the current turnaround (applied to the graded sale price).
 */
public record GradingCost(BigDecimal fee, BigDecimal shipping, BigDecimal insurance, BigDecimal timeToCashRate) {

    public GradingCost {
        java.util.Objects.requireNonNull(fee, "fee");
        java.util.Objects.requireNonNull(shipping, "shipping");
        java.util.Objects.requireNonNull(insurance, "insurance");
        java.util.Objects.requireNonNull(timeToCashRate, "timeToCashRate");
        if (fee.signum() < 0 || shipping.signum() < 0 || insurance.signum() < 0) {
            throw new IllegalArgumentException("Grading costs can't be negative");
        }
        if (timeToCashRate.signum() < 0 || timeToCashRate.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException("Time-to-cash rate must be in [0, 1): " + timeToCashRate);
        }
    }

    /** Fixed costs: fee + shipping + insurance. */
    public BigDecimal fixed() {
        return fee.add(shipping).add(insurance);
    }

    /** Waiting cost for a card expected to sell for {@code grossPrice} once graded. */
    public BigDecimal waitingCost(BigDecimal grossPrice) {
        return grossPrice.multiply(timeToCashRate, Money.MC);
    }

    /** All-in cost C for a card expected to sell for {@code grossPrice} once graded. */
    public BigDecimal total(BigDecimal grossPrice) {
        return fixed().add(waitingCost(grossPrice));
    }

    /** Graded sale price net of all grading costs. */
    public BigDecimal net(BigDecimal grossPrice) {
        return grossPrice.subtract(total(grossPrice));
    }

    /** The net price a grade is valued at: net of grading costs, in cents, never below zero. */
    public BigDecimal netPrice(BigDecimal grossPrice) {
        return Money.max(BigDecimal.ZERO, Money.cents(net(grossPrice)));
    }
}

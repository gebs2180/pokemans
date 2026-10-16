package com.pokemans.valuation;

/** Confidence grade for a valuation. D is excluded from the collateral base. */
public enum Confidence {
    A, B, C, D;

    /** One level lower, floored at D. */
    public Confidence drop() {
        return this == D ? D : values()[ordinal() + 1];
    }

    /** The less confident of the two. */
    public Confidence worse(Confidence other) {
        return ordinal() >= other.ordinal() ? this : other;
    }
}

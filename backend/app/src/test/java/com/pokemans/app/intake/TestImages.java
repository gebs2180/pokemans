package com.pokemans.app.intake;

/** Synthetic card photos for integration tests in other modules. */
public final class TestImages {

    private TestImages() {
    }

    /** A sharp, whole card on a table: passes every check. */
    public static byte[] goodFront() {
        return QualityCheckerTest.png(QualityCheckerTest.goodPhoto());
    }

    /** Another passing photo with different pixels (the duplicate check compares hashes). */
    public static byte[] goodBack() {
        return QualityCheckerTest.png(QualityCheckerTest.withCard(QualityCheckerTest.background(1700, 2300), 950, 850, 1150, -3));
    }
}

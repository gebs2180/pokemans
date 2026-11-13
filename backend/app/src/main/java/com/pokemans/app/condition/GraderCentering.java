package com.pokemans.app.condition;

import java.util.List;

/**
 * Each grader's published centering allowance, for display on the labelling and review pages only: the
 * reviewer sees what PSA, BGS, CGC and TAG would allow for the lines they drew. Nothing here prices a card
 * or caps a distribution. The centering cap and every valuation stay on {@link PsaCentering} alone
 * (CLAUDE.md: "Another provider's centering is never used").
 *
 * <p>Shares are the larger border's share of a pair in percent, 55 for 55/45, and the worse pair counts,
 * as in {@link PsaCentering}. Each table is best grade first; the first grade both sides fit is the answer,
 * and below the last one the card shows as the grader's lowest grade. A grade whose limit the grader
 * doesn't state is left out rather than guessed, so the answer can only come out lower, never higher.
 * A value marked {@code unsure} is ours, not the grader's, and waits for Zach to confirm it.
 */
final class GraderCentering {

    /**
     * @param grade         the grade as the grader writes it
     * @param maxFrontShare largest share the bigger front border may have
     * @param maxBackShare  the same for the back
     * @param unsure        why this row isn't straight from the grader's page, null when it is
     */
    record Allowance(String grade, double maxFrontShare, double maxBackShare, String unsure) {
    }

    /**
     * @param lowest what to show when no allowance fits
     * @param source where the numbers come from
     */
    record Standard(String grader, String source, String lowest, List<Allowance> allowances) {
    }

    /** PSA's own table, the one the centering cap uses, so the two can't drift apart. */
    static final Standard PSA = new Standard("PSA", "https://www.psacard.com/gradingstandards, read 2026-10-28 (PsaCentering)", "1",
            PsaCentering.STANDARDS.stream()
                    .map(t -> new Allowance(String.valueOf(t.grade()), t.maxFrontShare(), t.maxBackShare(), null))
                    .toList());

    /**
     * Beckett's centering subgrade. beckett.com/grading/scale refused our reader (HTTP 403) on 2026-11-13, so
     * every row is from the commonly quoted Beckett scale and unchecked against the page. Half grades other
     * than 9.5 are left out: 8.5 is usually quoted with 8's limits, and the better grade would win a tie.
     */
    static final Standard BGS = new Standard("BGS", "https://www.beckett.com/grading/scale (not readable 2026-11-13; quoted from memory)", "1",
            List.of(
                    bgs("Pristine 10", 50, 60),
                    bgs("Gem Mint 9.5", 55, 60),
                    // Usually quoted as "55/45 one way, 60/40 the other": we hold both ways to 55.
                    bgs("Mint 9", 55, 70),
                    bgs("NM-MT 8", 60, 80),
                    bgs("NM 7", 65, 90),
                    bgs("EX-MT 6", 70, 95),
                    bgs("EX 5", 75, 95),
                    bgs("VG-EX 4", 80, 100),
                    bgs("VG 3", 85, 100),
                    bgs("Good 2", 90, 100)));

    /**
     * CGC's standard card grading scale, read 2026-11-13. CGC gives numbers "for sports and non-sports cards"
     * and none written for TCG, so applying them to Pokémon is ours to confirm. Grades it gives no centering
     * for (9.5, 8.5, 6.5, 5.5, 5, 4, 3 and below) are left out; 7.5 is left out because it states 65/35, the
     * same as 8. A back limit CGC doesn't state is taken from the grade above, which never loosens it.
     */
    static final Standard CGC = new Standard("CGC", "https://www.cgccards.com/card-grading/grading-scale/, read 2026-11-13", "1",
            List.of(
                    // "The centering is 50/50": the back isn't stated.
                    new Allowance("Pristine 10", 50, 75, "back not stated; Gem Mint 10's 75/25 used"),
                    // "not to exceed approximately 55/45, and reverse centering is not to exceed 75/25"
                    new Allowance("Gem Mint 10", 55, 75, null),
                    // "60/40 or better for the front of the card, and 90/10 for the back"
                    new Allowance("Mint 9", 60, 90, null),
                    new Allowance("NM/Mint 8", 65, 90, "back not stated; Mint 9's 90/10 used"),
                    new Allowance("NM 7", 70, 90, "back not stated; Mint 9's 90/10 used"),
                    new Allowance("EX/NM 6", 75, 90, "back not stated; Mint 9's 90/10 used"),
                    new Allowance("VG/Ex+ 4.5", 85, 90, "back not stated; Mint 9's 90/10 used"),
                    // "may have 90/10 centering", side not said: both.
                    new Allowance("Very Good+ 3.5", 90, 90, "side not stated; applied to front and back")));

    /**
     * TAG's grading rubric, TCG column, read 2026-11-13. TAG writes every limit as approximate ("~55/45").
     * The front applies to every card; the back is given for TCG down to 8, and below that the 8's ~95/5
     * is used. Fair 1.5's ~98.33/1.67 is as printed.
     */
    static final Standard TAG = new Standard("TAG", "https://taggrading.com/pages/rubric, read 2026-11-13", "1",
            List.of(
                    tag("Pristine 10", 51, 52),
                    tag("Gem Mint 10", 55, 65),
                    tag("Mint 9", 60, 75),
                    tag("NM-MT+ 8.5", 62.5, 85),
                    tag("NM-MT 8", 65, 95),
                    tagBelow8("NM+ 7.5", 67.5),
                    tagBelow8("NM 7", 70),
                    tagBelow8("EX-MT+ 6.5", 72.5),
                    tagBelow8("EX-MT 6", 75),
                    tagBelow8("EX+ 5.5", 77.5),
                    tagBelow8("EX 5", 80),
                    tagBelow8("VG-EX+ 4.5", 82.5),
                    tagBelow8("VG-EX 4", 85),
                    tagBelow8("VG+ 3.5", 87.5),
                    tagBelow8("VG 3", 90),
                    tagBelow8("Good+ 2.5", 92.5),
                    tagBelow8("Good 2", 95),
                    tagBelow8("Fair 1.5", 98.33)));

    static final List<Standard> ALL = List.of(PSA, BGS, CGC, TAG);

    private GraderCentering() {
    }

    private static Allowance bgs(String grade, double front, double back) {
        return new Allowance(grade, front, back, "Beckett's page wasn't readable: confirm against it");
    }

    private static Allowance tag(String grade, double front, double back) {
        return new Allowance(grade, front, back, null);
    }

    private static Allowance tagBelow8(String grade, double front) {
        return new Allowance(grade, front, 95, "TCG back not stated below 8; NM-MT 8's ~95/5 used");
    }

    /**
     * The best grade this grader allows, the same rule as {@link PsaCentering#cap} with no margin: a side
     * not measured doesn't limit it, and with neither measured there is no answer (null).
     */
    static String best(Standard standard, Double frontShare, Double backShare) {
        if (frontShare == null && backShare == null) {
            return null;
        }
        for (Allowance a : standard.allowances()) {
            if (within(frontShare, a.maxFrontShare()) && within(backShare, a.maxBackShare())) {
                return a.grade();
            }
        }
        return standard.lowest();
    }

    private static boolean within(Double share, double max) {
        return share == null || share <= max + 1e-9;
    }
}

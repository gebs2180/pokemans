package com.pokemans.app.condition;

import java.util.List;

/**
 * The best PSA grade a card's centering allows, from PSA's published card grading standards
 * (https://www.psacard.com/gradingstandards, read 2026-10-28). Centering there is the larger border's
 * share of each pair, left/right and top/bottom, and the worse pair counts. PSA's wording per grade:
 *
 * <ul>
 *   <li>GEM-MT 10: "not to exceed approximately 55/45 percent on the front, and 75/25 percent on the reverse"</li>
 *   <li>MINT 9: "approximately 60/40 or better on the front and 90/10 or better on the reverse"</li>
 *   <li>NM-MT 8: 65/35 front, 90/10 back. NM 7: 70/30 front, 90/10 back</li>
 *   <li>EX-MT 6: 80/20 front. EX 5 and VG-EX 4: 85/15 front. All 90/10 back</li>
 *   <li>VG 3, GOOD 2 and FR 1.5: 90/10 front and back. PR 1 states no centering limit</li>
 * </ul>
 *
 * PSA also allows "a 5% leeway" on the front for grades 7 and up when the eye appeal is good, at the
 * grader's discretion. We don't count on it: conservative by default. FR 1.5 shares grade 2's limits
 * but half grades fold down, so anything worse than 90/10 caps at 1.
 */
final class PsaCentering {

    /**
     * @param grade         the PSA grade
     * @param maxFrontShare largest share (percent) the bigger front border may have, e.g. 55 for 55/45
     * @param maxBackShare  the same for the back
     */
    record Tolerance(int grade, double maxFrontShare, double maxBackShare) {
    }

    /** Best grade first. */
    static final List<Tolerance> STANDARDS = List.of(
            new Tolerance(10, 55, 75),
            new Tolerance(9, 60, 90),
            new Tolerance(8, 65, 90),
            new Tolerance(7, 70, 90),
            new Tolerance(6, 80, 90),
            new Tolerance(5, 85, 90),
            new Tolerance(4, 85, 90),
            new Tolerance(3, 90, 90),
            new Tolerance(2, 90, 90));

    private PsaCentering() {
    }

    /**
     * The best grade both sides allow. A side that wasn't measured doesn't limit the grade; with
     * neither measured there is no cap (null).
     *
     * @param frontShare the front's worse pair: larger border's share in percent (50 to 100), or null
     * @param backShare  the same for the back, or null
     * @param margin     added to each measured share first, for measurement error: a card measured at
     *                   54.5/45.5 with a margin of 1 counts as 55.5/44.5
     */
    static Integer cap(Double frontShare, Double backShare, double margin) {
        if (frontShare == null && backShare == null) {
            return null;
        }
        for (Tolerance t : STANDARDS) {
            if (within(frontShare, margin, t.maxFrontShare()) && within(backShare, margin, t.maxBackShare())) {
                return t.grade();
            }
        }
        return 1;
    }

    private static boolean within(Double share, double margin, double max) {
        return share == null || share + margin <= max + 1e-9;
    }
}

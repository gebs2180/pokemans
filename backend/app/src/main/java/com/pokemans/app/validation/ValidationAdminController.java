package com.pokemans.app.validation;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Validation reports for the admin (docs/validation-plan.md). Read-only; /api/admin/** is admin only. */
@RestController
class ValidationAdminController {

    private final ConditionCalibrationReport conditionCalibration;
    private final PreGradeComparisonReport preGradeComparison;

    ValidationAdminController(ConditionCalibrationReport conditionCalibration, PreGradeComparisonReport preGradeComparison) {
        this.conditionCalibration = conditionCalibration;
        this.preGradeComparison = preGradeComparison;
    }

    /** Check 1: every returned grade against the assessment in force before the card was graded. */
    @GetMapping("/api/admin/validation/condition-calibration")
    ConditionCalibration.Report conditionCalibration() {
        return conditionCalibration.compute();
    }

    /** CardGrade and the claude-vision shadow side by side per card, with the PSA grade once it is back. */
    @GetMapping("/api/admin/validation/pregrade-comparison")
    PreGradeComparisonReport.Report preGradeComparison() {
        return preGradeComparison.compute();
    }
}

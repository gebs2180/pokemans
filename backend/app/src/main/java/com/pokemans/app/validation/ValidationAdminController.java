package com.pokemans.app.validation;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Validation reports for the admin (docs/validation-plan.md). Read-only; /api/admin/** is admin only. */
@RestController
class ValidationAdminController {

    private final ConditionCalibrationReport conditionCalibration;

    ValidationAdminController(ConditionCalibrationReport conditionCalibration) {
        this.conditionCalibration = conditionCalibration;
    }

    /** Check 1: every returned grade against the assessment in force before the card was graded. */
    @GetMapping("/api/admin/validation/condition-calibration")
    ConditionCalibration.Report conditionCalibration() {
        return conditionCalibration.compute();
    }
}

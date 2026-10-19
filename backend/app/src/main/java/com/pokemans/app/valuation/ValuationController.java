package com.pokemans.app.valuation;

import com.pokemans.valuation.GradingCost;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Admin-only (by path): start valuation runs and read them back, with every input and trace. */
@RestController
class ValuationController {

    /** Per card, the grading cost of the day. */
    record GradingCostRequest(@NotNull @DecimalMin("0") @Digits(integer = 6, fraction = 2) BigDecimal fee,
                              @NotNull @DecimalMin("0") @Digits(integer = 6, fraction = 2) BigDecimal shipping,
                              @NotNull @DecimalMin("0") @Digits(integer = 6, fraction = 2) BigDecimal insurance,
                              @NotNull @DecimalMin("0") @DecimalMax(value = "0.5") @Digits(integer = 1, fraction = 4)
                              BigDecimal timeToCashRate) {
    }

    /** @param gradingCost null for the configured defaults */
    record RunRequest(@Valid GradingCostRequest gradingCost, @Size(max = 1000) String note) {
    }

    record Defaults(String parameterSet, String gradingCompany, GradingCost gradingCost) {
    }

    record Replay(boolean reproduced, List<String> differences) {
    }

    private final ValuationService service;
    private final Valuations valuations;

    ValuationController(ValuationService service, Valuations valuations) {
        this.service = service;
        this.valuations = valuations;
    }

    @GetMapping("/api/admin/valuation/defaults")
    Defaults defaults() {
        return new Defaults(service.parameterSet().version(), service.gradingCompany(), service.defaultGradingCost());
    }

    @PostMapping("/api/admin/orgs/{orgId}/collections/{collectionId}/valuation-runs")
    @ResponseStatus(HttpStatus.CREATED)
    Valuations.RunSummary start(@PathVariable UUID orgId, @PathVariable UUID collectionId, @Valid @RequestBody RunRequest request) {
        GradingCost cost = request.gradingCost() == null ? service.defaultGradingCost()
                : new GradingCost(request.gradingCost().fee(), request.gradingCost().shipping(), request.gradingCost().insurance(),
                request.gradingCost().timeToCashRate());
        return valuations.summary(service.run(orgId, collectionId, cost, blankToNull(request.note())).getId());
    }

    @GetMapping("/api/admin/orgs/{orgId}/collections/{collectionId}/valuation-runs")
    List<Valuations.RunSummary> list(@PathVariable UUID orgId, @PathVariable UUID collectionId) {
        return valuations.forCollection(orgId, collectionId);
    }

    @GetMapping("/api/admin/valuation-runs/{runId}")
    Valuations.RunDetail detail(@PathVariable UUID runId) {
        return valuations.detail(runId);
    }

    /** Re-values the run from its stored inputs alone: proof that the report can be reproduced. */
    @GetMapping("/api/admin/valuation-runs/{runId}/replay")
    Replay replay(@PathVariable UUID runId) {
        List<String> differences = service.replay(valuations.require(runId));
        return new Replay(differences.isEmpty(), differences);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}

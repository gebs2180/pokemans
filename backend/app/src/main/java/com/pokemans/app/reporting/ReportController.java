package com.pokemans.app.reporting;

import com.pokemans.app.identity.OrgAccess;
import com.pokemans.app.identity.OrgType;
import com.pokemans.app.intake.CardItems;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Report PDFs. The admin issues them from a valuation run; members of the collection's org can list
 * and download the issued ones. A download is a short-lived signed link, returned as JSON rather than
 * a redirect so the browser opens it without the API token.
 */
@RestController
class ReportController {

    private static final OrgType[] COLLECTION_ORGS = {OrgType.COLLECTOR, OrgType.CONTRIBUTOR};

    /** @param lenderAlpha the lender's own alpha, if they gave one: the report then shows the advance at it */
    record IssueRequest(@NotNull Report.Type type,
                        @DecimalMin("0") @DecimalMax("1") @Digits(integer = 1, fraction = 4) BigDecimal lenderAlpha) {
    }

    record ReportView(UUID id, UUID runId, UUID collectionId, Report.Type type, BigDecimal lenderAlpha, String templateVersion,
                      String sha256, long sizeBytes, Instant issuedAt) {
    }

    record DownloadLink(URI url) {
    }

    private final ReportService service;
    private final OrgAccess access;
    private final CardItems cardItems;

    ReportController(ReportService service, OrgAccess access, CardItems cardItems) {
        this.service = service;
        this.access = access;
        this.cardItems = cardItems;
    }

    @PostMapping("/api/admin/valuation-runs/{runId}/reports")
    @ResponseStatus(HttpStatus.CREATED)
    ReportView issue(@PathVariable UUID runId, @Valid @RequestBody IssueRequest request) {
        return view(service.issue(runId, request.type(), request.lenderAlpha()));
    }

    @GetMapping("/api/admin/valuation-runs/{runId}/reports")
    List<ReportView> forRun(@PathVariable UUID runId) {
        return service.forRun(runId).stream().map(ReportController::view).toList();
    }

    @GetMapping("/api/admin/reports/{reportId}/download")
    DownloadLink adminDownload(@PathVariable UUID reportId) {
        return new DownloadLink(service.download(null, reportId));
    }

    @GetMapping("/api/orgs/{orgId}/collections/{collectionId}/reports")
    List<ReportView> forCollection(@PathVariable UUID orgId, @PathVariable UUID collectionId) {
        access.require(orgId, COLLECTION_ORGS);
        cardItems.requireCollection(orgId, collectionId);
        return service.forCollection(orgId, collectionId).stream().map(ReportController::view).toList();
    }

    @GetMapping("/api/orgs/{orgId}/reports/{reportId}/download")
    DownloadLink download(@PathVariable UUID orgId, @PathVariable UUID reportId) {
        access.require(orgId, COLLECTION_ORGS);
        return new DownloadLink(service.download(orgId, reportId));
    }

    private static ReportView view(Report r) {
        return new ReportView(r.getId(), r.getRunId(), r.getCollectionId(), r.getType(), r.getLenderAlpha(), r.getTemplateVersion(),
                r.getSha256(), r.getSizeBytes(), r.getIssuedAt());
    }
}

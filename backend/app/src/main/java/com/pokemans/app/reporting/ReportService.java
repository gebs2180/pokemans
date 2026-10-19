package com.pokemans.app.reporting;

import com.pokemans.app.identity.CurrentUser;
import com.pokemans.app.storage.BlobStore;
import com.pokemans.app.storage.StorageProperties;
import com.pokemans.app.valuation.CardInputs;
import com.pokemans.app.valuation.Valuations;
import java.math.BigDecimal;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Issues report PDFs from stored valuation runs, stores each once with its hash (in the private photo
 * bucket, under reports/), and hands out short-lived download links. No access checks: the
 * controllers have done them.
 */
@Service
class ReportService {

    private final Valuations valuations;
    private final ReportRepository reports;
    private final BlobStore blobs;
    private final StorageProperties storage;
    private final CurrentUser currentUser;
    private final ReportRenderer renderer = new ReportRenderer();

    ReportService(Valuations valuations, ReportRepository reports, BlobStore blobs, StorageProperties storage, CurrentUser currentUser) {
        this.valuations = valuations;
        this.reports = reports;
        this.blobs = blobs;
        this.storage = storage;
        this.currentUser = currentUser;
    }

    /** @param lenderAlpha only for a lender report, and only when the lender has given one */
    @Transactional
    Report issue(UUID runId, Report.Type type, BigDecimal lenderAlpha) {
        if (type == Report.Type.INSURER && lenderAlpha != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Alpha applies to lender reports only");
        }
        Valuations.RunDetail detail = valuations.detail(runId);
        UUID reportId = UUID.randomUUID();
        // The PDF is stamped with the same instant the row stores.
        Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        byte[] pdf = render(detail, type, reportId, issuedAt, lenderAlpha);
        String key = "reports/" + detail.run().orgId() + "/" + reportId + ".pdf";
        if (!blobs.putIfAbsent(storage.photoBucket(), key, pdf, "application/pdf")) {
            throw new IllegalStateException("Report object already exists: " + key);
        }
        return reports.save(new Report(reportId, runId, detail.run().orgId(), detail.run().collectionId(), type, lenderAlpha,
                ReportModel.TEMPLATE_VERSION, key, sha256(pdf), pdf.length, currentUser.id(), issuedAt));
    }

    byte[] render(Valuations.RunDetail detail, Report.Type type, UUID reportId, Instant issuedAt, BigDecimal lenderAlpha) {
        Map<String, Object> model = switch (type) {
            case LENDER -> ReportModel.lender(detail, reportId, issuedAt, lenderAlpha);
            case INSURER -> ReportModel.insurer(detail, reportId, issuedAt, this::photo);
        };
        String template = type == Report.Type.LENDER ? "lender" : "insurer";
        return renderer.pdf(renderer.xhtml(template, model), reportId, issuedAt, model.get("title") + ": " + detail.run().collectionName());
    }

    private String photo(CardInputs.Photo photo) {
        String key = photo.cropKey() != null ? photo.cropKey() : photo.storageKey();
        return Thumbnails.dataUri(blobs.read(storage.photoBucket(), key));
    }

    @Transactional(readOnly = true)
    List<Report> forRun(UUID runId) {
        return reports.findByRunIdOrderByIssuedAtDesc(runId);
    }

    @Transactional(readOnly = true)
    List<Report> forCollection(UUID orgId, UUID collectionId) {
        return reports.findByOrgIdAndCollectionIdOrderByIssuedAtDesc(orgId, collectionId);
    }

    /** @param orgId the org the report must belong to, or null for the admin */
    @Transactional(readOnly = true)
    URI download(UUID orgId, UUID reportId) {
        Report report = (orgId == null ? reports.findById(reportId) : reports.findByIdAndOrgId(reportId, orgId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Report not found"));
        return blobs.presignDownload(storage.photoBucket(), report.getStorageKey());
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

package com.pokemans.app.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.pokemans.app.valuation.ValuationFixtures;
import com.pokemans.app.valuation.Valuations;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

/**
 * The lender and insurer reports for a fixed collection, compared with the text checked in under
 * {@code src/test/resources/golden}. A change to the math or the templates shows up as a diff to review.
 * Run with {@code -Dgolden.update=true} to rewrite the golden files (and say why in the commit).
 */
class GoldenReportTest {

    private static final UUID REPORT = UUID.fromString("00000000-0000-4000-8000-00000000abcd");
    private static final Instant ISSUED = Instant.parse("2026-10-18T16:30:00Z");
    private static final Path GOLDEN = Path.of("src/test/resources/golden");

    private final ReportRenderer renderer = new ReportRenderer();

    @Test
    void lenderReport() throws Exception {
        Valuations.RunDetail detail = ValuationFixtures.detail();
        Map<String, Object> model = ReportModel.lender(detail, REPORT, ISSUED, new BigDecimal("0.6"));
        byte[] pdf = renderer.pdf(renderer.xhtml("lender", model), REPORT, ISSUED, "Collateral report");

        String text = text(pdf);
        assertThat(text).contains("Collateral report", "The lender sets alpha", "Grade first", "Traceability",
                "unresolved authenticity flag (awaiting verification)", "custody not verified (lapsed)",
                "photographed lot: lots aren't valued", "Correlation haircut on set");
        assertThat(text).doesNotContain("null");
        keep("lender-report.pdf", pdf);
        matchesGolden("lender-report.txt", text);
        sameBytesTwice(pdf, renderer.pdf(renderer.xhtml("lender", model), REPORT, ISSUED, "Collateral report"));
    }

    @Test
    void insurerReport() throws Exception {
        Valuations.RunDetail detail = ValuationFixtures.detail();
        String photo = Thumbnails.dataUri(png());
        Map<String, Object> model = ReportModel.insurer(detail, REPORT, ISSUED, p -> photo);
        byte[] pdf = renderer.pdf(renderer.xhtml("insurer", model), REPORT, ISSUED, "Replacement value report");

        String text = text(pdf);
        assertThat(text).contains("Replacement value report", "Not covered", "authenticity doubted (COUNTERFEIT): not insured",
                "captured 2026-10-12 18:04 UTC");
        assertThat(text).doesNotContain("null");
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getDocumentInformation().getProducer()).isEqualTo(ReportRenderer.PRODUCER);
            assertThat(doc.getDocumentInformation().getCreationDate().toInstant()).isEqualTo(ISSUED);
        }
        keep("insurer-report.pdf", pdf);
        matchesGolden("insurer-report.txt", text);
    }

    @Test
    void lenderReportWithoutAlphaOnlySuggestsARange() throws Exception {
        Map<String, Object> model = ReportModel.lender(ValuationFixtures.detail(), REPORT, ISSUED, null);
        String text = text(renderer.pdf(renderer.xhtml("lender", model), REPORT, ISSUED, "Collateral report"));

        assertThat(text).contains("The lender sets alpha.").doesNotContain("set by the lender, the advance");
    }

    private static void sameBytesTwice(byte[] first, byte[] second) {
        assertThat(ReportService.sha256(second)).isEqualTo(ReportService.sha256(first));
    }

    private static String text(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc).replace("\r\n", "\n");
        }
    }

    /** The rendered PDFs, to look at: build/golden-reports. */
    private static void keep(String name, byte[] pdf) throws IOException {
        Path dir = Path.of("build/golden-reports");
        Files.createDirectories(dir);
        Files.write(dir.resolve(name), pdf);
    }

    private static void matchesGolden(String name, String text) throws IOException {
        Path file = GOLDEN.resolve(name);
        if (Boolean.getBoolean("golden.update") || !Files.exists(file)) {
            Files.createDirectories(GOLDEN);
            Files.writeString(file, text, StandardCharsets.UTF_8);
        }
        assertThat(text).isEqualTo(Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }

    private static byte[] png() throws IOException {
        BufferedImage image = new BufferedImage(630, 880, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(240, 200, 40));
        g.fillRect(0, 0, 630, 880);
        g.setColor(new Color(40, 90, 200));
        g.fillRect(40, 40, 550, 800);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}

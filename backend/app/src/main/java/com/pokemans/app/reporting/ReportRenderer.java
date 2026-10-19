package com.pokemans.app.reporting;

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Calendar;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * Report templates to XHTML (Thymeleaf) and XHTML to PDF (OpenHTMLtoPDF), all in the JVM. The fonts
 * are bundled (Noto Sans, SIL OFL) so the layout doesn't depend on the host. The PDF's dates and
 * document id come from the report itself, not the clock, so the same report renders the same bytes.
 */
final class ReportRenderer {

    static final String PRODUCER = "pokemans reports";
    private static final String FONT = "Noto Sans";

    private final TemplateEngine templates;
    private final byte[] regular;
    private final byte[] bold;

    ReportRenderer() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("reports/templates/");
        resolver.setSuffix(".xhtml");
        resolver.setTemplateMode(TemplateMode.XML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(true);
        this.templates = new TemplateEngine();
        this.templates.setTemplateResolver(resolver);
        this.regular = resource("reports/fonts/NotoSans-Regular.ttf");
        this.bold = resource("reports/fonts/NotoSans-Bold.ttf");
    }

    String xhtml(String template, Map<String, Object> model) {
        Context context = new Context();
        context.setVariable("m", model);
        return templates.process(template, context);
    }

    byte[] pdf(String xhtml, UUID reportId, Instant issuedAt, String title) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.useFont(() -> new java.io.ByteArrayInputStream(regular), FONT, 400, BaseRendererBuilder.FontStyle.NORMAL, true);
            builder.useFont(() -> new java.io.ByteArrayInputStream(bold), FONT, 700, BaseRendererBuilder.FontStyle.NORMAL, true);
            builder.withHtmlContent(xhtml, null);
            builder.withProducer(PRODUCER);
            builder.toStream(out);
            builder.run();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return stamp(out.toByteArray(), reportId, issuedAt, title);
    }

    /** Fixes the document's dates and id to the report's own, instead of the render time. */
    private static byte[] stamp(byte[] pdf, UUID reportId, Instant issuedAt, String title) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            Calendar when = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            when.setTimeInMillis(issuedAt.toEpochMilli());
            PDDocumentInformation info = doc.getDocumentInformation();
            info.setCreationDate(when);
            info.setModificationDate(when);
            info.setProducer(PRODUCER);
            info.setCreator(PRODUCER);
            info.setTitle(title);
            byte[] id = md5(reportId.toString());
            COSArray ids = new COSArray();
            ids.add(new COSString(id));
            ids.add(new COSString(id));
            doc.getDocument().getTrailer().setItem(COSName.ID, ids);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] md5(String s) {
        try {
            return MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] resource(String path) {
        try (InputStream in = ReportRenderer.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing " + path);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

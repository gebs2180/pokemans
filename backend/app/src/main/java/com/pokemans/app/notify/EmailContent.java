package com.pokemans.app.notify;

import java.util.List;
import org.springframework.web.util.HtmlUtils;

/** An email's subject and its HTML and plain-text bodies. */
record EmailContent(String subject, String html, String text) {

    /**
     * A plain, short email: a few paragraphs, one link to act on, and a footer saying why it came. Paragraphs are
     * plain text, escaped for the HTML version.
     */
    static EmailContent of(String subject, List<String> paragraphs, String linkText, String link, String footer) {
        StringBuilder html = new StringBuilder();
        StringBuilder text = new StringBuilder();
        html.append("<!doctype html><html><body style=\"font-family:-apple-system,Segoe UI,Helvetica,Arial,sans-serif;"
                + "font-size:15px;line-height:1.5;color:#1a1a1a;max-width:560px;margin:0 auto;padding:16px\">");
        for (String p : paragraphs) {
            html.append("<p>").append(escape(p).replace("\n", "<br>")).append("</p>");
            text.append(p).append("\n\n");
        }
        html.append("<p><a href=\"").append(escape(link)).append("\" style=\"color:#1a56db\">").append(escape(linkText)).append("</a></p>");
        text.append(linkText).append(": ").append(link).append("\n\n");
        html.append("<p style=\"font-size:12px;color:#666;border-top:1px solid #ddd;padding-top:12px;margin-top:24px\">")
                .append(escape(footer)).append("</p></body></html>");
        text.append("--\n").append(footer).append("\n");
        return new EmailContent(subject, html.toString(), text.toString());
    }

    private static String escape(String s) {
        return HtmlUtils.htmlEscape(s);
    }
}

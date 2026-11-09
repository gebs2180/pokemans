package com.pokemans.app.pricing;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Pings a healthchecks.io-style check after each nightly pricing run (deep review A6): the check URL on
 * success, {@code <url>/fail} on failure, with a one-line summary as the body. A run that never reports in
 * (crashed, skipped as busy, scheduler off) shows up as a missed ping. Off when the URL is blank.
 * A ping that can't be delivered is logged and never fails the run.
 */
@Component
class PricingHealthcheck {

    private static final Logger log = LoggerFactory.getLogger(PricingHealthcheck.class);

    /** Sends one ping. A seam for tests; the default posts over HTTP. */
    interface Transport {
        void post(URI uri, String body) throws Exception;
    }

    private final String url;
    private final Transport transport;

    @Autowired
    PricingHealthcheck(@Value("${pokemans.pricing.ingestion.healthcheck-url:}") String url) {
        this(url, httpTransport());
    }

    PricingHealthcheck(String url, Transport transport) {
        this.url = url == null ? "" : url.strip().replaceAll("/+$", "");
        this.transport = transport;
    }

    boolean enabled() {
        return !url.isEmpty();
    }

    void success(String summary) {
        send("", summary);
    }

    void fail(String summary) {
        send("/fail", summary);
    }

    private void send(String suffix, String body) {
        if (!enabled()) {
            return;
        }
        try {
            transport.post(URI.create(url + suffix), body);
        } catch (Exception e) {
            log.warn("Pricing healthcheck ping{} failed: {}", suffix, e.toString());
        }
    }

    private static Transport httpTransport() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return (uri, body) -> {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "text/plain; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("HTTP " + response.statusCode());
            }
        };
    }
}

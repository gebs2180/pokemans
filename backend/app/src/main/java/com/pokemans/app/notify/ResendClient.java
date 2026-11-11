package com.pokemans.app.notify;

import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Sends one email over <a href="https://resend.com/docs/api-reference/emails/send-email">Resend's API</a>: POST
 * /emails with the key as a bearer token. The idempotency key makes a retry within 24 hours return the first send
 * instead of sending again.
 */
class ResendClient {

    private static final ParameterizedTypeReference<Map<String, Object>> OBJECT = new ParameterizedTypeReference<>() {
    };

    /** One email. {@code idempotencyKey} is the same on every try of the same email. */
    record Message(String from, String to, String subject, String html, String text, String idempotencyKey) {
    }

    /**
     * Resend didn't take the email. Retryable for no answer, a timeout, rate limiting (429) and its own errors (5xx);
     * any other 4xx is about the email itself and won't change on a retry. The message holds the status and Resend's
     * answer, never the request's headers.
     */
    static class Failure extends RuntimeException {

        private final boolean retryable;

        Failure(String message, boolean retryable, Throwable cause) {
            super(message, cause);
            this.retryable = retryable;
        }

        boolean retryable() {
            return retryable;
        }
    }

    private final RestClient http;

    ResendClient(EmailProperties properties) {
        this(properties, RestClient.builder().requestFactory(requests(properties)));
    }

    /** For tests, with a builder bound to a fake server. */
    ResendClient(EmailProperties properties, RestClient.Builder builder) {
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new IllegalStateException("pokemans.notify.email.api-key (RESEND_API_KEY) must be set when NOTIFY_EMAIL_ENABLED is true");
        }
        this.http = builder.baseUrl(properties.baseUrl().toString())
                .defaultHeader("Authorization", "Bearer " + properties.apiKey())
                .build();
    }

    static ClientHttpRequestFactory requests(EmailProperties properties) {
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build());
        requests.setReadTimeout(properties.timeout());
        return new BufferingClientHttpRequestFactory(requests);
    }

    /** Sends it and returns Resend's id for it. */
    String send(Message m) {
        Map<String, Object> answer;
        try {
            answer = http.post().uri("/emails")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", m.idempotencyKey())
                    .body(Map.of("from", m.from(), "to", List.of(m.to()), "subject", m.subject(), "html", m.html(), "text", m.text()))
                    .retrieve()
                    .body(OBJECT);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            throw new Failure("HTTP " + status + ": " + shorten(e.getResponseBodyAsString()), status == 429 || status >= 500, e);
        } catch (ResourceAccessException e) {
            throw new Failure("No answer: " + shorten(e.getMessage()), true, e);
        } catch (RestClientException e) {
            throw new Failure("Unreadable answer: " + shorten(e.getMessage()), true, e);
        }
        Object id = answer == null ? null : answer.get("id");
        if (id == null) {
            throw new Failure("Answer had no id", true, null);
        }
        return id.toString();
    }

    private static String shorten(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 500 ? s.substring(0, 500) + "…" : s;
    }
}

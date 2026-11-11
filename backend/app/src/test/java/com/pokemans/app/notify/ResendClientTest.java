package com.pokemans.app.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ResendClientTest {

    private final FakeResend resend = new FakeResend();

    @AfterEach
    void stop() {
        resend.close();
    }

    private EmailProperties properties(String key, Duration timeout) {
        return new EmailProperties(true, key, "Rawmark <notify@rawmark.app>", resend.url(), URI.create("https://beta.rawmark.app"),
                Duration.ofSeconds(2), timeout, Duration.ofSeconds(20), 20, 8, "-");
    }

    private ResendClient client() {
        return new ResendClient(properties("re_test_key", Duration.ofSeconds(5)));
    }

    private static ResendClient.Message message() {
        return new ResendClient.Message("Rawmark <notify@rawmark.app>", "sam@example.com", "Sold: Charizard", "<p>Hi</p>", "Hi", "key-1");
    }

    @Test
    void sendsTheEmailWithTheKeyAndIdempotencyKeyAndReturnsResendsId() {
        resend.answerNext(200, "{\"id\":\"49a3999c-0ce1-4ea6-ab68-afcd6dc2e794\"}");

        String id = client().send(message());

        assertThat(id).isEqualTo("49a3999c-0ce1-4ea6-ab68-afcd6dc2e794");
        FakeResend.Request r = resend.requests().getFirst();
        assertThat(r.authorization()).isEqualTo("Bearer re_test_key");
        assertThat(r.idempotencyKey()).isEqualTo("key-1");
        assertThat(r.contentType()).startsWith("application/json");
        assertThat(r.to()).isEqualTo("sam@example.com");
        assertThat(r.field("from")).isEqualTo("Rawmark <notify@rawmark.app>");
        assertThat(r.field("subject")).isEqualTo("Sold: Charizard");
        assertThat(r.field("html")).isEqualTo("<p>Hi</p>");
        assertThat(r.field("text")).isEqualTo("Hi");
    }

    @Test
    void rateLimitsAndServerErrorsAreRetryable() {
        resend.answerNext(429, "{\"name\":\"rate_limit_exceeded\"}");
        resend.answerNext(503, "{\"name\":\"internal_server_error\"}");

        assertThatThrownBy(() -> client().send(message())).isInstanceOfSatisfying(ResendClient.Failure.class, f -> {
            assertThat(f.retryable()).isTrue();
            assertThat(f.getMessage()).startsWith("HTTP 429").contains("rate_limit_exceeded");
        });
        assertThatThrownBy(() -> client().send(message())).isInstanceOfSatisfying(ResendClient.Failure.class,
                f -> assertThat(f.retryable()).isTrue());
    }

    @Test
    void aRefusedEmailIsNotRetriedAndTheErrorNeverHoldsTheKey() {
        resend.answerNext(422, "{\"name\":\"validation_error\",\"message\":\"Invalid `to` field.\"}");

        assertThatThrownBy(() -> client().send(message())).isInstanceOfSatisfying(ResendClient.Failure.class, f -> {
            assertThat(f.retryable()).isFalse();
            assertThat(f.getMessage()).startsWith("HTTP 422").contains("Invalid `to` field.").doesNotContain("re_test_key");
        });
    }

    @Test
    void noAnswerInTimeIsRetryable() {
        resend.answerNextAfter(Duration.ofSeconds(3));
        ResendClient slow = new ResendClient(properties("re_test_key", Duration.ofMillis(300)));

        assertThatThrownBy(() -> slow.send(message())).isInstanceOfSatisfying(ResendClient.Failure.class,
                f -> assertThat(f.retryable()).isTrue());
    }

    @Test
    void anAnswerWithoutAnIdIsRetryable() {
        resend.answerNext(200, "{}");

        assertThatThrownBy(() -> client().send(message())).isInstanceOfSatisfying(ResendClient.Failure.class,
                f -> assertThat(f.retryable()).isTrue());
    }

    @Test
    void sendingOnWithoutAKeyFailsAtStartup() {
        assertThatThrownBy(() -> new ResendClient(properties(" ", Duration.ofSeconds(5)))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RESEND_API_KEY");
    }

    @Test
    void settingsNeverPrintTheKey() {
        assertThat(properties("re_test_key", Duration.ofSeconds(5)).toString()).doesNotContain("re_test_key").contains("apiKey=***");
    }

    @Test
    void backoffDoublesFromAMinuteUpToSixHours() {
        assertThat(EmailSender.backoff(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(EmailSender.backoff(2)).isEqualTo(Duration.ofMinutes(2));
        assertThat(EmailSender.backoff(4)).isEqualTo(Duration.ofMinutes(8));
        assertThat(EmailSender.backoff(30)).isEqualTo(Duration.ofHours(6));
    }
}

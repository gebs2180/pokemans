package com.pokemans.app.condition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** CardGrade's HTTP side: what each documented error does to the request (api-docs, 2026-11-10). */
class CardGradeCallsTest {

    private static final PreGradeProvider.Photos PHOTOS = new PreGradeProvider.Photos(
            new PreGradeProvider.Image(new byte[] {1, 2, 3}, "image/jpeg"), new PreGradeProvider.Image(new byte[] {4, 5}, "image/jpeg"));

    private MutableClock clock;
    private MockRestServiceServer server;
    private CardGradePreGradeProvider provider;

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-10T12:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @BeforeEach
    void setUp() {
        provider(10);
    }

    private void provider(int gradesPerMinute) {
        clock = new MutableClock();
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        ConditionProperties properties = new ConditionProperties(null, null, 1.5, new BigDecimal("0.10"), new BigDecimal("0.25"),
                new BigDecimal("0.25"), new BigDecimal("0.02"), null,
                new ConditionProperties.CardGrade(true, "cgrd_test", URI.create("https://cardgrade.test"), "PSA",
                        Duration.ofSeconds(30), gradesPerMinute, 50, Duration.ofHours(1)), null);
        provider = new CardGradePreGradeProvider(properties, builder, clock);
    }

    @Test
    void submitSendsBearerKeyAndIdempotencyKey() {
        server.expect(once(), requestTo("https://cardgrade.test/api/v1/grade"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer cgrd_test"))
                .andExpect(header("Idempotency-Key", "req-1"))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"id\":\"g-1\",\"status\":\"pending\",\"creditsRemaining\":71}"));

        assertThat(provider.submit(PHOTOS, "req-1")).isEqualTo("g-1");
        server.verify();
    }

    @Test
    void outOfCreditsFailsWithoutRetryingAndStopsLaterSubmitsCallingAtAll() {
        server.expect(once(), requestTo("https://cardgrade.test/api/v1/grade"))
                .andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"Insufficient credits\",\"code\":\"INSUFFICIENT_CREDITS\"}"));

        assertThatThrownBy(() -> provider.submit(PHOTOS, "req-1")).isInstanceOf(PreGradeProvider.Refused.class)
                .hasMessageContaining("INSUFFICIENT_CREDITS");
        // No second request reaches the server: the mock would fail on an unexpected call.
        assertThatThrownBy(() -> provider.submit(PHOTOS, "req-2")).isInstanceOf(PreGradeProvider.Refused.class)
                .hasMessageContaining("out of credits");
        server.verify();

        // After the pause it tries again: the month may have rolled over.
        clock.now = clock.now.plus(Duration.ofHours(1)).plusSeconds(1);
        server.reset();
        server.expect(once(), requestTo("https://cardgrade.test/api/v1/grade"))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("{\"id\":\"g-3\",\"creditsRemaining\":80}"));
        assertThat(provider.submit(PHOTOS, "req-3")).isEqualTo("g-3");
    }

    @Test
    void theLastCreditStopsTheNextSubmitBeforeItIsSent() {
        server.expect(once(), requestTo("https://cardgrade.test/api/v1/grade"))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("{\"id\":\"g-1\",\"creditsRemaining\":0}"));

        assertThat(provider.submit(PHOTOS, "req-1")).isEqualTo("g-1");
        assertThatThrownBy(() -> provider.submit(PHOTOS, "req-2")).isInstanceOf(PreGradeProvider.Refused.class);
        server.verify();
    }

    @Test
    void rateLimitedWaitsForRetryAfter() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "17");
        server.expect(once(), requestTo("https://cardgrade.test/api/v1/grade"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(headers).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"Rate limit exceeded\",\"code\":\"RATE_LIMITED\"}"));

        assertThatThrownBy(() -> provider.submit(PHOTOS, "req-1")).isInstanceOfSatisfying(PreGradeProvider.Busy.class,
                busy -> assertThat(busy.retryAfter()).isEqualTo(Duration.ofSeconds(17)));
    }

    @Test
    void ourOwnLimitHoldsTheEleventhGradeInAMinuteWithoutCallingCardGrade() {
        provider(2);
        server.expect(org.springframework.test.web.client.ExpectedCount.times(2), requestTo("https://cardgrade.test/api/v1/grade"))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("{\"id\":\"g\"}"));

        provider.submit(PHOTOS, "a");
        clock.now = clock.now.plusSeconds(20);
        provider.submit(PHOTOS, "b");
        assertThatThrownBy(() -> provider.submit(PHOTOS, "c")).isInstanceOfSatisfying(PreGradeProvider.Busy.class,
                busy -> assertThat(busy.retryAfter()).isEqualTo(Duration.ofSeconds(41)));
        server.verify();
    }

    @Test
    void badKeyOrPlanIsRefused() {
        server.expect(once(), requestTo("https://cardgrade.test/api/v1/grade"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"Your plan does not include API access\",\"code\":\"PLAN_REQUIRED\"}"));

        assertThatThrownBy(() -> provider.submit(PHOTOS, "req-1")).isInstanceOf(PreGradeProvider.Refused.class)
                .hasMessageContaining("PLAN_REQUIRED");
    }

    @Test
    void serverErrorIsLeftForTheWorkerToRetry() {
        server.expect(once(), requestTo("https://cardgrade.test/api/v1/grade")).andRespond(withServerError());

        assertThatThrownBy(() -> provider.submit(PHOTOS, "req-1")).isInstanceOf(RestClientResponseException.class);
    }

    @Test
    void pollReadsTheGrading() {
        server.expect(once(), requestTo("https://cardgrade.test/api/v1/grade/g-1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"id\":\"g-1\",\"status\":\"processing\"}", MediaType.APPLICATION_JSON));

        assertThat(provider.poll("g-1")).isInstanceOf(PreGradeProvider.Pending.class);
    }
}

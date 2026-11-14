package com.pokemans.app.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class CardPickerTest {

    @Test
    void splitsANameFromACollectorNumber() {
        assertThat(CardPicker.parse("Charizard 4/102")).isEqualTo(new CardPicker.Query("Charizard", "4/102"));
        assertThat(CardPicker.parse("  charizard  #4 ")).isEqualTo(new CardPicker.Query("charizard", "4"));
        assertThat(CardPicker.parse("Pikachu TG05/TG30")).isEqualTo(new CardPicker.Query("Pikachu", "TG05/TG30"));
        assertThat(CardPicker.parse("Lugia V 186")).isEqualTo(new CardPicker.Query("Lugia V", "186"));
        assertThat(CardPicker.parse("Metagross delta")).isEqualTo(new CardPicker.Query("Metagross delta", null));
        // Long words with digits are names, not numbers.
        assertThat(CardPicker.parse("Porygon2")).isEqualTo(new CardPicker.Query("Porygon2", null));
        assertThat(CardPicker.parse("4/102")).isEqualTo(new CardPicker.Query("", "4/102"));
    }

    @Test
    void showsTheNumberAsPrinted() {
        assertThat(CardPicker.printed("4", 102)).isEqualTo("4/102");
        assertThat(CardPicker.printed("TG05", 30)).isEqualTo("TG05");
        assertThat(CardPicker.printed("4", null)).isEqualTo("4");
    }

    @Test
    void limitsEachUserPerWindow() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-13T10:00:00Z"));
        Clock clock = new Clock() {
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
                return now.get();
            }
        };
        CatalogRateLimiter limiter = new CatalogRateLimiter(3, Duration.ofMinutes(1), clock);
        limiter.acquire("a");
        limiter.acquire("a");
        limiter.acquire("a");
        assertThatThrownBy(() -> limiter.acquire("a")).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("429");
        limiter.acquire("b"); // others aren't held up
        now.set(now.get().plusSeconds(61));
        limiter.acquire("a");
    }
}

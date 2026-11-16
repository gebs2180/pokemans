package com.pokemans.app.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.pokemans.app.catalog.IdentifyPolicy.Decision;
import com.pokemans.app.catalog.IdentifyPolicy.ReadingState;
import com.pokemans.app.catalog.IdentifyPolicy.Step;
import com.pokemans.app.catalog.IdentifyPolicy.Why;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class IdentifyPolicyTest {

    private static final Instant NOW = Instant.parse("2026-11-15T12:00:00Z");

    private static Decision decide(ReadingState reading) {
        return IdentifyPolicy.decide(false, false, true, NOW.minusSeconds(10), true, true, reading, NOW);
    }

    private static ReadingState done(Duration ago, boolean matchFailed, int suggestions) {
        return new ReadingState(CardReading.Status.DONE, NOW.minus(ago).minusSeconds(5), NOW.minus(ago), matchFailed, suggestions);
    }

    @Test
    void anAnswerOrMissingPhotosComeFirst() {
        assertThat(IdentifyPolicy.decide(true, true, true, null, true, true, null, NOW)).isEqualTo(new Decision(Step.IDENTIFIED, null));
        assertThat(IdentifyPolicy.decide(false, true, false, null, true, true, null, NOW)).isEqualTo(new Decision(Step.PROPOSED, null));
        assertThat(IdentifyPolicy.decide(false, false, false, null, true, true, null, NOW)).isEqualTo(new Decision(Step.PHOTOS, null));
        assertThat(IdentifyPolicy.decide(false, false, true, NOW, false, true, null, NOW)).isEqualTo(new Decision(Step.PICK, Why.READER_OFF));
    }

    @Test
    void waitsForTheReaderOnlySoLong() {
        assertThat(decide(null).step()).isEqualTo(Step.READING);
        assertThat(IdentifyPolicy.decide(false, false, true, NOW.minus(Duration.ofMinutes(5)), true, true, null, NOW))
                .isEqualTo(new Decision(Step.PICK, Why.NOT_READ));
        assertThat(decide(new ReadingState(CardReading.Status.PENDING, NOW.minusSeconds(30), null, false, 0)).step())
                .isEqualTo(Step.READING);
        assertThat(decide(new ReadingState(CardReading.Status.PENDING, NOW.minus(Duration.ofMinutes(3)), null, false, 0)))
                .isEqualTo(new Decision(Step.PICK, Why.READING_SLOW));
        assertThat(decide(new ReadingState(CardReading.Status.FAILED, NOW.minusSeconds(30), NOW, false, 0)))
                .isEqualTo(new Decision(Step.PICK, Why.READING_FAILED));
    }

    @Test
    void suggestsWhatMatchedAndSearchesWhenNothingDid() {
        assertThat(decide(done(Duration.ofSeconds(1), false, 2)).step()).isEqualTo(Step.SUGGEST);
        assertThat(decide(done(Duration.ofSeconds(5), false, 0)).step()).isEqualTo(Step.READING);
        assertThat(decide(done(Duration.ofMinutes(1), false, 0))).isEqualTo(new Decision(Step.PICK, Why.NO_MATCH));
        assertThat(decide(done(Duration.ofSeconds(5), true, 0))).isEqualTo(new Decision(Step.PICK, Why.READING_FAILED));
        assertThat(IdentifyPolicy.decide(false, false, true, NOW, true, false, done(Duration.ofSeconds(5), false, 0), NOW))
                .isEqualTo(new Decision(Step.PICK, Why.NO_MATCH));
    }
}

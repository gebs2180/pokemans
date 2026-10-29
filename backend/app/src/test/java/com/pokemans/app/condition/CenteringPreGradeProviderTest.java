package com.pokemans.app.condition;

import static com.pokemans.app.condition.CenteringMeasurerTest.photo;
import static com.pokemans.app.condition.CenteringMeasurerTest.png;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.pokemans.app.condition.CenteringMeasurerTest.Borders;
import com.pokemans.app.intake.CardOutline;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CenteringPreGradeProviderTest {

    private final CenteringPreGradeProvider provider = new CenteringPreGradeProvider(
            new CenteringMeasurer(new CardOutline(CenteringMeasurerTest.QUALITY)), 1.0);

    private static final PreGradeProvider.Image CENTRED = image(new Borders(48, 48, 48, 48), true);

    private static PreGradeProvider.Image image(Borders borders, boolean art) {
        return new PreGradeProvider.Image(png(photo(borders, 2, art)), "image/png");
    }

    @Test
    void reportsCenteringOnlyWithTheCapAsTheCenteringGrade() {
        // Front 40/60 left/right: past 60/40 once the 1 point margin is added, so PSA 8 at best.
        PreGradeProvider.Poll poll = provider.grade(new PreGradeProvider.Photos(image(new Borders(40, 60, 48, 48), true), CENTRED));

        assertThat(poll).isInstanceOf(PreGradeProvider.Done.class);
        PreGradeProvider.Reading reading = ((PreGradeProvider.Done) poll).reading();
        assertThat(reading.centering()).isEqualTo(8.0);
        assertThat(reading.overallGrade()).isNull();
        assertThat(reading.psaGrade()).isNull();
        assertThat(reading.corners()).isNull();
        assertThat(reading.edges()).isNull();
        assertThat(reading.surface()).isNull();
        assertThat(reading.modelVersion()).isEqualTo(CenteringPreGradeProvider.VERSION);
        assertThat(reading.details()).containsEntry("psaCenteringCap", 8);
        @SuppressWarnings("unchecked")
        Map<String, Object> front = (Map<String, Object>) reading.details().get("frontCentering");
        assertThat(Double.parseDouble(((String) front.get("leftRight")).split("/")[0])).isCloseTo(40, within(1.0));
        assertThat(front).containsKeys("topBottom", "bordersMm", "worstShare");
    }

    @Test
    void anUnmeasuredSideDoesNotLimitTheCap() {
        PreGradeProvider.Poll poll = provider.grade(new PreGradeProvider.Photos(CENTRED, image(new Borders(48, 48, 48, 48), false)));

        PreGradeProvider.Reading reading = ((PreGradeProvider.Done) poll).reading();
        assertThat(reading.centering()).isEqualTo(10.0);
        @SuppressWarnings("unchecked")
        Map<String, Object> back = (Map<String, Object>) reading.details().get("backCentering");
        assertThat(back).containsKey("unmeasured");
    }

    @Test
    void neitherSideMeasuredIsARefusalWithBothReasons() {
        PreGradeProvider.Image blank = image(new Borders(48, 48, 48, 48), false);

        PreGradeProvider.Poll poll = provider.grade(new PreGradeProvider.Photos(blank, blank));

        assertThat(poll).isInstanceOf(PreGradeProvider.Failed.class);
        assertThat(((PreGradeProvider.Failed) poll).reason()).contains("front: ").contains("back: ");
    }
}

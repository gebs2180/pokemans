package com.pokemans.app.condition;

import com.pokemans.app.intake.CardOutline;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

/**
 * Prints the centering measured on a folder of real photos, and writes each straightened card with
 * the measured borders drawn on it as {@code <name>.centering.jpg}. For checking by eye, not a
 * pass/fail test: real photos stay out of the repo. Run with {@code POKEMANS_REAL_PHOTOS=<folder>}.
 */
@EnabledIfEnvironmentVariable(named = "POKEMANS_REAL_PHOTOS", matches = ".+")
class RealPhotoCenteringTest {

    @Test
    void printCentering() throws IOException {
        Path folder = Path.of(System.getenv("POKEMANS_REAL_PHOTOS"));
        List<Path> photos;
        try (Stream<Path> files = Files.list(folder)) {
            photos = files.filter(p -> p.toString().toLowerCase().matches(".*\\.(jpe?g|png)$"))
                    .filter(p -> !p.getFileName().toString().matches(".*\\.(card|centering)\\..*"))
                    .sorted().toList();
        }
        for (Path photo : photos) {
            Mat[] card = new Mat[1];
            CenteringMeasurer measurer = new CenteringMeasurer(new CardOutline(CenteringMeasurerTest.QUALITY), m -> card[0] = m.clone());
            CenteringMeasurer.Result result = measurer.measure(Files.readAllBytes(photo));
            System.out.println(photo.getFileName() + ": " + CenteringPreGradeProvider.describe(result));
            if (card[0] == null) {
                continue;
            }
            if (result instanceof CenteringMeasurer.Measured m) {
                Scalar red = new Scalar(0, 0, 255);
                int w = card[0].cols();
                int h = card[0].rows();
                Imgproc.line(card[0], new Point(m.left(), 0), new Point(m.left(), h), red, 1);
                Imgproc.line(card[0], new Point(w - 1 - m.right(), 0), new Point(w - 1 - m.right(), h), red, 1);
                Imgproc.line(card[0], new Point(0, m.top()), new Point(w, m.top()), red, 1);
                Imgproc.line(card[0], new Point(0, h - 1 - m.bottom()), new Point(w, h - 1 - m.bottom()), red, 1);
                System.out.printf(Locale.ROOT, "  L/R %.1f/%.1f  T/B %.1f/%.1f  PSA cap %d%n", m.leftShare(), 100 - m.leftShare(),
                        m.topShare(), 100 - m.topShare(), PsaCentering.cap(m.worstShare(), null, 1.0));
            }
            Imgcodecs.imwrite(photo.resolveSibling(photo.getFileName() + ".centering.jpg").toString(), card[0]);
            card[0].release();
        }
    }
}

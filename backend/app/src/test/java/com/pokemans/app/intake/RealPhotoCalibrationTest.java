package com.pokemans.app.intake;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Prints quality and card metrics for a folder of real photos, and writes each card crop next to it
 * as {@code <name>.card.jpg}. For tuning thresholds by hand, not a pass/fail test: real photos stay
 * out of the repo. Run with {@code POKEMANS_REAL_PHOTOS=<folder>}.
 */
@EnabledIfEnvironmentVariable(named = "POKEMANS_REAL_PHOTOS", matches = ".+")
class RealPhotoCalibrationTest {

    @Test
    void printMetrics() throws IOException {
        QualityProperties properties = QualityCheckerTest.defaults();
        QualityChecker checker = new QualityChecker(properties);
        Path folder = Path.of(System.getenv("POKEMANS_REAL_PHOTOS"));
        List<Path> photos;
        try (Stream<Path> files = Files.list(folder)) {
            photos = files.filter(p -> p.toString().toLowerCase().matches(".*\\.(jpe?g|png)$"))
                    .filter(p -> !p.getFileName().toString().contains(".card."))
                    .sorted().toList();
        }
        for (Path photo : photos) {
            QualityChecker.Result result = checker.check(Files.readAllBytes(photo));
            System.out.printf("%s%n  frame blur %.0f, frame glare %.3f, reasons %s%n",
                    photo.getFileName(), result.blurScore(), result.glareFraction(), result.reasons());
            CardDetector.Detection card = result.card();
            if (card == null) {
                System.out.println("  no whole card");
                continue;
            }
            System.out.printf("  card coverage %.2f, aspect %.3f, blur %.0f, glare %.3f%n",
                    card.coverage(), card.aspect(), card.blurScore(), card.glareFraction());
            Files.write(photo.resolveSibling(photo.getFileName() + ".card.jpg"), card.cropJpeg());
        }
    }
}

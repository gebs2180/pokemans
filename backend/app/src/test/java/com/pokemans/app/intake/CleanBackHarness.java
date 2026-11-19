package com.pokemans.app.intake;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Not a test: runs the clean back over real surface crops in a local folder with the edge fixes in several settings,
 * and writes each run's images to a subfolder for a look. Run with {@code -Dcleanback.dir=<folder of *.jpg>}. Photos
 * never go into git.
 */
@EnabledIfSystemProperty(named = "cleanback.dir", matches = ".+")
class CleanBackHarness {

    /** One setting of the edge fixes: sharpness sigmas, flow cap in px, damping gradient and floor. */
    record Run(String name, double[] sigmas, double flow, double damp, double floor) {}

    @Test
    void compare() throws IOException {
        Path dir = Path.of(System.getProperty("cleanback.dir"));
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.getFileName().toString().endsWith(".jpg")).sorted().toList();
        }
        List<byte[]> surfaces = new ArrayList<>();
        for (Path f : files) {
            surfaces.add(Files.readAllBytes(f));
        }
        double[] sigmas = CleanBack.SHARPNESS_SIGMAS;
        double flow = CleanBack.FLOW_MAX_PX;
        double damp = CleanBack.EDGE_DAMP_GRADIENT;
        double floor = CleanBack.EDGE_DAMP_FLOOR;
        List<Run> runs = List.of(
                new Run("old", new double[] {0}, 0, 1e9, 1),
                new Run("nodamp", sigmas, flow, 1e9, 1),
                new Run("new", sigmas, flow, damp, floor));
        try {
            for (Run run : runs) {
                CleanBack.SHARPNESS_SIGMAS = run.sigmas();
                CleanBack.FLOW_MAX_PX = run.flow();
                CleanBack.EDGE_DAMP_GRADIENT = run.damp();
                CleanBack.EDGE_DAMP_FLOOR = run.floor();
                CleanBack.Built built = CleanBack.buildReference(surfaces, CleanBack.MIN_SEEDED_BACKS);
                System.out.println(run.name() + " reference used " + built.used() + " rejected " + built.rejected());
                Path out = dir.resolve(run.name());
                Files.createDirectories(out);
                Files.write(out.resolve("reference.png"), built.png());
                CleanBack.Reference reference = CleanBack.Reference.of(built.png());
                for (int i = 0; i < files.size(); i++) {
                    long t = System.nanoTime();
                    CleanBack.Outcome o = CleanBack.clean(surfaces.get(i), reference);
                    System.out.printf("%s %s %s corr=%.4f resid=%.2f %d ms%n", run.name(), files.get(i).getFileName(),
                            o.failure(), o.scores().correlation(), o.scores().residual(),
                            (System.nanoTime() - t) / 1_000_000);
                    if (o.jpeg() != null) {
                        Files.write(out.resolve(files.get(i).getFileName()), o.jpeg());
                    }
                }
            }
        } finally {
            CleanBack.SHARPNESS_SIGMAS = sigmas;
            CleanBack.FLOW_MAX_PX = flow;
            CleanBack.EDGE_DAMP_GRADIENT = damp;
            CleanBack.EDGE_DAMP_FLOOR = floor;
        }
    }
}

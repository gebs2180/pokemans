package com.pokemans.app.condition;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Grades real photos at CardGrade through the same adapter the app uses, and saves each final raw
 * response as a test fixture in {@code src/test/resources/cardgrade/}. Costs one credit per card, so it
 * runs only when asked:
 *
 * <pre>
 * $env:CARDGRADE_LIVE_PHOTOS = "C:\path\to\folder"   # name-front.jpg and name-back.jpg per card
 * .\gradlew.bat :app:test --tests "*CardGradeLiveCheck"
 * </pre>
 *
 * with {@code CARDGRADE_API_KEY} set in the environment. The key is only ever sent in the
 * Authorization header; responses don't contain it, and it is checked for before anything is written.
 */
@EnabledIfEnvironmentVariable(named = "CARDGRADE_LIVE_PHOTOS", matches = ".+")
@EnabledIfEnvironmentVariable(named = "CARDGRADE_API_KEY", matches = ".+")
class CardGradeLiveCheck {

    private static final Path FIXTURES = Path.of("src/test/resources/cardgrade");

    @Test
    void gradeFolder() throws Exception {
        String key = System.getenv("CARDGRADE_API_KEY").trim();
        ConditionProperties properties = new ConditionProperties(null, 1.5, new BigDecimal("0.10"), new BigDecimal("0.25"),
                new BigDecimal("0.25"), new BigDecimal("0.02"), null,
                new ConditionProperties.CardGrade(true, key, URI.create("https://cardgrade.io"), "PSA",
                        Duration.ofSeconds(60), 10, 50, Duration.ofHours(1)), null);
        CardGradePreGradeProvider provider = new CardGradePreGradeProvider(properties);
        JsonMapper json = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
        Files.createDirectories(FIXTURES);

        Path folder = Path.of(System.getenv("CARDGRADE_LIVE_PHOTOS"));
        List<Path> fronts;
        try (Stream<Path> files = Files.list(folder)) {
            fronts = files.filter(p -> p.getFileName().toString().toLowerCase().matches(".*-front\\.(jpe?g|png|webp)$")).sorted().toList();
        }
        for (Path front : fronts) {
            String file = front.getFileName().toString();
            String name = file.substring(0, file.toLowerCase().lastIndexOf("-front"));
            Path back = front.resolveSibling(file.replaceFirst("(?i)-front", "-back"));
            Path out = FIXTURES.resolve(name + ".json");
            if (Files.exists(out)) {
                System.out.println(name + ": already saved, skipped (delete " + out + " to grade it again)");
                continue;
            }
            PreGradeProvider.Photos photos = new PreGradeProvider.Photos(image(front), Files.exists(back) ? image(back) : null);
            String id = provider.submit(photos, UUID.randomUUID().toString());
            System.out.println(name + ": submitted as " + id);
            PreGradeProvider.Poll poll = PreGradeProvider.Poll.pending();
            for (int i = 0; i < 60 && poll instanceof PreGradeProvider.Pending; i++) {
                Thread.sleep(10_000);
                poll = provider.poll(id);
            }
            Map<String, Object> raw = switch (poll) {
                case PreGradeProvider.Done done -> done.raw();
                case PreGradeProvider.Failed failed -> failed.raw();
                case PreGradeProvider.Pending pending -> throw new IllegalStateException(name + " still pending after 10 minutes: " + id);
            };
            String text = json.writeValueAsString(raw);
            if (text.contains(key)) {
                throw new IllegalStateException("The response for " + name + " contains the API key; not written");
            }
            Files.writeString(out, text + System.lineSeparator());
            System.out.println(name + ": " + poll.getClass().getSimpleName() + " -> " + out
                    + (poll instanceof PreGradeProvider.Done done ? " " + done.reading() : ""));
        }
    }

    private static PreGradeProvider.Image image(Path path) throws IOException {
        String name = path.getFileName().toString().toLowerCase();
        String type = name.endsWith(".png") ? "image/png" : name.endsWith(".webp") ? "image/webp" : "image/jpeg";
        return new PreGradeProvider.Image(Files.readAllBytes(path), type);
    }
}

package com.pokemans.app.condition;

import com.pokemans.app.intake.CardOutline;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * claude-vision on a local folder of real photos, through the same adapter the app uses. Never in CI:
 * it runs only when {@code CLAUDE_VISION_LIVE_PHOTOS} names a folder (name-front.jpg and name-back.jpg
 * per card; real photos stay out of the repo).
 *
 * <pre>
 * $env:CLAUDE_VISION_LIVE_PHOTOS = "C:\path\to\folder"
 * $env:CLAUDE_VISION_API_KEY = "..."    # leave unset for the estimate only
 * .\gradlew.bat :app:test --tests "*ClaudeVisionLiveCheck" -i
 * </pre>
 *
 * Without a key it prints, per card, the views and an input-token estimate from their pixel counts
 * (about w × h / 750 per picture). With a key it sends one real batch per card ({@code CLAUDE_VISION_SAMPLES}
 * samples, default 3), waits for it ({@code CLAUDE_VISION_LIVE_WAIT}, default 2 hours; the API allows 24), prints the reading and the measured cost from the returned usage,
 * and writes the raw answer to {@code build/claude-vision-live/} (no pictures in it).
 */
@EnabledIfEnvironmentVariable(named = "CLAUDE_VISION_LIVE_PHOTOS", matches = ".+")
class ClaudeVisionLiveCheck {

    /** The rubric and the closing instruction, rounded up; counted from the text at about 4 characters a token. */
    private static final int TEXT_TOKENS = (ClaudeVisionRubric.SYSTEM.length() + 1500) / 4 + 50;

    /** How long to wait per card: {@code CLAUDE_VISION_LIVE_WAIT} (e.g. PT24H), default 2 hours. */
    private static final Duration WAIT = Duration.parse(Optional.ofNullable(System.getenv("CLAUDE_VISION_LIVE_WAIT")).orElse("PT2H"));

    @Test
    void gradeFolder() throws Exception {
        String key = System.getenv("CLAUDE_VISION_API_KEY");
        String samples = System.getenv("CLAUDE_VISION_SAMPLES");
        ConditionProperties.ClaudeVision config = new ConditionProperties.ClaudeVision(true,
                key == null || key.isBlank() ? "estimate-only" : key.trim(), URI.create("https://api.anthropic.com"), "claude-opus-5-5",
                "high", samples == null ? 3 : Integer.parseInt(samples), 16000, Duration.ofSeconds(120), 2.00, 10.00);
        CardOutline outline = new CardOutline(CenteringMeasurerTest.QUALITY);
        ClaudeVisionPreGradeProvider provider = new ClaudeVisionPreGradeProvider(config, RestClient.builder(), new ClaudeVisionViews(outline),
                new CenteringPreGradeProvider(new CenteringMeasurer(outline), 1.0), null);
        boolean live = key != null && !key.isBlank();
        JsonMapper json = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
        Path out = Path.of("build/claude-vision-live");
        Files.createDirectories(out);

        Path folder = Path.of(System.getenv("CLAUDE_VISION_LIVE_PHOTOS"));
        List<Path> fronts;
        try (Stream<Path> files = Files.list(folder)) {
            fronts = files.filter(p -> p.getFileName().toString().toLowerCase().matches(".*-front\\.(jpe?g|png|webp)$")).sorted().toList();
        }
        for (Path front : fronts) {
            String file = front.getFileName().toString();
            String name = file.substring(0, file.toLowerCase().lastIndexOf("-front"));
            Path back = front.resolveSibling(file.replaceFirst("(?i)-front", "-back"));
            PreGradeProvider.Photos photos = new PreGradeProvider.Photos(image(front), Files.exists(back) ? image(back) : null);

            ClaudeVisionPreGradeProvider.Prepared prepared = provider.prepare(photos);
            long imageTokens = 0;
            for (ClaudeVisionViews.View view : prepared.views()) {
                var picture = ImageIO.read(new ByteArrayInputStream(view.jpeg()));
                imageTokens += Math.round((double) picture.getWidth() * picture.getHeight() / 750);
            }
            long input = imageTokens + TEXT_TOKENS;
            System.out.printf("%s: %d views (%s), about %,d input tokens per sample; centering %s%n", name, prepared.views().size(),
                    prepared.centering().get("frontViews") + " / " + prepared.centering().get("backViews"), input,
                    prepared.centering().get("psaCenteringCap"));
            if (!live) {
                continue;
            }
            String id = provider.submit(photos, UUID.randomUUID().toString());
            System.out.println(name + ": batch " + id.substring(0, id.indexOf('|')));
            PreGradeProvider.Poll poll = PreGradeProvider.Poll.pending();
            Instant until = Instant.now().plus(WAIT);
            while (poll instanceof PreGradeProvider.Pending && Instant.now().isBefore(until)) {
                Thread.sleep(30_000);
                poll = provider.poll(id);
            }
            Map<String, Object> raw = switch (poll) {
                case PreGradeProvider.Done done -> done.raw();
                case PreGradeProvider.Failed failed -> failed.raw();
                case PreGradeProvider.Pending pending -> null;
            };
            if (raw == null) {
                // Batches may take up to 24 hours; the batch keeps running and its results stay in the console.
                System.out.println(name + ": still pending after " + WAIT + "; batch " + id.substring(0, id.indexOf('|')));
                continue;
            }
            String text = json.writeValueAsString(raw);
            if (text.contains(config.apiKey())) {
                throw new IllegalStateException("The answer for " + name + " contains the API key; not written");
            }
            Files.writeString(out.resolve(name + ".json"), text + System.lineSeparator());
            System.out.println(name + ": " + (poll instanceof PreGradeProvider.Done done ? done.reading() : poll)
                    + System.lineSeparator() + "  usage " + raw.get("usage"));
        }
    }

    private static PreGradeProvider.Image image(Path path) throws IOException {
        String name = path.getFileName().toString().toLowerCase();
        String type = name.endsWith(".png") ? "image/png" : name.endsWith(".webp") ? "image/webp" : "image/jpeg";
        return new PreGradeProvider.Image(Files.readAllBytes(path), type);
    }
}

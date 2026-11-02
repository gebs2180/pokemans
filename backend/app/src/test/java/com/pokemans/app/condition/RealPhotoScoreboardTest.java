package com.pokemans.app.condition;

import com.pokemans.app.condition.CenteringScoreboard.Reading;
import com.pokemans.app.condition.CenteringScoreboard.Truth;
import com.pokemans.app.intake.CardOutline;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Runs today's measurer over a scoreboard file and writes the board beside it, as
 * {@code <file>.board.md}, with every photo's outcome in {@code <file>.photos.csv} to diff against
 * another run. Real photos stay out of the repo, so this is not a pass/fail test. Run with
 * {@code POKEMANS_CENTERING_SCOREBOARD=<file>}.
 *
 * <p>The file is what the labelling page's "Download scoreboard file" gives. Its photo links expire,
 * so each photo is kept in a {@code <file>.photos} folder the first time, and never fetched again. A
 * hand-written file can run on a folder of photos with no labels: a photo may name a local {@code file}
 * instead of a {@code url}, relative to the scoreboard file, and a face may say {@code hardCard} itself.
 */
@EnabledIfEnvironmentVariable(named = "POKEMANS_CENTERING_SCOREBOARD", matches = ".+")
class RealPhotoScoreboardTest {

    /** {@code pokemans.condition.centering.measurement-margin}'s default. */
    private static final double MARGIN = 1;

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    @Test
    void score() throws Exception {
        Path file = Path.of(System.getenv("POKEMANS_CENTERING_SCOREBOARD")).toAbsolutePath();
        JsonNode board = new ObjectMapper().readTree(file.toFile());
        Path cache = file.resolveSibling(file.getFileName() + ".photos");
        CenteringMeasurer measurer = new CenteringMeasurer(new CardOutline(CenteringMeasurerTest.QUALITY));

        List<Reading> readings = new ArrayList<>();
        StringBuilder csv = new StringBuilder("card,face,photo,left_share,top_share,error,reason\n");
        for (JsonNode card : board.path("cards")) {
            for (JsonNode face : card.path("faces")) {
                JsonNode label = face.path("label");
                Truth truth = label.isObject() ? new Truth(mm(label, "left"), mm(label, "right"), mm(label, "top"), mm(label, "bottom")) : null;
                boolean back = face.path("face").asString().equals("BACK");
                for (JsonNode photo : face.path("photos")) {
                    byte[] bytes = bytes(photo, file, cache);
                    Reading reading = reading(card.path("cardId").asString() + "/" + face.path("face").asString(), back,
                            label.path("hardCard").asBoolean() || face.path("hardCard").asBoolean(), photo.path("id").asString(), truth,
                            measurer.measure(bytes));
                    readings.add(reading);
                    csv.append(String.format(Locale.ROOT, "%s,%s,%s,%s,%s,%s,\"%s\"%n", card.path("label").asString().replace(",", " "),
                            face.path("face").asString(), reading.photo(), number(reading.leftShare()), number(reading.topShare()),
                            number(reading.error()), reading.reason() == null ? "" : reading.reason().replace("\"", "'")));
                }
            }
        }
        String table = CenteringScoreboard.table(CenteringScoreboard.score(readings, MARGIN));
        String report = "Measurer " + CenteringPreGradeProvider.VERSION + ", margin " + MARGIN + ", from " + file.getFileName() + "\n\n" + table;
        System.out.println(report);
        Files.writeString(file.resolveSibling(file.getFileName() + ".board.md"), report);
        Files.writeString(file.resolveSibling(file.getFileName() + ".photos.csv"), csv);
    }

    private static Reading reading(String faceId, boolean back, boolean hard, String photo, Truth truth, CenteringMeasurer.Result result) {
        return switch (result) {
            case CenteringMeasurer.Measured m -> new Reading(faceId, back, hard, photo, truth, m.leftShare(), m.topShare(), null);
            case CenteringMeasurer.Unmeasured u -> new Reading(faceId, back, hard, photo, truth, null, null, u.reason());
        };
    }

    private byte[] bytes(JsonNode photo, Path file, Path cache) throws IOException, InterruptedException {
        if (photo.hasNonNull("file")) {
            return Files.readAllBytes(file.resolveSibling(photo.path("file").asString()));
        }
        Path kept = cache.resolve(photo.path("id").asString());
        if (!Files.exists(kept)) {
            HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(URI.create(photo.path("url").asString())).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IOException("Photo " + photo.path("id").asString() + " answered " + response.statusCode()
                        + ": the file's links have expired, so download a new scoreboard file");
            }
            Files.createDirectories(cache);
            Files.write(kept, response.body());
        }
        return Files.readAllBytes(kept);
    }

    private static Double mm(JsonNode label, String side) {
        return label.hasNonNull(side) ? label.path(side).asDouble() : null;
    }

    private static String number(Double value) {
        return value == null ? "" : String.format(Locale.ROOT, "%.2f", value);
    }
}

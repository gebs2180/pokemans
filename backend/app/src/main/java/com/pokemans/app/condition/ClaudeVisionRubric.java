package com.pokemans.app.condition;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import tools.jackson.databind.json.JsonMapper;

/**
 * The fixed grading rubric {@link ClaudeVisionPreGradeProvider} sends, how one request is assembled,
 * and how the samples' answers become one reading. Pure: no Spring, no network, so it is unit-tested
 * as is.
 *
 * <p>Change {@link #VERSION} whenever the prompt, the schema or the aggregation changes: it is stored
 * with every prediction, so readings from different rubrics are never compared as one.
 */
final class ClaudeVisionRubric {

    static final String VERSION = "rubric-1";

    /** The sub-grades the model gives. Centering is ours ({@link CenteringMeasurer}), never the model's. */
    static final List<String> SUBGRADES = List.of("corners", "edges", "surface");

    static final String SYSTEM = """
            You grade raw Pokémon trading cards from photos the way PSA grades them, as a cautious human \
            pre-grader would before deciding whether to submit a card. You are given the front and the back, \
            each as the whole card straightened, four corner close-ups and four edge strips. Every picture \
            keeps a thin band of background past the card's edge, so the true edge is always in view.

            Grade three things on PSA's 1–10 scale, in half points: corners, edges and surface. Do not grade \
            centering: it is measured separately with a ruler. Grade the worse of front and back for each.

            PSA's grades, in short:
            10 Gem Mint: four sharp corners, clean edges with no whitening front or back, no scratches, \
            scuffs, dents or print lines in normal light.
            9 Mint: one small flaw only, such as the faintest touch at one corner or one tiny back-edge nick.
            8 NM-MT: slight wear at one or two corners, a few specks of back-edge whitening, a faint surface \
            mark or minor print spot.
            7 NM: light fraying on some corners, light whitening along back edges, light scratches visible \
            when the light catches them.
            6 EX-MT: visible corner wear, whitening along several edges, light scuffing or a few clear \
            scratches, slight loss of gloss.
            5 EX: corners starting to round, edge chipping or whitening plain at arm's length, scratches \
            visible without tilting the card.
            4 VG-EX: rounded corners, heavy edge wear, obvious surface wear, possibly a light crease.
            3 VG: clearly rounded corners, heavy edge chipping, scuffed surface, a crease that may break the \
            gloss.
            2 Good: heavy wear everywhere, creases, staining or writing starting.
            1 Poor: major creases, tears, missing paper, heavy staining or water damage.

            Check explicitly, every time:
            - Back edges: whitening on the dark-blue back border is the most common flaw on these cards and \
            the easiest to miss on the front. Look along all four back edge strips and all four back \
            corners for white specks, chipping and silvering. Several specks along one edge are not a 9.
            - Holo surface: on a holo or foil card, look across the holo for fine scratches, scuffs, \
            indentations and print lines; they show as thin bright or dark lines that do not follow the \
            artwork. Also check the non-holo areas and the back for scratches and dents.
            - Creases and dents anywhere: any crease caps the card at 4 or below, however clean the rest.

            Rules:
            - The overall grade follows the weakest sub-grade, not the average: overall is never higher than \
            the lowest of corners, edges and surface, and one bad corner pulls the whole card down.
            - Be conservative. Photos hide flaws a grader sees in hand. When torn between two grades, give \
            the lower. When glare, blur, a sleeve or the crop hides an area, say so in the notes and do not \
            assume it is clean.
            - In each sub-grade's notes, name the visible defects and where they are (for example "back \
            top edge: 4–5 white specks near right corner; front bottom-left corner: slight fraying"). If \
            you see none, say what you checked.
            - limiting_factor is the sub-grade that set the overall grade.""";

    /** Structured output: the answer is this JSON object and nothing else. */
    static final Map<String, Object> SCHEMA = schema();

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ClaudeVisionRubric() {
    }

    private static Map<String, Object> schema() {
        Map<String, Object> subgrade = Map.of(
                "type", "object",
                "properties", Map.of(
                        "grade", Map.of("type", "number", "description", "PSA 1–10 in half points"),
                        "notes", Map.of("type", "string", "description", "The visible defects and where they are, or what was checked")),
                "required", List.of("grade", "notes"),
                "additionalProperties", false);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("corners", subgrade);
        properties.put("edges", subgrade);
        properties.put("surface", subgrade);
        properties.put("overall", Map.of("type", "number", "description", "PSA 1–10 in half points; never above the lowest sub-grade"));
        properties.put("limiting_factor", Map.of("type", "string", "enum", SUBGRADES));
        properties.put("hidden_areas", Map.of("type", "string", "description", "Areas glare, blur, a sleeve or the crop kept you from judging; empty if none"));
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("corners", "edges", "surface", "overall", "limiting_factor", "hidden_areas"),
                "additionalProperties", false);
    }

    /**
     * One Messages API request's {@code params}: the rubric as the system prompt, then each picture
     * after a text label naming it. The same for every sample of a card: the samples differ only
     * through the model's own sampling.
     */
    static Map<String, Object> params(String model, String effort, int maxTokens, List<ClaudeVisionViews.View> views) {
        List<Map<String, Object>> content = new ArrayList<>();
        for (ClaudeVisionViews.View view : views) {
            content.add(Map.of("type", "text", "text", view.label() + ":"));
            content.add(Map.of("type", "image", "source", Map.of("type", "base64", "media_type", "image/jpeg",
                    "data", Base64.getEncoder().encodeToString(view.jpeg()))));
        }
        // Cached: the samples of a card share everything up to here, so a later sample can read it at a tenth of the price.
        content.add(Map.of("type", "text", "text", "Grade this card's corners, edges and surface by the rubric.",
                "cache_control", Map.of("type", "ephemeral")));
        Map<String, Object> outputConfig = new LinkedHashMap<>();
        outputConfig.put("format", Map.of("type", "json_schema", "schema", SCHEMA));
        if (effort != null && !effort.isBlank()) {
            outputConfig.put("effort", effort);
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("model", model);
        params.put("max_tokens", maxTokens);
        params.put("system", SYSTEM);
        params.put("output_config", outputConfig);
        params.put("messages", List.of(Map.of("role", "user", "content", content)));
        return params;
    }

    /** One sample's answer, half-point grades already snapped down; or why it isn't usable. */
    record Sample(Map<String, Double> grades, Double overall, String limitingFactor, Map<String, String> notes, String hiddenAreas,
                  String error) {

        static Sample failed(String error) {
            return new Sample(Map.of(), null, null, Map.of(), null, error);
        }

        boolean ok() {
            return error == null;
        }
    }

    /**
     * Reads one sample from a Messages API response (a batch result's {@code message}). A refusal, a
     * cut-off answer or JSON that doesn't fit the schema makes a failed sample, never a guess.
     */
    static Sample parse(Map<String, Object> message) {
        if (message == null) {
            return Sample.failed("no message");
        }
        Object stop = message.get("stop_reason");
        if (!"end_turn".equals(stop)) {
            return Sample.failed("stop_reason " + stop);
        }
        String text = null;
        if (message.get("content") instanceof List<?> blocks) {
            for (Object block : blocks) {
                if (block instanceof Map<?, ?> b && "text".equals(b.get("type")) && b.get("text") instanceof String t) {
                    text = t;
                }
            }
        }
        if (text == null) {
            return Sample.failed("no text block");
        }
        Map<String, Object> answer;
        try {
            answer = JSON.readValue(text, JSON.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
        } catch (RuntimeException e) {
            return Sample.failed("answer isn't JSON: " + e.getMessage());
        }
        Map<String, Double> grades = new LinkedHashMap<>();
        Map<String, String> notes = new LinkedHashMap<>();
        for (String name : SUBGRADES) {
            if (!(answer.get(name) instanceof Map<?, ?> sub) || snap(sub.get("grade")) == null) {
                return Sample.failed("no " + name + " grade");
            }
            grades.put(name, snap(sub.get("grade")));
            notes.put(name, sub.get("notes") == null ? "" : sub.get("notes").toString());
        }
        Double overall = snap(answer.get("overall"));
        if (overall == null) {
            return Sample.failed("no overall grade");
        }
        Object limiting = answer.get("limiting_factor");
        Object hidden = answer.get("hidden_areas");
        return new Sample(grades, overall, limiting == null ? null : limiting.toString(), notes,
                hidden == null ? null : hidden.toString(), null);
    }

    /** A grade on the 1–10 scale snapped down to a half point; null when it isn't a number on the scale. */
    static Double snap(Object value) {
        if (!(value instanceof Number n) || Double.isNaN(n.doubleValue()) || n.doubleValue() < 1 || n.doubleValue() > 10) {
            return null;
        }
        return Math.floor(n.doubleValue() * 2) / 2;
    }

    /**
     * The median of the samples' grades. With an even count, the lower of the middle two: conservative
     * by default, never interpolated upward.
     */
    static Double median(List<Double> values) {
        List<Double> sorted = values.stream().filter(Objects::nonNull).sorted().toList();
        return sorted.isEmpty() ? null : sorted.get((sorted.size() - 1) / 2);
    }

    static Double spread(List<Double> values) {
        List<Double> sorted = values.stream().filter(Objects::nonNull).sorted().toList();
        return sorted.isEmpty() ? null : sorted.getLast() - sorted.getFirst();
    }

    /**
     * The card's reading from its samples, or null when none is usable.
     *
     * <p>Each sub-grade is the median of the samples. The overall is the lowest of the model's median
     * overall, the median sub-grades and our own centering cap: it follows the weakest sub-grade (the
     * rubric asks the model for this too; here it can't be skipped). The limiting factor is the
     * sub-grade at that lowest point, centering when ours is lower than everything the model saw, ties
     * going to the factor most samples named. Notes come from the sample whose overall is the median,
     * so they describe a grade that was actually given.
     *
     * @param centeringCap our measurement's PSA cap, null when neither side could be measured
     */
    static Aggregate aggregate(List<Sample> samples, Integer centeringCap) {
        List<Sample> ok = samples.stream().filter(Sample::ok).toList();
        if (ok.isEmpty()) {
            return null;
        }
        Map<String, Double> medians = new LinkedHashMap<>();
        Map<String, Double> spreads = new LinkedHashMap<>();
        for (String name : SUBGRADES) {
            List<Double> values = ok.stream().map(s -> s.grades().get(name)).toList();
            medians.put(name, median(values));
            spreads.put(name, spread(values));
        }
        List<Double> overalls = ok.stream().map(Sample::overall).toList();
        Double modelOverall = median(overalls);
        spreads.put("overall", spread(overalls));

        double weakestSub = medians.values().stream().min(Double::compare).orElseThrow();
        double overall = Math.min(modelOverall, weakestSub);
        String limiting;
        if (centeringCap != null && centeringCap < overall) {
            overall = centeringCap;
            limiting = "centering";
        } else {
            Map<String, Long> named = new LinkedHashMap<>();
            ok.forEach(s -> named.merge(String.valueOf(s.limitingFactor()), 1L, Long::sum));
            limiting = SUBGRADES.stream().filter(name -> medians.get(name) == weakestSub)
                    .max(Comparator.comparing((Function<String, Long>) name -> named.getOrDefault(name, 0L))
                            .thenComparing(name -> -SUBGRADES.indexOf(name)))
                    .orElseThrow();
        }
        Sample representative = ok.stream().filter(s -> s.overall().equals(modelOverall)).findFirst().orElse(ok.getFirst());
        return new Aggregate(medians, overall, modelOverall, spreads, limiting, representative.notes(),
                representative.hiddenAreas(), ok.size());
    }

    /**
     * @param overall      what the card would grade at PSA by this reading, half points kept
     * @param modelOverall the model's own median overall, before the weakest-sub-grade rule and our centering
     * @param spreads      highest minus lowest across samples, per sub-grade and overall: 0 means every sample agreed
     */
    record Aggregate(Map<String, Double> medians, double overall, double modelOverall, Map<String, Double> spreads,
                     String limitingFactor, Map<String, String> notes, String hiddenAreas, int samplesUsed) {

        /**
         * PSA's own scale: 1.5 to 8.5 in halves, then 9 and 10, so a 9.5 reads as 9 (never rounded up).
         */
        double psaGrade() {
            return overall > 9 && overall < 10 ? 9 : overall;
        }
    }
}

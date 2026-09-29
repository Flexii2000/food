package com.fherrmann.food.service;

import com.fherrmann.food.model.Dish;
import com.fherrmann.food.model.Nutrients;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Gegen ein Skript, das statt der Claude-Session antwortet: geprueft wird, was in den
 * Auftrag geht und was aus der Antwort gelesen wird.
 */
class ClaudeSessionNutritionExtractorTest {

    @TempDir
    Path tempDir;

    private static final String ANSWER = """
            {"type":"result","result":"{\\"name\\":\\"Pasta\\",\\"kcalPer100g\\":150,\\"proteinPer100g\\":5,\\"carbsPer100g\\":30,\\"fatPer100g\\":1,\\"grams\\":400,\\"lookedUp\\":[],\\"estimated\\":[\\"kcalPer100g\\"],\\"note\\":\\"x\\",\\"meal\\":\\"DINNER\\",\\"sugarPer100g\\":2.5,\\"saltPer100g\\":0.4}"}
            """;

    private ClaudeSessionNutritionExtractor extractor(Path promptCopy) throws Exception {
        return extractor(promptCopy, ANSWER);
    }

    private ClaudeSessionNutritionExtractor extractor(Path promptCopy, String answer) throws Exception {
        Path script = tempDir.resolve("agent.sh");
        Files.writeString(script, "#!/bin/sh\ncat > " + promptCopy + "\ncat <<'JSON'\n" + answer + "JSON\n");
        script.toFile().setExecutable(true);
        return new ClaudeSessionNutritionExtractor(script.toString(), 10, new ObjectMapper());
    }

    private static final List<Dish> KNOWN = List.of(new Dish("d1", "Skyr natur",
            new Nutrients(63, 11, 4, 0.2, 0.1, 4.0, 0.0, 0.13, Map.of("calciumMg", 150.0)), 150.0, null));

    /** Eine Antwort mit Mikronaehrstoffen - darunter einer, den es nicht gibt, und zwei unbrauchbare Werte. */
    private static final String ANSWER_WITH_MICROS = ANSWER.replace(
            ",\\\"meal\\\"",
            ",\\\"microsPer100g\\\":{\\\"ironMg\\\":2.1,\\\"folateUg\\\":60,\\\"vitaminKUg\\\":5,"
                    + "\\\"zincMg\\\":-1,\\\"iodineUg\\\":\\\"viel\\\"},\\\"meal\\\"");

    @Test
    void aDetailedPersonIsAskedForTheWholeLabelAndGetsIt() throws Exception {
        Path prompt = tempDir.resolve("prompt.txt");
        ExtractedDish dish = extractor(prompt).extract("Pasta", null, new Nutrients(2800, 180, 300, 90), KNOWN, true, false);

        String sent = Files.readString(prompt);
        assertThat(sent).contains("saturatedFatPer100g, sugarPer100g, fiberPer100g und saltPer100g");
        assertThat(sent).contains("Zucker 4 g", "Salz 0.13 g");
        assertThat(dish.sugarG()).isEqualTo(2.5);
        assertThat(dish.saltG()).isEqualTo(0.4);
        assertThat(dish.fiberG()).isNull();
    }

    /** Felix: keine Frage nach Details, und was ungefragt kommt, wird nicht gelesen. */
    @Test
    void everyoneElseIsNotAskedAndGetsNoDetails() throws Exception {
        Path prompt = tempDir.resolve("prompt.txt");
        ExtractedDish dish = extractor(prompt).extract("Pasta", null, new Nutrients(2300, 200, 235.5, 62), KNOWN, false, false);

        String sent = Files.readString(prompt);
        assertThat(sent).doesNotContain("sugarPer100g", "Zucker");
        assertThat(dish.sugarG()).isNull();
        assertThat(dish.saltG()).isNull();
        assertThat(dish.kcal()).isEqualTo(150);
    }

    /**
     * Fehlt die Liste der Schaetzungen, gilt alles als geschaetzt - auch die Detailwerte.
     * Sonst stuenden sie im Vorschlag, als haetten sie so im Text gestanden.
     */
    @Test
    void withoutAnEstimatedListTheDetailsCountAsEstimatedToo() throws Exception {
        String answer = ANSWER.replace(",\\\"estimated\\\":[\\\"kcalPer100g\\\"]", "");
        assertThat(answer).isNotEqualTo(ANSWER);

        ExtractedDish dish = extractor(tempDir.resolve("prompt.txt"), answer)
                .extract("Pasta", null, new Nutrients(2800, 180, 300, 90), KNOWN, true, false);

        assertThat(dish.estimatedFields()).contains("kcalPer100g", "sugarPer100g", "saltPer100g");
    }

    /** Eine Session, die nicht endet, darf den Auftrag nicht festhalten - das Zeitlimit muss greifen. */
    @Test
    void aHangingSessionRunsIntoTheTimeout() throws Exception {
        Path script = tempDir.resolve("hanging.sh");
        Files.writeString(script, "#!/bin/sh\ncat > /dev/null\nexec sleep 30\n");
        script.toFile().setExecutable(true);
        ClaudeSessionNutritionExtractor hanging =
                new ClaudeSessionNutritionExtractor(script.toString(), 1, new ObjectMapper());

        long start = System.nanoTime();
        assertThatThrownBy(() -> hanging.extract("Pasta", null, new Nutrients(2300, 200, 235.5, 62), KNOWN, false, false))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(504));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
    }

    // --- Mikronaehrstoffe --------------------------------------------------------

    @Test
    void aPersonWithMicronutrientsIsAskedForThemAndGetsThem() throws Exception {
        assertThat(ANSWER_WITH_MICROS).isNotEqualTo(ANSWER);
        Path prompt = tempDir.resolve("prompt.txt");
        ExtractedDish dish = extractor(prompt, ANSWER_WITH_MICROS)
                .extract("Linsensuppe", null, new Nutrients(2800, 180, 300, 90), KNOWN, false, true);

        String sent = Files.readString(prompt);
        assertThat(sent).contains("microsPer100g", "vitaminAUg, vitaminDUg", "seleniumUg", "ausdruecklich erwuenscht");
        // Nur Bekanntes mit einer Zahl >= 0 - der Rest ist keine Angabe.
        assertThat(dish.micros()).containsExactly(Map.entry("folateUg", 60.0), Map.entry("ironMg", 2.1));
    }

    /** Die gespeicherten Gerichte gehen ohne ihre Mikronaehrstoffe in den Auftrag - der Dienst nimmt ohnehin die eigenen. */
    @Test
    void theKnownDishesGoWithoutTheirMicros() throws Exception {
        Path prompt = tempDir.resolve("prompt.txt");
        extractor(prompt, ANSWER_WITH_MICROS).extract("Skyr", null, new Nutrients(2800, 180, 300, 90), KNOWN, false, true);
        assertThat(Files.readString(prompt)).contains("Skyr natur: 63 kcal").doesNotContain("150 mg", "calciumMg: ");
    }

    /** Felix: keine Frage nach Mikronaehrstoffen, und was ungefragt kommt, wird nicht gelesen. */
    @Test
    void everyoneElseIsNotAskedForMicrosAndGetsNone() throws Exception {
        Path prompt = tempDir.resolve("prompt.txt");
        ExtractedDish dish = extractor(prompt, ANSWER_WITH_MICROS)
                .extract("Linsensuppe", null, new Nutrients(2300, 200, 235.5, 62), KNOWN, true, false);

        assertThat(Files.readString(prompt)).doesNotContain("microsPer100g", "Mikronaehrstoffe", "vitaminAUg");
        assertThat(dish.micros()).isEmpty();
        assertThat(dish.sugarG()).isEqualTo(2.5);
    }

    /** Fehlt die Liste der Schaetzungen, gelten auch die Mikronaehrstoffe als geschaetzt. */
    @Test
    void withoutAnEstimatedListTheMicrosCountAsEstimatedToo() throws Exception {
        String answer = ANSWER_WITH_MICROS.replace(",\\\"estimated\\\":[\\\"kcalPer100g\\\"]", "");
        assertThat(answer).isNotEqualTo(ANSWER_WITH_MICROS);

        ExtractedDish dish = extractor(tempDir.resolve("prompt.txt"), answer)
                .extract("Linsensuppe", null, new Nutrients(2800, 180, 300, 90), KNOWN, false, true);

        assertThat(dish.estimatedFields()).contains("kcalPer100g", "ironMg", "folateUg", "seleniumUg");
    }

    // MARK: - vegan

    private static final String ANSWER_VEGAN = ANSWER.replace(",\\\"meal\\\"", ",\\\"vegan\\\":false,\\\"meal\\\"");

    private static final List<Dish> KNOWN_WITH_FLAGS = List.of(
            new Dish("d1", "Skyr natur", new Nutrients(63, 11, 4, 0.2), 150.0, null, false),
            new Dish("d2", "Hummus", new Nutrients(170, 8, 14, 10), 50.0, null, true),
            new Dish("d3", "Brötchen", new Nutrients(270, 9, 50, 2), 60.0, null));

    @Test
    void whoKnowsTheVeganModeIsAskedAndGetsTheVerdict() throws Exception {
        assertThat(ANSWER_VEGAN).isNotEqualTo(ANSWER);
        Path prompt = tempDir.resolve("prompt.txt");
        ExtractedDish dish = extractor(prompt, ANSWER_VEGAN)
                .extract("Pasta", null, new Nutrients(2800, 180, 300, 90), KNOWN_WITH_FLAGS, false, false, true);

        String sent = Files.readString(prompt);
        assertThat(sent).contains("Gib zusaetzlich vegan an");
        assertThat(sent).contains("- Skyr natur: 63 kcal, 11 g E, 4 g KH, 0.2 g F, Portion 150 g, nicht vegan\n",
                "- Hummus: 170 kcal, 8 g E, 14 g KH, 10 g F, Portion 50 g, vegan\n",
                "- Brötchen: 270 kcal, 9 g E, 50 g KH, 2 g F, Portion 60 g\n");
        assertThat(dish.vegan()).isFalse();
    }

    /** Felix: keine Frage, keine Kennzeichen im Auftrag, und was ungefragt kommt, wird nicht gelesen. */
    @Test
    void everyoneElseIsNotAskedAboutVegan() throws Exception {
        Path prompt = tempDir.resolve("prompt.txt");
        ExtractedDish dish = extractor(prompt, ANSWER_VEGAN)
                .extract("Pasta", null, new Nutrients(2300, 200, 235.5, 62), KNOWN_WITH_FLAGS, false, false);

        assertThat(Files.readString(prompt)).doesNotContain("vegan");
        assertThat(dish.vegan()).isNull();
    }

    /** Nur ein echter Wahrheitswert zaehlt - "unklar" oder ein Text heisst unbekannt. */
    @Test
    void anythingButABooleanLeavesVeganUnknown() throws Exception {
        String answer = ANSWER.replace(",\\\"meal\\\"", ",\\\"vegan\\\":\\\"vielleicht\\\",\\\"meal\\\"");
        ExtractedDish dish = extractor(tempDir.resolve("prompt.txt"), answer)
                .extract("Pasta", null, new Nutrients(2300, 200, 235.5, 62), KNOWN, false, false, true);

        assertThat(dish.vegan()).isNull();
    }
}

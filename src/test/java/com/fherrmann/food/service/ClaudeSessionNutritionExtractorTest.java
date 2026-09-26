package com.fherrmann.food.service;

import com.fherrmann.food.model.Dish;
import com.fherrmann.food.model.Nutrients;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
        Path script = tempDir.resolve("agent.sh");
        Files.writeString(script, "#!/bin/sh\ncat > " + promptCopy + "\ncat <<'JSON'\n" + ANSWER + "JSON\n");
        script.toFile().setExecutable(true);
        return new ClaudeSessionNutritionExtractor(script.toString(), 10, new ObjectMapper());
    }

    private static final List<Dish> KNOWN = List.of(new Dish("d1", "Skyr natur",
            new Nutrients(63, 11, 4, 0.2, 0.1, 4.0, 0.0, 0.13), 150.0, null));

    @Test
    void aDetailedPersonIsAskedForTheWholeLabelAndGetsIt() throws Exception {
        Path prompt = tempDir.resolve("prompt.txt");
        ExtractedDish dish = extractor(prompt).extract("Pasta", null, new Nutrients(2800, 180, 300, 90), KNOWN, true);

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
        ExtractedDish dish = extractor(prompt).extract("Pasta", null, new Nutrients(2300, 200, 235.5, 62), KNOWN, false);

        String sent = Files.readString(prompt);
        assertThat(sent).doesNotContain("sugarPer100g", "Zucker");
        assertThat(dish.sugarG()).isNull();
        assertThat(dish.saltG()).isNull();
        assertThat(dish.kcal()).isEqualTo(150);
    }
}

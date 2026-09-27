package com.fherrmann.food;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Die Mikronaehrstoffe durch den ganzen Dienst - Filter, Controller, Dienst, Datei und
 * die JSON-Einstellungen, mit denen die Clients die Antworten wirklich bekommen.
 *
 * <p>Fuer Felix steht jede Antwort Zeichen fuer Zeichen da, und zwar so, wie sie vor den
 * Mikronaehrstoffen aussah: aufgenommen mit genau diesen Anfragen gegen den alten Stand.
 * Seine Anfragen schicken dabei sogar {@code micros} mit - die muessen spurlos
 * verschwinden. Die einzige Aenderung ist {@code micronutrients: false} in
 * {@code /features}, und die verlangt der Vertrag.
 *
 * <p>Nur die Ids werden vor dem Vergleich ersetzt; die Uhr steht still.
 */
@SpringBootTest
@Import(MicronutrientsApiTest.FixedClock.class)
class MicronutrientsApiTest {

    private static final String TORBEN = "0123456789abcdef0123456789abcdef";
    private static final String JOANA = "fedcba9876543210fedcba9876543210";

    private static final Path DATA;
    private static final Path AGENT;

    static {
        try {
            DATA = Files.createTempDirectory("micronutrients-api");
            // Steht fuer die Claude-Session: liefert immer dieselbe Antwort, mit
            // Detailwerten und Mikronaehrstoffen.
            AGENT = DATA.resolve("agent.sh");
            Files.writeString(AGENT, "#!/bin/sh\ncat > /dev/null\ncat <<'JSON'\n"
                    + "{\"type\":\"result\",\"result\":\"{\\\"name\\\":\\\"Linsensuppe\\\",\\\"kcalPer100g\\\":95,"
                    + "\\\"proteinPer100g\\\":6,\\\"carbsPer100g\\\":12,\\\"fatPer100g\\\":2.5,\\\"grams\\\":400,"
                    + "\\\"portionG\\\":400,\\\"sugarPer100g\\\":1.2,\\\"saltPer100g\\\":0.7,"
                    + "\\\"microsPer100g\\\":{\\\"ironMg\\\":2.1,\\\"folateUg\\\":60},"
                    + "\\\"lookedUp\\\":[],\\\"estimated\\\":[\\\"kcalPer100g\\\",\\\"grams\\\",\\\"ironMg\\\"],"
                    + "\\\"note\\\":\\\"geschaetzt\\\",\\\"meal\\\":\\\"LUNCH\\\"}\"}\n"
                    + "JSON\n");
            AGENT.toFile().setExecutable(true);
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("food.data-file", () -> DATA.resolve("food.json").toString());
        registry.add("food.security.token", () -> "testtoken");
        registry.add("health.tokens", () -> "torben:" + TORBEN + ",joana:" + JOANA);
        registry.add("food.detailed-people", () -> "torben");
        registry.add("food.micronutrient-people", () -> "torben,joana");
        registry.add("food.agent.command", AGENT::toString);
        registry.add("food.agent.people", () -> "felix,torben");
    }

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-09-27T08:30:00Z"), ZoneId.of("Europe/Berlin"));
        }
    }

    @Autowired
    private WebApplicationContext context;

    /** Wer fragt: Felix ueber den Privat-Cookie, alle anderen mit ihrem Token. */
    private MockHttpServletResponse send(String who, MockHttpServletRequestBuilder request) throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        request = switch (who) {
            case "felix" -> request.cookie(new Cookie("fh_private", "testtoken"));
            case "torben" -> request.header("Authorization", "Bearer " + TORBEN);
            default -> request.header("Authorization", "Bearer " + JOANA);
        };
        return mockMvc.perform(request).andReturn().getResponse();
    }

    /** Status und Antwort in einer Zeile, die Ids ersetzt - so vergleicht sich das am besten. */
    private String call(String who, MockHttpServletRequestBuilder request) throws Exception {
        MockHttpServletResponse response = send(who, request);
        return response.getStatus() + " " + response.getContentAsString(StandardCharsets.UTF_8)
                .replaceAll("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "<id>");
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private JsonNode read(String who, MockHttpServletRequestBuilder request) throws Exception {
        return new ObjectMapper().readTree(send(who, request).getContentAsString(StandardCharsets.UTF_8));
    }

    private String dishId(String who, String name) throws Exception {
        for (JsonNode dish : read(who, get("/api/food/dishes"))) {
            if (dish.path("name").asString("").equals(name)) {
                return dish.path("id").asString("");
            }
        }
        throw new IllegalStateException("kein Gericht " + name);
    }

    /** Startet eine Schnellerfassung und wartet auf den Vorschlag. */
    private String quickCapture(String who) throws Exception {
        String id = read(who, json(post("/api/food/quick-capture"),
                "{\"date\":\"2026-09-27\",\"text\":\"eine Portion Linsensuppe\",\"meal\":\"LUNCH\"}"))
                .path("id").asString("");
        String state = "";
        for (int i = 0; i < 100 && !state.contains("\"done\"") && !state.contains("\"failed\""); i++) {
            Thread.sleep(50);
            state = call(who, get("/api/food/quick-capture/" + id));
        }
        return state;
    }

    @Test
    void felixGetsExactlyTheResponsesOfBefore() throws Exception {
        assertThat(call("felix", json(put("/api/food/targets"),
                "{\"kcal\":2300,\"proteinG\":200,\"carbsG\":235.5,\"fatG\":62,"
                        + "\"mealShares\":{\"BREAKFAST\":0.25,\"LUNCH\":0.35,\"DINNER\":0.3,\"SNACK\":0.1},"
                        + "\"micros\":{\"vitaminCMg\":110}}")))
                .isEqualTo("200 {\"kcal\":2300.0,\"proteinG\":200.0,\"carbsG\":235.5,\"fatG\":62.0}");
        assertThat(call("felix", json(post("/api/food/dishes"),
                "{\"name\":\"Haferflocken\",\"kcal\":372,\"proteinG\":13.5,\"carbsG\":58.7,\"fatG\":7,"
                        + "\"portionG\":60,\"micros\":{\"ironMg\":4.2,\"magnesiumMg\":130}}")))
                .isEqualTo("201 {\"id\":\"<id>\",\"name\":\"Haferflocken\",\"per100g\":{\"kcal\":372.0,"
                        + "\"proteinG\":13.5,\"carbsG\":58.7,\"fatG\":7.0},\"portionG\":60.0,\"lastUsedOn\":null}");
        assertThat(call("felix", json(post("/api/food/entries"),
                "{\"date\":\"2026-09-27\",\"grams\":300,\"meal\":\"BREAKFAST\",\"dish\":{\"name\":\"Skyr mit Beeren\","
                        + "\"kcal\":80,\"proteinG\":8,\"carbsG\":6,\"fatG\":1.3,\"portionG\":300,"
                        + "\"micros\":{\"calciumMg\":120,\"vitaminB12Ug\":0.4}}}")))
                .isEqualTo("201 {\"date\":\"2026-09-27\",\"targets\":{\"kcal\":2300.0,\"proteinG\":200.0,"
                        + "\"carbsG\":235.5,\"fatG\":62.0},\"consumed\":{\"kcal\":240.0,\"proteinG\":24.0,"
                        + "\"carbsG\":18.0,\"fatG\":3.9},\"remaining\":{\"kcal\":2060.0,\"proteinG\":176.0,"
                        + "\"carbsG\":217.5,\"fatG\":58.1},\"entries\":[{\"id\":\"<id>\",\"date\":\"2026-09-27\","
                        + "\"dishId\":\"<id>\",\"name\":\"Skyr mit Beeren\",\"grams\":300.0,\"per100g\":{\"kcal\":80.0,"
                        + "\"proteinG\":8.0,\"carbsG\":6.0,\"fatG\":1.3},\"meal\":\"BREAKFAST\","
                        + "\"createdAt\":\"2026-09-27T08:30:00Z\"}],\"mealTargets\":{\"BREAKFAST\":575.0,"
                        + "\"LUNCH\":805.0,\"DINNER\":690.0,\"SNACK\":230.0}}");
        String oats = dishId("felix", "Haferflocken");
        assertThat(call("felix", json(post("/api/food/entries"),
                "{\"date\":\"2026-09-27\",\"grams\":60,\"meal\":\"BREAKFAST\",\"dishId\":\"" + oats + "\"}")))
                .isEqualTo("201 {\"date\":\"2026-09-27\",\"targets\":{\"kcal\":2300.0,\"proteinG\":200.0,"
                        + "\"carbsG\":235.5,\"fatG\":62.0},\"consumed\":{\"kcal\":463.2,\"proteinG\":32.1,"
                        + "\"carbsG\":53.2,\"fatG\":8.1},\"remaining\":{\"kcal\":1836.8,\"proteinG\":167.9,"
                        + "\"carbsG\":182.3,\"fatG\":53.9},\"entries\":[{\"id\":\"<id>\",\"date\":\"2026-09-27\","
                        + "\"dishId\":\"<id>\",\"name\":\"Skyr mit Beeren\",\"grams\":300.0,\"per100g\":{\"kcal\":80.0,"
                        + "\"proteinG\":8.0,\"carbsG\":6.0,\"fatG\":1.3},\"meal\":\"BREAKFAST\","
                        + "\"createdAt\":\"2026-09-27T08:30:00Z\"},{\"id\":\"<id>\",\"date\":\"2026-09-27\","
                        + "\"dishId\":\"<id>\",\"name\":\"Haferflocken\",\"grams\":60.0,\"per100g\":{\"kcal\":372.0,"
                        + "\"proteinG\":13.5,\"carbsG\":58.7,\"fatG\":7.0},\"meal\":\"BREAKFAST\","
                        + "\"createdAt\":\"2026-09-27T08:30:00Z\"}],\"mealTargets\":{\"BREAKFAST\":575.0,"
                        + "\"LUNCH\":805.0,\"DINNER\":690.0,\"SNACK\":230.0}}");
        assertThat(call("felix", json(put("/api/food/dishes/" + oats),
                "{\"name\":\"Haferflocken\",\"kcal\":370,\"proteinG\":13.5,\"carbsG\":58.7,\"fatG\":7,"
                        + "\"portionG\":50,\"micros\":{\"ironMg\":4.0}}")))
                .isEqualTo("200 {\"id\":\"<id>\",\"name\":\"Haferflocken\",\"per100g\":{\"kcal\":370.0,"
                        + "\"proteinG\":13.5,\"carbsG\":58.7,\"fatG\":7.0},\"portionG\":50.0,"
                        + "\"lastUsedOn\":\"2026-09-27\"}");
        assertThat(call("felix", get("/api/food/day").param("date", "2026-09-27")))
                .isEqualTo("200 {\"date\":\"2026-09-27\",\"targets\":{\"kcal\":2300.0,\"proteinG\":200.0,"
                        + "\"carbsG\":235.5,\"fatG\":62.0},\"consumed\":{\"kcal\":463.2,\"proteinG\":32.1,"
                        + "\"carbsG\":53.2,\"fatG\":8.1},\"remaining\":{\"kcal\":1836.8,\"proteinG\":167.9,"
                        + "\"carbsG\":182.3,\"fatG\":53.9},\"entries\":[{\"id\":\"<id>\",\"date\":\"2026-09-27\","
                        + "\"dishId\":\"<id>\",\"name\":\"Skyr mit Beeren\",\"grams\":300.0,\"per100g\":{\"kcal\":80.0,"
                        + "\"proteinG\":8.0,\"carbsG\":6.0,\"fatG\":1.3},\"meal\":\"BREAKFAST\","
                        + "\"createdAt\":\"2026-09-27T08:30:00Z\"},{\"id\":\"<id>\",\"date\":\"2026-09-27\","
                        + "\"dishId\":\"<id>\",\"name\":\"Haferflocken\",\"grams\":60.0,\"per100g\":{\"kcal\":372.0,"
                        + "\"proteinG\":13.5,\"carbsG\":58.7,\"fatG\":7.0},\"meal\":\"BREAKFAST\","
                        + "\"createdAt\":\"2026-09-27T08:30:00Z\"}],\"mealTargets\":{\"BREAKFAST\":575.0,"
                        + "\"LUNCH\":805.0,\"DINNER\":690.0,\"SNACK\":230.0}}");
        assertThat(call("felix", get("/api/food/dishes")))
                .isEqualTo("200 [{\"id\":\"<id>\",\"name\":\"Haferflocken\",\"per100g\":{\"kcal\":370.0,"
                        + "\"proteinG\":13.5,\"carbsG\":58.7,\"fatG\":7.0},\"portionG\":50.0,"
                        + "\"lastUsedOn\":\"2026-09-27\"},{\"id\":\"<id>\",\"name\":\"Skyr mit Beeren\","
                        + "\"per100g\":{\"kcal\":80.0,\"proteinG\":8.0,\"carbsG\":6.0,\"fatG\":1.3},\"portionG\":300.0,"
                        + "\"lastUsedOn\":\"2026-09-27\"}]");
        assertThat(call("felix", get("/api/food/targets")))
                .isEqualTo("200 {\"kcal\":2300.0,\"proteinG\":200.0,\"carbsG\":235.5,\"fatG\":62.0}");
        assertThat(call("felix", get("/api/food/daily").param("from", "2026-09-20").param("to", "2026-09-27")))
                .isEqualTo("200 [{\"date\":\"2026-09-27\",\"consumed\":{\"kcal\":463.2,\"proteinG\":32.1,"
                        + "\"carbsG\":53.2,\"fatG\":8.1}}]");
        // Der Agent liefert Mikronaehrstoffe mit - im Vorschlag fuer Felix stehen sie nicht.
        assertThat(quickCapture("felix"))
                .isEqualTo("200 {\"id\":\"<id>\",\"status\":\"done\",\"preview\":{\"known\":false,\"dishId\":null,"
                        + "\"name\":\"Linsensuppe\",\"per100g\":{\"kcal\":95.0,\"proteinG\":6.0,\"carbsG\":12.0,"
                        + "\"fatG\":2.5},\"portionG\":400.0,\"grams\":400.0,\"meal\":\"LUNCH\","
                        + "\"valueSources\":{\"kcal\":\"estimated\",\"proteinG\":\"read\",\"carbsG\":\"read\","
                        + "\"fatG\":\"read\",\"portionG\":\"read\",\"grams\":\"estimated\"},\"note\":\"geschaetzt\"},"
                        + "\"error\":null,\"elapsedSeconds\":0}");
        assertThat(call("felix", get("/api/food/features")))
                .isEqualTo("200 {\"quickCapture\":true,\"me\":\"felix\",\"detailedNutrients\":false,"
                        + "\"micronutrients\":false}");
        assertThat(Files.readString(DATA.resolve("food.json"))).doesNotContain("micro");
    }

    @Test
    void torbenGetsMicrosWithEveryEntryAndTheDayAddsThemUp() throws Exception {
        assertThat(call("torben", get("/api/food/features")))
                .isEqualTo("200 {\"quickCapture\":true,\"me\":\"torben\",\"detailedNutrients\":true,"
                        + "\"micronutrients\":true}");

        // Unbekannter Schluessel, zu grosser Wert: 400 mit Grund, nichts gespeichert.
        MockHttpServletResponse rejected = send("torben", json(post("/api/food/dishes"),
                "{\"name\":\"Kaputt\",\"kcal\":100,\"proteinG\":1,\"carbsG\":1,\"fatG\":1,\"micros\":{\"vitaminKUg\":5}}"));
        assertThat(rejected.getStatus()).isEqualTo(400);
        assertThat(rejected.getErrorMessage()).contains("micros.vitaminKUg is not a known micronutrient");
        assertThat(send("torben", json(post("/api/food/dishes"),
                "{\"name\":\"Kaputt\",\"kcal\":100,\"proteinG\":1,\"carbsG\":1,\"fatG\":1,\"micros\":{\"ironMg\":10001}}"))
                .getErrorMessage()).contains("micros.ironMg must be at most");

        assertThat(call("torben", json(post("/api/food/dishes"),
                "{\"name\":\"Haferflocken\",\"kcal\":372,\"proteinG\":13.5,\"carbsG\":58.7,\"fatG\":7,"
                        + "\"portionG\":60,\"micros\":{\"ironMg\":4.2,\"magnesiumMg\":130}}")))
                .isEqualTo("201 {\"id\":\"<id>\",\"name\":\"Haferflocken\",\"per100g\":{\"kcal\":372.0,"
                        + "\"proteinG\":13.5,\"carbsG\":58.7,\"fatG\":7.0,\"micros\":{\"magnesiumMg\":130.0,"
                        + "\"ironMg\":4.2}},\"portionG\":60.0,\"lastUsedOn\":null}");
        send("torben", json(post("/api/food/entries"),
                "{\"date\":\"2026-09-27\",\"grams\":300,\"meal\":\"BREAKFAST\",\"dish\":{\"name\":\"Skyr mit Beeren\","
                        + "\"kcal\":80,\"proteinG\":8,\"carbsG\":6,\"fatG\":1.3,\"portionG\":300,"
                        + "\"micros\":{\"calciumMg\":120,\"vitaminB12Ug\":0.4}}}"));
        String oats = dishId("torben", "Haferflocken");
        send("torben", json(post("/api/food/entries"),
                "{\"date\":\"2026-09-27\",\"grams\":60,\"meal\":\"BREAKFAST\",\"dishId\":\"" + oats + "\"}"));

        JsonNode day = read("torben", get("/api/food/day").param("date", "2026-09-27"));
        // Nie gespeichert: die DGE-Vorgabe.
        assertThat(day.path("targets").path("micros").size()).isEqualTo(14);
        assertThat(day.path("targets").path("micros").path("vitaminDUg").asDouble()).isEqualTo(20.0);
        // Teilsummen: jeder Wert aus den Eintraegen, die ihn kennen.
        assertThat(day.path("consumed").path("micros").toString()).isEqualTo(
                "{\"vitaminB12Ug\":1.2,\"calciumMg\":360.0,\"magnesiumMg\":78.0,\"ironMg\":2.52}");
        // Jeder Schluessel fehlt in mindestens einem der beiden Eintraege.
        assertThat(day.path("microGaps").size()).isEqualTo(14);
        assertThat(day.path("remaining").has("micros")).isFalse();
        assertThat(day.path("entries").get(0).path("per100g").path("micros").toString())
                .isEqualTo("{\"vitaminB12Ug\":0.4,\"calciumMg\":120.0}");
        assertThat(read("torben", get("/api/food/daily").param("from", "2026-09-27").param("to", "2026-09-27"))
                .get(0).path("consumed").path("micros").path("calciumMg").asDouble()).isEqualTo(360.0);

        // PUT ersetzt: was nicht mitkommt, ist weg.
        assertThat(call("torben", json(put("/api/food/dishes/" + oats),
                "{\"name\":\"Haferflocken\",\"kcal\":370,\"proteinG\":13.5,\"carbsG\":58.7,\"fatG\":7,"
                        + "\"portionG\":50,\"micros\":{\"ironMg\":4.0}}")))
                .contains("\"micros\":{\"ironMg\":4.0}}");

        assertThat(quickCapture("torben")).isEqualTo("200 {\"id\":\"<id>\",\"status\":\"done\",\"preview\":{\"known\":false,\"dishId\":null,"
                        + "\"name\":\"Linsensuppe\",\"per100g\":{\"kcal\":95.0,\"proteinG\":6.0,\"carbsG\":12.0,"
                        + "\"fatG\":2.5,\"sugarG\":1.2,\"saltG\":0.7,\"micros\":{\"folateUg\":60.0,\"ironMg\":2.1}},"
                        + "\"portionG\":400.0,\"grams\":400.0,\"meal\":\"LUNCH\","
                        + "\"valueSources\":{\"kcal\":\"estimated\",\"proteinG\":\"read\",\"carbsG\":\"read\","
                        + "\"fatG\":\"read\",\"portionG\":\"read\",\"sugarG\":\"read\",\"saltG\":\"read\","
                        + "\"folateUg\":\"read\",\"ironMg\":\"estimated\",\"grams\":\"estimated\"},"
                        + "\"note\":\"geschaetzt\"},\"error\":null,\"elapsedSeconds\":0}");
    }

    @Test
    void microTargetsStartWithTheDgeValuesAndCanBeDeliberatelyLeftOut() throws Exception {
        assertThat(call("joana", get("/api/food/targets"))).isEqualTo("200 {\"kcal\":2300.0,\"proteinG\":200.0,\"carbsG\":235.5,\"fatG\":62.0,"
                        + "\"micros\":{\"vitaminAUg\":850.0,\"vitaminDUg\":20.0,\"vitaminEMg\":8.0,"
                        + "\"vitaminCMg\":110.0,\"vitaminB2Mg\":1.4,\"vitaminB12Ug\":4.0,\"folateUg\":300.0,"
                        + "\"calciumMg\":1000.0,\"magnesiumMg\":350.0,\"potassiumMg\":4000.0,\"ironMg\":11.0,"
                        + "\"zincMg\":16.0,\"iodineUg\":150.0,\"seleniumUg\":70.0}}");

        String base = "\"kcal\":2000,\"proteinG\":120,\"carbsG\":230,\"fatG\":65";
        assertThat(call("joana", json(put("/api/food/targets"), "{" + base + ",\"micros\":{\"ironMg\":15,\"folateUg\":300}}")))
                .isEqualTo("200 {\"kcal\":2000.0,\"proteinG\":120.0,\"carbsG\":230.0,\"fatG\":65.0,"
                        + "\"micros\":{\"folateUg\":300.0,\"ironMg\":15.0}}");
        // Ein Client ohne das Feld laesst die Mikro-Ziele stehen.
        assertThat(call("joana", json(put("/api/food/targets"), "{" + base + "}")))
                .endsWith("\"micros\":{\"folateUg\":300.0,\"ironMg\":15.0}}");
        // Ein Ziel von 0 ist keines - dafuer laesst man den Schluessel weg.
        assertThat(send("joana", json(put("/api/food/targets"), "{" + base + ",\"micros\":{\"ironMg\":0}}"))
                .getErrorMessage()).contains("micros.ironMg must be greater than 0");
        // Bewusst ohne jedes Ziel: kein micros mehr - und auch nicht wieder die Vorgabe.
        assertThat(call("joana", json(put("/api/food/targets"), "{" + base + ",\"micros\":{}}")))
                .isEqualTo("200 {\"kcal\":2000.0,\"proteinG\":120.0,\"carbsG\":230.0,\"fatG\":65.0}");
        assertThat(call("joana", get("/api/food/targets")))
                .isEqualTo("200 {\"kcal\":2000.0,\"proteinG\":120.0,\"carbsG\":230.0,\"fatG\":65.0}");
        assertThat(read("joana", get("/api/food/day").param("date", "2026-09-27")).path("targets").has("micros")).isFalse();
    }
}

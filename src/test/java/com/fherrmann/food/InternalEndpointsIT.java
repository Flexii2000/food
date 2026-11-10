package com.fherrmann.food;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Die internen Endpunkte (Vertrag §11.3) gegen einen echten Server - aus demselben Grund
 * wie {@link ErrorStatusIT}: erst hier laeuft der zweite Durchgang nach {@code /error},
 * und dort entscheidet sich, ob ein 400 des Endpunkts als 400 ankommt. Der Dienst-Token
 * der Einkaufsliste ist keine Person; ohne den Filter auf dem ERROR-Dispatch wuerde aus
 * jedem Fehler ein 403.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "food.security.token=testtoken",
        "food.data-file=build/tmp/internal-endpoints-it/food.json",
        "food.push.devices-file=build/tmp/internal-endpoints-it/devices.json",
        "food.push.devices-production-file=build/tmp/internal-endpoints-it/devices-production.json",
        "food.push.android-devices-file=build/tmp/internal-endpoints-it/devices-android.json",
        "health.tokens=felix:" + InternalEndpointsIT.FELIX + ",torben:" + InternalEndpointsIT.TORBEN,
        "food.recipe-token=" + InternalEndpointsIT.RECIPES,
})
class InternalEndpointsIT {

    static final String FELIX = "ffffffffffffffffffffffffffffffff";
    static final String TORBEN = "0123456789abcdef0123456789abcdef";
    static final String RECIPES = "recipe-service-token-0123456789";

    private static final String DISH = "\"dish\":{\"name\":\"Rezept: Gericht A\",\"kcal\":152.4,\"proteinG\":5.7,"
            + "\"carbsG\":18.6,\"fatG\":5.7,\"portionG\":420.0}";

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void cleanData() throws IOException {
        Path dir = Path.of("build/tmp/internal-endpoints-it");
        if (Files.exists(dir)) {
            try (var paths = Files.walk(dir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private HttpResponse<String> call(String method, String path, String bearer, String body, String... headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        for (int i = 0; i + 1 < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Anlegen bei zwei Personen, dann sehen beide es in ihrer Gerichteliste - und nur dort. */
    @Test
    void aCookedRecipeLandsInEveryLinkedDiary() throws Exception {
        HttpResponse<String> first = call("POST", "/api/internal/recipe-dishes", RECIPES,
                "{\"persons\":[\"felix\",\"torben\",\"joana\"]," + DISH + "}");

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.body()).isEqualTo("[{\"person\":\"felix\",\"status\":\"created\",\"message\":null},"
                + "{\"person\":\"torben\",\"status\":\"created\",\"message\":null},"
                + "{\"person\":\"joana\",\"status\":\"not-linked\",\"message\":null}]");

        for (String token : new String[] {FELIX, TORBEN}) {
            JsonNode dishes = mapper.readTree(call("GET", "/api/food/dishes", token, null).body());
            assertThat(dishes).hasSize(1);
            assertThat(dishes.get(0).path("name").asString()).isEqualTo("Rezept: Gericht A");
            assertThat(dishes.get(0).path("per100g").path("kcal").asDouble()).isEqualTo(152.4);
            assertThat(dishes.get(0).path("portionG").asDouble()).isEqualTo(420.0);
            assertThat(dishes.get(0).has("vegan")).isFalse();
        }

        // Noch einmal dasselbe: nichts zu tun. Mit neuen Werten: auf den neuen Stand.
        assertThat(call("POST", "/api/internal/recipe-dishes", RECIPES, "{\"persons\":[\"felix\"]," + DISH + "}")
                .body()).contains("\"status\":\"unchanged\"");
        assertThat(call("POST", "/api/internal/recipe-dishes", RECIPES,
                "{\"persons\":[\"felix\"]," + DISH.replace("152.4", "160.0") + "}").body())
                .contains("\"status\":\"updated\"");
        JsonNode dishes = mapper.readTree(call("GET", "/api/food/dishes", FELIX, null).body());
        assertThat(dishes).hasSize(1);
        assertThat(dishes.get(0).path("per100g").path("kcal").asDouble()).isEqualTo(160.0);
    }

    /** Werte ausserhalb der Grenzen eines Gerichts: je Person "rejected" mit dem Grund, kein Fehlerstatus. */
    @Test
    void aDishOutsideTheLimitsIsRejectedPerPerson() throws Exception {
        HttpResponse<String> response = call("POST", "/api/internal/recipe-dishes", RECIPES,
                "{\"persons\":[\"torben\"],\"dish\":{\"name\":\"Rezept: Gericht B\",\"kcal\":1200,\"proteinG\":5,"
                        + "\"carbsG\":5,\"fatG\":5}}");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"rejected\"").contains("kcal must be at most");
    }

    /** Der eigentliche Grund fuer diesen Test: ein 400 bleibt ein 400, mit Begruendung - kein 403. */
    @Test
    void aBadRequestWithTheServiceTokenStaysABadRequest() throws Exception {
        HttpResponse<String> missing = call("POST", "/api/internal/recipe-dishes", RECIPES, "{\"persons\":[\"felix\"]}");
        assertThat(missing.statusCode()).isEqualTo(400);
        assertThat(missing.body()).contains("dish is required");

        HttpResponse<String> garbled = call("POST", "/api/internal/recipe-dishes", RECIPES, "{kein json");
        assertThat(garbled.statusCode()).isEqualTo(400);
    }

    @Test
    void pushErrorsKeepTheirStatusToo() throws Exception {
        HttpResponse<String> unknown = call("POST", "/api/internal/push", FELIX,
                "{\"person\":\"joana\",\"kind\":\"friend-nudge\",\"title\":\"Person A\"}");
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(unknown.body()).contains("unknown person");

        assertThat(call("POST", "/api/internal/push", FELIX, "{\"person\":\"torben\"}").statusCode()).isEqualTo(400);
    }

    /** Ohne Schluessel fuer APNs und Firebase geht nichts hinaus - die Antwort sagt das, ohne Fehler. */
    @Test
    void aPushWithoutConfiguredChannelsReachesNoDevice() throws Exception {
        HttpResponse<String> response = call("POST", "/api/internal/push", FELIX,
                "{\"person\":\"torben\",\"kind\":\"friend-nudge\",\"title\":\"Person A\",\"body\":\"stupst dich an\"}");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"ios\":0,\"android\":0}");
    }

    /** Von aussen (X-Real-IP, also ueber nginx) gibt es die Pfade nicht - mit leerer Antwort. */
    @Test
    void throughNginxTheEndpointsDoNotExist() throws Exception {
        HttpResponse<String> push = call("POST", "/api/internal/push", FELIX,
                "{\"person\":\"torben\",\"kind\":\"friend-nudge\",\"title\":\"Person A\"}", "X-Real-IP", "203.0.113.7");
        assertThat(push.statusCode()).isEqualTo(404);
        assertThat(push.body()).isEmpty();

        assertThat(call("POST", "/api/internal/recipe-dishes", RECIPES, "{\"persons\":[\"felix\"]," + DISH + "}",
                "X-Real-IP", "203.0.113.7").statusCode()).isEqualTo(404);
        assertThat(call("GET", "/api/internal/push", null, null, "X-Real-IP", "203.0.113.7").statusCode())
                .isEqualTo(404);
    }

    /** Falscher oder fehlender Schluessel: 403, und zwar auch nach dem Umweg ueber /error. */
    @Test
    void theWrongKeyIsForbidden() throws Exception {
        String dishes = "{\"persons\":[\"felix\"]," + DISH + "}";
        assertThat(call("POST", "/api/internal/recipe-dishes", FELIX, dishes).statusCode()).isEqualTo(403);
        assertThat(call("POST", "/api/internal/recipe-dishes", null, dishes).statusCode()).isEqualTo(403);
        assertThat(call("POST", "/api/internal/recipe-dishes", null, dishes, "Cookie", "fh_private=testtoken")
                .statusCode()).isEqualTo(403);
        String push = "{\"person\":\"torben\",\"kind\":\"friend-nudge\",\"title\":\"Person A\"}";
        assertThat(call("POST", "/api/internal/push", TORBEN, push).statusCode()).isEqualTo(403);
        assertThat(call("POST", "/api/internal/push", RECIPES, push).statusCode()).isEqualTo(403);
        assertThat(call("POST", "/api/internal/push", null, push, "Cookie", "fh_private=testtoken").statusCode())
                .isEqualTo(403);
        // Der Dienst-Token ist keine Person: das Tagebuch oeffnet er nicht.
        assertThat(call("GET", "/api/food/day", RECIPES, null).statusCode()).isEqualTo(403);
    }
}

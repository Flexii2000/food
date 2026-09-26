package com.fherrmann.food.featurerequest;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Der ganze Weg gegen einen echten Server: Wunsch, Entwurf (von einem Skript statt
 * Claude), Absenden, Unteraufgabe im nachgespielten To-Do, Abhaken, "erledigt".
 * Dazu, was MockMvc nicht sieht - der Fehlerstatus nach dem internen Weiterreichen
 * an {@code /error} (siehe {@code ErrorStatusIT}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "food.security.token=" + FakeTodoServer.TOKEN,
        "food.data-file=build/tmp/feature-requests-it/food.json",
        "food.feature-requests.data-file=" + FeatureRequestsIT.DATA_FILE,
        "food.feature-requests.base-url=http://localhost/feature-requests",
        "health.tokens=torben:" + FeatureRequestsIT.TORBEN,
})
class FeatureRequestsIT {

    static final String TORBEN = "0123456789abcdef0123456789abcdef";
    static final String DATA_FILE = "build/tmp/feature-requests-it/feature-requests.json";

    private static FakeTodoServer todo;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        todo = new FakeTodoServer();
        registry.add("food.todo.url", todo::baseUrl);

        Path script = Path.of("build/tmp/feature-requests-it/agent.sh").toAbsolutePath();
        Files.createDirectories(script.getParent());
        Files.writeString(script, """
                #!/bin/sh
                cat > /dev/null
                cat <<'OUT'
                {"type":"result","subtype":"success","is_error":false,"result":"{\\"title\\":\\"Dunkles Widget\\",\\"story\\":\\"Als Android-Nutzer möchte ich ein dunkles Widget, damit es nachts nicht blendet.\\",\\"acceptanceCriteria\\":[\\"Das Widget folgt dem Systemdesign.\\"]}"}
                OUT
                """);
        script.toFile().setExecutable(true);
        registry.add("food.story-agent.command", script::toString);
    }

    @AfterAll
    static void stopTodo() {
        todo.close();
    }

    @LocalServerPort
    private int port;

    @Autowired
    private FeatureRequestTodos todos;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        Files.deleteIfExists(Path.of(DATA_FILE));
        todo.areas.clear();
        todo.todos.clear();
        todo.area("Privat");
        todo.area("Server");
    }

    private HttpResponse<String> send(String method, String path, String body, boolean authenticated)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (authenticated) {
            request.header("Authorization", "Bearer " + TORBEN);
        }
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) {
        return mapper.readTree(response.body());
    }

    @Test
    void fromWishToTickedSubtask() throws Exception {
        HttpResponse<String> started = send("POST", "/feature-requests/api/drafts",
                "{\"text\":\"Ein dunkles Widget wäre toll\"}", true);
        assertThat(started.statusCode()).isEqualTo(202);
        String jobId = json(started).path("jobId").asString();

        JsonNode draft = null;
        for (int i = 0; i < 100; i++) {
            draft = json(send("GET", "/feature-requests/api/drafts/" + jobId, null, true));
            if (!"running".equals(draft.path("status").asString())) {
                break;
            }
            Thread.sleep(50);
        }
        assertThat(draft.path("status").asString()).isEqualTo("done");
        JsonNode card = draft.path("card");
        assertThat(card.path("title").asString()).isEqualTo("Dunkles Widget");

        // Torben aendert den Titel, bevor er absendet.
        String submission = mapper.writeValueAsString(java.util.Map.of(
                "originalText", "Ein dunkles Widget wäre toll",
                "title", "Dunkles Widget für die Nacht",
                "story", card.path("story").asString(),
                "acceptanceCriteria", java.util.List.of(card.path("acceptanceCriteria").get(0).asString(), "Neu dazu")));
        HttpResponse<String> created = send("POST", "/feature-requests/api/requests", submission, true);
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode request = json(created);
        assertThat(request.path("inTodo").asBoolean()).isTrue();
        String id = request.path("id").asString();

        FakeTodoServer.Todo subtask = todo.todos.stream()
                .filter(t -> "Dunkles Widget für die Nacht".equals(t.title)).findFirst().orElseThrow();
        assertThat(subtask.link).isEqualTo("http://localhost/feature-requests/" + id);
        FakeTodoServer.Todo parent = todo.find(subtask.parentId);
        assertThat(parent.title).isEqualTo("Healthy");
        assertThat(parent.areaId).isEqualTo(todo.areas.get(1).id());

        assertThat(json(send("GET", "/feature-requests/api/requests", null, true)).get(0).path("status").asString())
                .isEqualTo("open");
        subtask.doneAt = Instant.now();
        JsonNode listed = json(send("GET", "/feature-requests/api/requests", null, true)).get(0);
        assertThat(listed.path("status").asString()).isEqualTo("done");
        assertThat(listed.path("acceptanceCriteria").size()).isEqualTo(2);
    }

    /** Das To-Do faellt aus: die Anfrage ist trotzdem gespeichert, und der Nachlauf holt die Aufgabe nach. */
    @Test
    void aRequestSurvivesATodoOutage() throws Exception {
        todo.failStatus = 503;
        todo.failBody = "Wartung";

        HttpResponse<String> created = send("POST", "/feature-requests/api/requests",
                "{\"originalText\":\"x\",\"title\":\"Trotz Ausfall\",\"story\":\"Als … möchte ich …\"}", true);

        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(json(created).path("inTodo").asBoolean()).isFalse();
        assertThat(todo.todos).isEmpty();

        todos.catchUp();

        assertThat(json(send("GET", "/feature-requests/api/requests", null, true)).get(0).path("inTodo").asBoolean())
                .isTrue();
        assertThat(todo.todos).extracting(t -> t.title).contains("Healthy", "Trotz Ausfall");
    }

    @Test
    void aBadCardArrivesAsBadRequestWithItsReason() throws Exception {
        HttpResponse<String> response = send("POST", "/feature-requests/api/requests",
                "{\"title\":\"\",\"story\":\"s\"}", true);
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response).path("message").asString()).isEqualTo("Die Karte braucht einen Titel.");
    }

    @Test
    void withoutATokenPageAndApiAreForbidden() throws Exception {
        assertThat(send("GET", "/feature-requests/", null, false).statusCode()).isEqualTo(403);
        assertThat(send("GET", "/feature-requests/api/requests", null, false).statusCode()).isEqualTo(403);
        assertThat(send("GET", "/feature-requests/styles.css", null, false).statusCode()).isEqualTo(403);
    }

    @Test
    void someoneElsesDraftIsNotFound() throws Exception {
        assertThat(send("GET", "/feature-requests/api/drafts/gibt-es-nicht", null, true).statusCode()).isEqualTo(404);
    }
}

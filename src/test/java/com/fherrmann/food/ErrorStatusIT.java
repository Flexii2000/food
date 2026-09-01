package com.fherrmann.food;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Laeuft gegen einen echten Server, nicht gegen MockMvc - und das ist hier der
 * ganze Punkt.
 *
 * <p>Loest ein Endpunkt eine Ausnahme aus, stellt der Servlet-Container die
 * Anfrage intern nach {@code /error} zu. Dieser zweite Durchlauf ging urspruenglich
 * an der Cookie-Pruefung vorbei ({@code OncePerRequestFilter} laesst den Filter
 * beim ERROR-Dispatch per Vorgabe aus), der Kontext war leer, und Spring Security
 * ersetzte jeden Fehlerstatus durch ein 403. MockMvc fuehrt diesen zweiten
 * Durchlauf nicht aus und sah davon nichts - die Slice-Tests waren gruen, waehrend
 * live jeder Fehler als "nicht autorisiert" ankam.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "food.security.token=testtoken",
        "food.data-file=build/tmp/error-status-it/food.json",
})
class ErrorStatusIT {

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    /** Roher HTTP-Aufruf ueber den JDK-Client - bewusst ohne Spring-Hilfsmittel,
     *  damit hier wirklich das ankommt, was auch ein Browser bekaeme. */
    /** Wie {@link #status}, gibt aber den Antwortkoerper zurueck. */
    private String body(String method, String path, String payload) throws IOException, InterruptedException {
        return send(method, path, payload).body();
    }

    private int status(String method, String path, String body) throws IOException, InterruptedException {
        return send(method, path, body).statusCode();
    }

    /** Zieht die Auftragsnummer aus der Startantwort. */
    private static String jobId(String json) {
        int at = json.indexOf("\"id\":\"") + 6;
        return json.substring(at, json.indexOf('"', at));
    }

    private HttpResponse<String> send(String method, String path, String body)
            throws IOException, InterruptedException {
        HttpRequest.BodyPublisher payload = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Cookie", "fh_private=testtoken")
                .method(method, payload);
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aBadRequestArrivesAsBadRequestAndNotAsForbidden() throws Exception {
        assertThat(status("POST", "/api/food/entries", "{}")).isEqualTo(400);
    }

    @Test
    void anUnknownEntryArrivesAsNotFound() throws Exception {
        assertThat(status("DELETE", "/api/food/entries/gibtsnicht", null)).isEqualTo(404);
    }

    @Test
    void aMissingQueryParameterArrivesAsBadRequest() throws Exception {
        assertThat(status("GET", "/api/food/daily", null)).isEqualTo(400);
    }

    @Test
    void anUnconfiguredAgentSurfacesAsAFailedJob() throws Exception {
        // Im Test ist kein Agent-Kommando gesetzt. Der Start gelingt trotzdem;
        // dass es nicht eingerichtet ist, steht im Stand des Auftrags - und nicht
        // als Rechteproblem, wie es frueher ohne den ERROR-Dispatch-Fix aussah.
        String id = jobId(body("POST", "/api/food/quick-capture", "{\"text\":\"ein Apfel\"}"));

        String state = "";
        for (int i = 0; i < 50 && !state.contains("\"status\":\"failed\""); i++) {
            Thread.sleep(100);
            state = body("GET", "/api/food/quick-capture/" + id, null);
        }
        assertThat(state).contains("\"status\":\"failed\"").contains("nicht eingerichtet");
    }

    @Test
    void quickCaptureStartsAJobInsteadOfBlocking() throws Exception {
        // Ohne Agent-Kommando schlaegt die Auswertung fehl - der START muss
        // trotzdem sofort mit 202 antworten. Genau das ist der Punkt des Umbaus:
        // keine Anfrage haengt mehr eine Minute am Draht.
        long before = System.currentTimeMillis();
        assertThat(status("POST", "/api/food/quick-capture", "{\"text\":\"ein Apfel\"}"))
                .isEqualTo(202);
        assertThat(System.currentTimeMillis() - before).isLessThan(5000);
    }

    @Test
    void anUnknownJobIsNotFound() throws Exception {
        assertThat(status("GET", "/api/food/quick-capture/gibtsnicht", null)).isEqualTo(404);
    }

    @Test
    void withoutTheCookieItIsStillForbidden() throws Exception {
        int code = http.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/food/day")).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode();
        assertThat(code).isEqualTo(403);
    }
}

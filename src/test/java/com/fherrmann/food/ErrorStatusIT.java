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
    private int status(String method, String path, String body) throws IOException, InterruptedException {
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
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
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
    void quickCaptureWithoutAnApiKeyReportsThatItIsUnavailable() throws Exception {
        // Im Test ist kein Schluessel gesetzt - der Endpunkt muss das sagen,
        // statt wie ein Rechteproblem auszusehen.
        assertThat(status("POST", "/api/food/quick-capture", "{\"text\":\"ein Apfel\"}"))
                .isEqualTo(503);
    }

    @Test
    void withoutTheCookieItIsStillForbidden() throws Exception {
        int code = http.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/food/day")).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode();
        assertThat(code).isEqualTo(403);
    }
}

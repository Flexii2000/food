package com.fherrmann.food.push;

import com.fherrmann.food.push.ApnsClient.Result;
import com.fherrmann.food.push.DeviceTokens.Environment;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gegen zwei lokale Server, die Apples Sandbox und Produktion spielen. Geprueft wird,
 * an welchen Rechner eine Mitteilung geht, was in der Nutzlast steht und wie der Client
 * Apples Antworten deutet.
 */
class ApnsClientSendTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer sandbox;
    private HttpServer production;
    private Path keyFile;

    /** Was bei welchem Rechner ankam: "<pfad> <topic> <nutzlast>". */
    private final List<String> sandboxRequests = new ArrayList<>();
    private final List<String> productionRequests = new ArrayList<>();
    private volatile int sandboxStatus = 200;
    private volatile String sandboxBody = "";
    private volatile int productionStatus = 200;
    private volatile String productionBody = "";

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        keyFile = tempDir.resolve("apns.p8");
        Files.writeString(keyFile, "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(
                        generator.generateKeyPair().getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n");

        sandbox = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        sandbox.createContext("/3/device/", exchange -> {
            record(sandboxRequests, exchange);
            respond(exchange, sandboxStatus, sandboxBody);
        });
        sandbox.start();
        production = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        production.createContext("/3/device/", exchange -> {
            record(productionRequests, exchange);
            respond(exchange, productionStatus, productionBody);
        });
        production.start();
    }

    @AfterEach
    void tearDown() {
        sandbox.stop(0);
        production.stop(0);
    }

    private ApnsClient client() {
        return new ApnsClient(keyFile.toString(), "KEYID12345", "TEAMID1234", "com.example.app",
                "http://127.0.0.1:" + sandbox.getAddress().getPort(),
                "http://127.0.0.1:" + production.getAddress().getPort(), mapper);
    }

    private static void record(List<String> requests, HttpExchange exchange) throws IOException {
        String payload = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        synchronized (requests) {
            requests.add(exchange.getRequestURI().getPath() + " "
                    + exchange.getRequestHeaders().getFirst("apns-topic") + " " + payload);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    /** Die Umgebung der Kennung entscheidet ueber den Rechner - mit demselben Schluessel und Topic. */
    @Test
    void eachEnvironmentHasItsOwnHost() {
        ApnsClient client = client();

        assertThat(client.send(Environment.SANDBOX, "aa11", "Titel", "Text", "chat-answer", Map.of()))
                .isEqualTo(Result.DELIVERED);
        assertThat(client.send(Environment.PRODUCTION, "bb22", "Titel", "Text", "chat-answer", Map.of()))
                .isEqualTo(Result.DELIVERED);

        assertThat(sandboxRequests).hasSize(1);
        assertThat(sandboxRequests.get(0)).startsWith("/3/device/aa11 com.example.app {");
        assertThat(productionRequests).hasSize(1);
        assertThat(productionRequests.get(0)).startsWith("/3/device/bb22 com.example.app {");
    }

    /** Art und Daten stehen neben aps - dort liest die App ab, wohin ein Tipp fuehrt. */
    @Test
    void thePayloadCarriesKindAndDataNextToAps() {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("jobId", "job-1");
        data.put("status", "done");
        // Was dem Dienst gehoert, laesst sich nicht ueberschreiben.
        data.put("kind", "gefaelscht");
        data.put("aps", "gefaelscht");

        JsonNode payload = mapper.readTree(client().payload("Vorschlag ist fertig", "Gericht A", "quick-capture", data));

        assertThat(payload.path("aps").path("alert").path("title").asString()).isEqualTo("Vorschlag ist fertig");
        assertThat(payload.path("aps").path("alert").path("body").asString()).isEqualTo("Gericht A");
        assertThat(payload.path("aps").path("sound").asString()).isEqualTo("default");
        assertThat(payload.path("kind").asString()).isEqualTo("quick-capture");
        assertThat(payload.path("jobId").asString()).isEqualTo("job-1");
        assertThat(payload.path("status").asString()).isEqualTo("done");
        assertThat(payload.propertyNames()).containsExactly("aps", "kind", "jobId", "status");
    }

    /**
     * Anfuehrungszeichen, Backslashes, Umlaute und Emoji in Titel und Text ergeben
     * gueltiges JSON, das unveraendert wieder herauskommt; Zeilenumbrueche werden
     * Leerzeichen, wie bisher.
     */
    @Test
    void quotesUmlautsAndLineBreaksStayValidJson() {
        String title = "Serie mit \"Jörg\" \\ Zoë 💪";
        String body = "noch 2.300 Schritte · noch 45 g Eiweiß\nzweite Zeile\r\nende";

        JsonNode payload = mapper.readTree(client().payload(title, body, "streak-warning", null));

        assertThat(payload.path("aps").path("alert").path("title").asString()).isEqualTo(title);
        assertThat(payload.path("aps").path("alert").path("body").asString())
                .isEqualTo("noch 2.300 Schritte · noch 45 g Eiweiß zweite Zeile  ende");
        assertThat(payload.propertyNames()).containsExactly("aps", "kind");
    }

    /** Ohne Art (so schickte der Dienst frueher) steht auch keine in der Nutzlast. */
    @Test
    void aPayloadWithoutKindHasNone() {
        JsonNode payload = mapper.readTree(client().payload("Titel", "Text", null, null));
        assertThat(payload.propertyNames()).containsExactly("aps");
    }

    /** Was wirklich ueber die Leitung geht, ist dieselbe Nutzlast - als UTF-8. */
    @Test
    void theSentBodyIsThePayload() {
        client().send(Environment.SANDBOX, "aa11", "Jörg", "stupst dich an", "friend-nudge", Map.of("x", "y"));

        String sent = sandboxRequests.get(0);
        JsonNode payload = mapper.readTree(sent.substring(sent.indexOf('{')));
        assertThat(payload.path("aps").path("alert").path("title").asString()).isEqualTo("Jörg");
        assertThat(payload.path("kind").asString()).isEqualTo("friend-nudge");
        assertThat(payload.path("x").asString()).isEqualTo("y");
    }

    /** BadDeviceToken, 410 und alles andere sind drei verschiedene Auskuenfte ueber die Kennung. */
    @Test
    void applesAnswersAreToldApart() {
        ApnsClient client = client();

        sandboxStatus = 400;
        sandboxBody = "{\"reason\":\"BadDeviceToken\"}";
        assertThat(client.send(Environment.SANDBOX, "aa11", "T", "B", "k", Map.of())).isEqualTo(Result.BAD_TOKEN);

        sandboxStatus = 410;
        sandboxBody = "{\"reason\":\"Unregistered\",\"timestamp\":1}";
        assertThat(client.send(Environment.SANDBOX, "aa11", "T", "B", "k", Map.of())).isEqualTo(Result.GONE);

        sandboxStatus = 503;
        sandboxBody = "{\"reason\":\"ServiceUnavailable\"}";
        assertThat(client.send(Environment.SANDBOX, "aa11", "T", "B", "k", Map.of())).isEqualTo(Result.KEPT);

        sandboxStatus = 403;
        sandboxBody = "{\"reason\":\"InvalidProviderToken\"}";
        assertThat(client.send(Environment.SANDBOX, "aa11", "T", "B", "k", Map.of())).isEqualTo(Result.KEPT);
        // Die Produktion hat von alledem nichts gesehen.
        assertThat(productionRequests).isEmpty();
    }

    /** Ist der Rechner nicht erreichbar, kostet das keine Kennung. */
    @Test
    void anUnreachableHostKeepsTheToken() {
        ApnsClient client = client();
        production.stop(0);
        assertThat(client.send(Environment.PRODUCTION, "bb22", "T", "B", "k", Map.of())).isEqualTo(Result.KEPT);
    }

    /** Ohne Schluessel passiert nichts - auch keine Anfrage. */
    @Test
    void withoutAKeyNothingIsSent() {
        ApnsClient client = new ApnsClient("", "", "", "com.example.app",
                "http://127.0.0.1:" + sandbox.getAddress().getPort(),
                "http://127.0.0.1:" + production.getAddress().getPort(), mapper);

        assertThat(client.isConfigured()).isFalse();
        assertThat(client.send(Environment.SANDBOX, "aa11", "T", "B", "k", Map.of())).isEqualTo(Result.KEPT);
        assertThat(sandboxRequests).isEmpty();
    }
}

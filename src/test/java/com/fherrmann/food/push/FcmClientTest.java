package com.fherrmann.food.push;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gegen einen lokalen Server, der Googles Token-Endpunkt und FCM spielt. Geprueft
 * wird, was ueber die Leitung geht - das signierte JWT, die Nachricht - und wie der
 * Client auf die Antworten reagiert.
 */
class FcmClientTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private KeyPair keys;
    private Path serviceAccount;

    private final AtomicReference<String> assertion = new AtomicReference<>();
    private final List<String> messages = new ArrayList<>();
    private final AtomicInteger tokenRequests = new AtomicInteger();
    private volatile int sendStatus = 200;
    private volatile String sendBody = "{\"name\":\"projects/healthy-test/messages/1\"}";

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            tokenRequests.incrementAndGet();
            String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            for (String pair : form.split("&")) {
                if (pair.startsWith("assertion=")) {
                    assertion.set(URLDecoder.decode(pair.substring(10), StandardCharsets.UTF_8));
                }
            }
            respond(exchange, 200, "{\"access_token\":\"ya29.test\",\"expires_in\":3599,\"token_type\":\"Bearer\"}");
        });
        server.createContext("/v1/projects/healthy-test/messages:send", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            synchronized (messages) {
                messages.add(auth + " " + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            }
            respond(exchange, sendStatus, sendBody);
        });
        server.start();

        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keys.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        serviceAccount = tempDir.resolve("fcm.json");
        Files.writeString(serviceAccount, mapper.writeValueAsString(Map.of(
                "type", "service_account",
                "project_id", "healthy-test",
                "private_key_id", "key-1",
                "private_key", pem,
                "client_email", "push@healthy-test.iam.gserviceaccount.com",
                "token_uri", base() + "/token")));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private FcmClient client() {
        return new FcmClient(serviceAccount.toString(), base(), mapper);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    void withoutAServiceAccountNothingHappens() {
        FcmClient client = new FcmClient("", base(), mapper);
        assertThat(client.isConfigured()).isFalse();
        assertThat(client.send("fcm-1", Map.of("kind", "x"))).isTrue();
        assertThat(messages).isEmpty();
    }

    /** Das JWT ist mit dem Schluessel des Dienstkontos signiert und traegt, was Google verlangt. */
    @Test
    void theAssertionIsASignedJwtForTheMessagingScope() throws Exception {
        assertThat(client().send("fcm-1", Map.of("kind", "quick-capture"))).isTrue();

        String[] parts = assertion.get().split("\\.");
        assertThat(parts).hasSize(3);
        JsonNode header = mapper.readTree(Base64.getUrlDecoder().decode(parts[0]));
        JsonNode claims = mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertThat(header.path("alg").asString()).isEqualTo("RS256");
        assertThat(header.path("kid").asString()).isEqualTo("key-1");
        assertThat(claims.path("iss").asString()).isEqualTo("push@healthy-test.iam.gserviceaccount.com");
        assertThat(claims.path("scope").asString()).isEqualTo("https://www.googleapis.com/auth/firebase.messaging");
        assertThat(claims.path("aud").asString()).isEqualTo(base() + "/token");
        assertThat(claims.path("exp").asLong() - claims.path("iat").asLong()).isEqualTo(3600);

        Signature verify = Signature.getInstance("SHA256withRSA");
        verify.initVerify(keys.getPublic());
        verify.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
        assertThat(verify.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
    }

    @Test
    void sendsADataMessageWithHighPriorityAndReusesTheAccessToken() throws Exception {
        FcmClient client = client();
        client.send("fcm-1", Map.of("kind", "quick-capture", "jobId", "j1"));
        client.send("fcm-2", Map.of("kind", "app-update"));

        assertThat(tokenRequests.get()).isEqualTo(1);
        assertThat(messages).hasSize(2);
        String first = messages.get(0);
        assertThat(first).startsWith("Bearer ya29.test ");
        JsonNode message = mapper.readTree(first.substring("Bearer ya29.test ".length())).path("message");
        assertThat(message.path("token").asString()).isEqualTo("fcm-1");
        assertThat(message.path("data").path("jobId").asString()).isEqualTo("j1");
        assertThat(message.path("android").path("priority").asString()).isEqualTo("high");
        assertThat(message.has("notification")).isFalse();
    }

    @Test
    void anUnregisteredTokenIsReportedAsGone() {
        sendStatus = 404;
        sendBody = """
                {"error":{"code":404,"message":"Requested entity was not found.","status":"NOT_FOUND",
                 "details":[{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError","errorCode":"UNREGISTERED"}]}}
                """;
        assertThat(client().send("fcm-alt", Map.of("kind", "x"))).isFalse();
    }

    /** Ein Serverfehler ist kein Grund, eine Kennung wegzuwerfen. */
    @Test
    void transientErrorsKeepTheToken() {
        sendStatus = 503;
        sendBody = "{\"error\":{\"code\":503,\"status\":\"UNAVAILABLE\"}}";
        assertThat(client().send("fcm-1", Map.of("kind", "x"))).isTrue();
        sendStatus = 404;
        sendBody = "{\"error\":{\"code\":404,\"message\":\"Project not found\"}}";
        assertThat(client().send("fcm-1", Map.of("kind", "x"))).isTrue();
    }

    @Test
    void goneIsDecidedOnlyByWhatFirebaseSaysExplicitly() {
        assertThat(FcmClient.isGone(400, "The registration token is not a valid FCM registration token")).isTrue();
        assertThat(FcmClient.isGone(403, "{\"errorCode\":\"SENDER_ID_MISMATCH\"}")).isTrue();
        assertThat(FcmClient.isGone(400, "Invalid JSON payload")).isFalse();
        assertThat(FcmClient.isGone(500, null)).isFalse();
    }
}

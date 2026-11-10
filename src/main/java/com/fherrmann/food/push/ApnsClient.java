package com.fherrmann.food.push;

import com.fherrmann.food.push.DeviceTokens.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Schickt Benachrichtigungen an Apples Push-Dienst.
 *
 * <p>Bewusst ohne Bibliothek: APNs ist ein HTTP/2-POST mit einem signierten
 * Token im Kopf, und beides kann das JDK. Eine Abhaengigkeit fuer dreissig
 * Zeilen einzuziehen waere mehr Pflegeaufwand als Ersparnis.
 *
 * <p><b>Sandbox oder Produktion:</b> welchen Host man braucht, entscheidet
 * nicht der Server, sondern womit die App signiert wurde. Eine
 * Entwicklungssignatur liefert Kennungen, die <em>nur</em> die Sandbox kennt -
 * schickt man sie an den Produktionshost, kommt {@code BadDeviceToken} zurueck
 * und sonst nichts. Seit 2026-11-10 gibt es beides nebeneinander (Felix'
 * Entwicklungs-Installation und TestFlight-Builds): die App meldet ihre Umgebung
 * mit der Kennung an, und jeder Aufruf nennt, an welchen Rechner er geht. Derselbe
 * Schluessel gilt fuer beide.
 */
@Component
public class ApnsClient {

    private static final Logger log = LoggerFactory.getLogger(ApnsClient.class);

    /**
     * Apple laesst ein Token bis zu einer Stunde gelten und lehnt ab, wer
     * oefter als alle zwanzig Minuten ein neues erzeugt. Fuenfundvierzig
     * Minuten liegen bequem dazwischen.
     */
    private static final Duration TOKEN_LIFETIME = Duration.ofMinutes(45);

    /** Was aus einem Zustellversuch geworden ist - danach richtet sich, was mit der Kennung passiert. */
    public enum Result {
        /** Apple hat die Nachricht angenommen. */
        DELIVERED,
        /** Nicht zugestellt, aber die Kennung sagt nichts dazu (Netz, Ueberlast, kein Schluessel). */
        KEPT,
        /** {@code BadDeviceToken}: meist die falsche Umgebung - der andere Rechner kennt sie vielleicht. */
        BAD_TOKEN,
        /** 410: das Geraet hat die App nicht mehr. */
        GONE
    }

    private final String keyFile;
    private final String keyId;
    private final String teamId;
    private final String topic;
    private final String sandboxHost;
    private final String productionHost;
    private final ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private volatile String cachedToken;
    private volatile Instant cachedUntil = Instant.EPOCH;

    public ApnsClient(
            @Value("${food.push.key-file:}") String keyFile,
            @Value("${food.push.key-id:}") String keyId,
            @Value("${food.push.team-id:}") String teamId,
            @Value("${food.push.topic:com.fherrmann.cockpit}") String topic,
            @Value("${food.push.host:https://api.sandbox.push.apple.com}") String sandboxHost,
            @Value("${food.push.production-host:https://api.push.apple.com}") String productionHost,
            ObjectMapper objectMapper) {
        this.keyFile = keyFile;
        this.keyId = keyId;
        this.teamId = teamId;
        this.topic = topic;
        this.sandboxHost = sandboxHost;
        this.productionHost = productionHost;
        this.objectMapper = objectMapper;
    }

    /** Ohne Schluessel gibt es keine Benachrichtigungen - und das ist in Ordnung. */
    public boolean isConfigured() {
        return !keyFile.isBlank() && !keyId.isBlank() && !teamId.isBlank();
    }

    /**
     * Schickt eine Mitteilung an den APNs-Rechner dieser Umgebung.
     *
     * @param kind die Art der Mitteilung - steht neben {@code aps} in der Nutzlast, die
     *             App liest daran ab, wohin ein Tipp fuehrt
     * @param data weitere Schluessel der Nutzlast, oder {@code null}
     */
    public Result send(Environment environment, String deviceToken, String title, String body,
                       String kind, Map<String, String> data) {
        if (!isConfigured()) {
            return Result.KEPT;
        }
        String host = environment == Environment.PRODUCTION ? productionHost : sandboxHost;
        try {
            String payload = payload(title, body, kind, data);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(host + "/3/device/" + deviceToken))
                    .header("authorization", "bearer " + authenticationToken())
                    .header("apns-topic", topic)
                    .header("apns-push-type", "alert")
                    .header("apns-priority", "10")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return Result.DELIVERED;
            }
            log.warn("APNs ({}) antwortete {}: {}", environment, response.statusCode(), response.body());
            // 410 heisst: das Geraet hat die App nicht mehr. 400 mit
            // BadDeviceToken heisst meist: falsche Umgebung oder alte Kennung.
            if (response.statusCode() == 410) {
                return Result.GONE;
            }
            return response.body().contains("BadDeviceToken") ? Result.BAD_TOKEN : Result.KEPT;
        } catch (Exception e) {
            // Eine fehlgeschlagene Benachrichtigung darf den Eintrag nicht
            // gefaehrden - der steht laengst.
            log.warn("Benachrichtigung konnte nicht zugestellt werden", e);
            return Result.KEPT;
        }
    }

    /**
     * Die Nutzlast: {@code aps} mit Titel und Text, daneben {@code kind} und die
     * uebrigen Schluessel. Ueber den {@link ObjectMapper} gebaut, damit Anfuehrungszeichen,
     * Umlaute und alles andere in einem Namen oder Fehlertext gueltiges JSON bleiben.
     */
    String payload(String title, String body, String kind, Map<String, String> data) {
        Map<String, Object> alert = new LinkedHashMap<>();
        alert.put("title", singleLine(title));
        alert.put("body", singleLine(body));
        Map<String, Object> aps = new LinkedHashMap<>();
        aps.put("alert", alert);
        aps.put("sound", "default");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("aps", aps);
        if (kind != null && !kind.isBlank()) {
            payload.put("kind", kind);
        }
        if (data != null) {
            // "aps" und "kind" gehoeren dem Dienst - eine Angabe von aussen ueberschreibt sie nicht.
            data.forEach((key, value) -> {
                if (value != null) {
                    payload.putIfAbsent(key, value);
                }
            });
        }
        return objectMapper.writeValueAsString(payload);
    }

    // MARK: - Signiertes Token

    private synchronized String authenticationToken() throws Exception {
        if (cachedToken != null && Instant.now().isBefore(cachedUntil)) {
            return cachedToken;
        }
        String header = base64("{\"alg\":\"ES256\",\"kid\":\"" + keyId + "\"}");
        String claims = base64("{\"iss\":\"" + teamId + "\",\"iat\":"
                + Instant.now().getEpochSecond() + "}");
        String signed = header + "." + claims;

        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(privateKey());
        signature.update(signed.getBytes(StandardCharsets.UTF_8));

        String token = signed + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(toJose(signature.sign()));
        cachedToken = token;
        cachedUntil = Instant.now().plus(TOKEN_LIFETIME);
        return token;
    }

    private PrivateKey privateKey() throws Exception {
        String pem = Files.readString(Path.of(keyFile))
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(pem);
        return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static String base64(String json) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Wandelt die Signatur von DER nach JOSE.
     *
     * <p>Der stille Fallstrick an ES256: die JCA liefert eine DER-Struktur
     * (SEQUENCE aus zwei INTEGERn mit variabler Laenge), JWT erwartet aber
     * schlicht 64 Byte - R und S, je 32, rechtsbuendig mit Nullen aufgefuellt.
     * Wer die DER-Bytes direkt einsetzt, bekommt von Apple ein
     * {@code InvalidProviderToken} und sucht den Fehler ueberall sonst.
     */
    static byte[] toJose(byte[] der) {
        int offset = (der[1] & 0xFF) > 0x80 ? 3 : 2;
        int rLength = der[offset + 1];
        int rStart = offset + 2;
        int sLength = der[rStart + rLength + 1];
        int sStart = rStart + rLength + 2;

        byte[] jose = new byte[64];
        copyRightAligned(der, rStart, rLength, jose, 0);
        copyRightAligned(der, sStart, sLength, jose, 32);
        return jose;
    }

    private static void copyRightAligned(byte[] source, int start, int length,
                                         byte[] target, int targetOffset) {
        int from = start;
        int count = length;
        // DER haengt eine fuehrende Null an, wenn das oberste Bit gesetzt ist -
        // die gehoert nicht in die 32 Byte.
        if (count > 32) {
            from += count - 32;
            count = 32;
        }
        System.arraycopy(source, from, target, targetOffset + 32 - count, count);
    }

    /** Eine Mitteilung ist einzeilig gedacht - Zeilenumbrueche (etwa aus einem Fehlertext) werden Leerzeichen. */
    private static String singleLine(String text) {
        return text == null ? "" : text.replace("\n", " ").replace("\r", " ");
    }
}

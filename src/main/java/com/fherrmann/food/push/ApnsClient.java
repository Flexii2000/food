package com.fherrmann.food.push;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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
 * und sonst nichts. Deshalb ist der Host einstellbar und steht standardmaessig
 * auf der Sandbox.
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

    private final String keyFile;
    private final String keyId;
    private final String teamId;
    private final String topic;
    private final String host;

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
            @Value("${food.push.host:https://api.sandbox.push.apple.com}") String host) {
        this.keyFile = keyFile;
        this.keyId = keyId;
        this.teamId = teamId;
        this.topic = topic;
        this.host = host;
    }

    /** Ohne Schluessel gibt es keine Benachrichtigungen - und das ist in Ordnung. */
    public boolean isConfigured() {
        return !keyFile.isBlank() && !keyId.isBlank() && !teamId.isBlank();
    }

    /**
     * @return {@code true}, wenn die Kennung weiter benutzbar ist;
     *         {@code false}, wenn Apple sie abgelehnt hat und sie weg soll.
     */
    public boolean send(String deviceToken, String title, String body) {
        if (!isConfigured()) {
            return true;
        }
        String payload = """
                {"aps":{"alert":{"title":"%s","body":"%s"},"sound":"default"}}"""
                .formatted(escape(title), escape(body));
        try {
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
                return true;
            }
            // 410 heisst: das Geraet hat die App nicht mehr. 400 mit
            // BadDeviceToken heisst meist: falsche Umgebung oder alte Kennung.
            boolean gone = response.statusCode() == 410
                    || response.body().contains("BadDeviceToken");
            log.warn("APNs antwortete {}: {}", response.statusCode(), response.body());
            return !gone;
        } catch (Exception e) {
            // Eine fehlgeschlagene Benachrichtigung darf den Eintrag nicht
            // gefaehrden - der steht laengst.
            log.warn("Benachrichtigung konnte nicht zugestellt werden", e);
            return true;
        }
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

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ");
    }
}

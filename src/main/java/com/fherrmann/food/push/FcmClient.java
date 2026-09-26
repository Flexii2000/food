package com.fherrmann.food.push;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
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
 * Schickt Benachrichtigungen an Android-Geraete ueber Firebase Cloud Messaging.
 *
 * <p>Bewusst ohne Firebase-SDK, aus demselben Grund wie beim {@link ApnsClient}:
 * die HTTP-v1-Schnittstelle ist ein POST mit einem Bearer-Token, und das Token
 * holt man sich mit einem RS256-signierten JWT aus dem Dienstkonto. Beides kann
 * das JDK; das Admin-SDK braechte dafuer ein gutes Dutzend Abhaengigkeiten mit.
 *
 * <p>Geschickt werden reine Datennachrichten ({@code data}, keine
 * {@code notification}): die App baut die Benachrichtigung selbst, mit eigenem
 * Kanal und dem Sprung an die richtige Stelle. Prioritaet {@code high}, sonst
 * kommt eine fertige Schnellerfassung erst an, wenn das Handy aus dem Doze
 * aufwacht.
 *
 * <p>Ohne Dienstkonto-Datei passiert nichts - wie bei APNs ohne Schluessel. Die
 * App fragt eine laufende Schnellerfassung ohnehin selbst nach, solange sie offen ist.
 */
@Component
public class FcmClient {

    private static final Logger log = LoggerFactory.getLogger(FcmClient.class);

    private static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

    /**
     * Google stellt Zugriffstoken fuer eine Stunde aus. Fuenf Minuten vor Ablauf
     * wird erneuert, damit keine Nachricht mit einem Token losgeht, das unterwegs
     * verfaellt.
     */
    private static final Duration RENEW_BEFORE_EXPIRY = Duration.ofMinutes(5);

    /** Wie lange eine Nachricht beim Dienst wartet, wenn das Handy aus ist. */
    private static final String TTL = "86400s";

    private record ServiceAccount(String projectId, String clientEmail, String keyId,
                                  PrivateKey privateKey, String tokenUri) {
    }

    private final String serviceAccountFile;
    private final String endpoint;
    private final ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private volatile ServiceAccount account;
    private volatile String accessToken;
    private volatile Instant accessTokenUntil = Instant.EPOCH;

    public FcmClient(
            @Value("${food.fcm.service-account-file:}") String serviceAccountFile,
            @Value("${food.fcm.endpoint:https://fcm.googleapis.com}") String endpoint,
            ObjectMapper objectMapper) {
        this.serviceAccountFile = serviceAccountFile == null ? "" : serviceAccountFile.trim();
        this.endpoint = endpoint;
        this.objectMapper = objectMapper;
    }

    /** Ohne Dienstkonto gibt es keine Android-Benachrichtigungen - und das ist in Ordnung. */
    public boolean isConfigured() {
        return !serviceAccountFile.isEmpty();
    }

    /**
     * @param data Schluessel und Werte der Datennachricht - bei FCM ausschliesslich Strings
     * @return {@code true}, wenn die Kennung weiter benutzbar ist; {@code false}, wenn
     *         Firebase sie abgelehnt hat und sie weg soll
     */
    public boolean send(String deviceToken, Map<String, String> data) {
        if (!isConfigured()) {
            return true;
        }
        try {
            ServiceAccount sa = account();
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("token", deviceToken);
            message.put("data", data);
            message.put("android", Map.of("priority", "high", "ttl", TTL));
            String body = objectMapper.writeValueAsString(Map.of("message", message));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint + "/v1/projects/" + sa.projectId() + "/messages:send"))
                    .header("Authorization", "Bearer " + accessToken(sa))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return true;
            }
            if (response.statusCode() == 401) {
                // Das Token galt nicht (mehr) - beim naechsten Mal ein frisches holen.
                accessToken = null;
            }
            log.warn("FCM antwortete {}: {}", response.statusCode(), response.body());
            return !isGone(response.statusCode(), response.body());
        } catch (Exception e) {
            // Eine fehlgeschlagene Benachrichtigung darf nichts anderes gefaehrden -
            // der Eintrag bzw. die Auswertung steht laengst.
            log.warn("FCM-Nachricht konnte nicht zugestellt werden", e);
            return true;
        }
    }

    /**
     * Ob die Kennung tot ist. Bewusst eng: ein 404 allein koennte auch ein falsch
     * eingetragenes Projekt sein, und dann flogen alle Kennungen raus. Tot ist,
     * was Firebase ausdruecklich so nennt - die App meldet eine lebende ohnehin
     * beim naechsten Start wieder an.
     */
    static boolean isGone(int status, String body) {
        String text = body == null ? "" : body;
        return text.contains("UNREGISTERED")
                || text.contains("SENDER_ID_MISMATCH")
                || (status == 400 && text.contains("registration token"));
    }

    // MARK: - Zugriffstoken

    private synchronized String accessToken(ServiceAccount sa) throws Exception {
        if (accessToken != null && Instant.now().isBefore(accessTokenUntil)) {
            return accessToken;
        }
        long now = Instant.now().getEpochSecond();
        String header = base64Json(Map.of("alg", "RS256", "typ", "JWT", "kid", sa.keyId()));
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", sa.clientEmail());
        claims.put("scope", SCOPE);
        claims.put("aud", sa.tokenUri());
        claims.put("iat", now);
        claims.put("exp", now + 3600);
        String unsigned = header + "." + base64Json(claims);

        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(sa.privateKey());
        signature.update(unsigned.getBytes(StandardCharsets.UTF_8));
        String jwt = unsigned + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());

        String form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer",
                StandardCharsets.UTF_8) + "&assertion=" + URLEncoder.encode(jwt, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(sa.tokenUri()))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Token-Abruf bei Google: HTTP " + response.statusCode()
                    + " " + response.body());
        }
        JsonNode json = objectMapper.readTree(response.body());
        String token = json.path("access_token").asString("");
        if (token.isEmpty()) {
            throw new IllegalStateException("Token-Abruf bei Google: kein access_token in der Antwort");
        }
        long lifetime = json.path("expires_in").asLong(3600);
        accessToken = token;
        accessTokenUntil = Instant.now().plusSeconds(lifetime).minus(RENEW_BEFORE_EXPIRY);
        return token;
    }

    /**
     * Das Dienstkonto aus der JSON-Datei, die Firebase unter
     * "Projekteinstellungen / Dienstkonten" erzeugt. Beim ersten Gebrauch gelesen,
     * nicht beim Start: eine kaputte Datei soll nur die Benachrichtigungen kosten,
     * nicht den ganzen Dienst.
     */
    private ServiceAccount account() throws Exception {
        ServiceAccount current = account;
        if (current != null) {
            return current;
        }
        JsonNode json = objectMapper.readTree(Files.readString(Path.of(serviceAccountFile)));
        String pem = json.path("private_key").asString("")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        PrivateKey key = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
        ServiceAccount loaded = new ServiceAccount(
                required(json, "project_id"),
                required(json, "client_email"),
                json.path("private_key_id").asString(""),
                key,
                json.path("token_uri").asString("https://oauth2.googleapis.com/token"));
        account = loaded;
        return loaded;
    }

    private static String required(JsonNode json, String field) {
        String value = json.path(field).asString("");
        if (value.isEmpty()) {
            throw new IllegalStateException("Dienstkonto-Datei ohne " + field);
        }
        return value;
    }

    private String base64Json(Map<String, ?> value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(objectMapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8));
    }
}

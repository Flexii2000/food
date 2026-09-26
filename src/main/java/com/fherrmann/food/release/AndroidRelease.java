package com.fherrmann.food.release;

import com.fherrmann.food.dto.AndroidReleaseInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Die veroeffentlichte Android-App: {@code healthy.apk} und {@code latest.json}
 * ({@code {"versionCode": 3, "versionName": "1.2"}}) in einem Verzeichnis, das
 * das Veroeffentlichungs-Skript auf dem Mac fuellt.
 *
 * <p>Warum nicht der Play Store: die App ist fuer zwei, drei Leute, und ein
 * Entwicklerkonto mit Pruefung fuer jede Version waere mehr Aufwand als die App.
 * Der Dienst liefert sie deshalb selbst aus - hinter denselben Token wie alles
 * andere.
 *
 * <p>Das Verzeichnis liegt bewusst ausserhalb von {@code /opt/food}: der Dienst
 * liest es nur, geschrieben wird es ohne sudo vom Veroeffentlichen. Leer
 * konfiguriert heisst: es gibt keine Android-App, beide Endpunkte antworten 404.
 */
@Component
public class AndroidRelease {

    private static final Logger log = LoggerFactory.getLogger(AndroidRelease.class);

    static final String APK = "healthy.apk";
    static final String META = "latest.json";

    /** Pruefsumme der zuletzt gesehenen Datei - eine APK neu zu hashen kostet ~100 ms. */
    private record Digest(long modified, long size, String sha256) {
    }

    private final Path dir;
    private final ObjectMapper objectMapper;
    private volatile Digest digest;

    public AndroidRelease(@Value("${food.android.dir:}") String dir, ObjectMapper objectMapper) {
        this.dir = dir == null || dir.isBlank() ? null : Path.of(dir.trim());
        this.objectMapper = objectMapper;
    }

    /** Die neueste Version, oder leer, solange keine vollstaendig veroeffentlicht ist. */
    public Optional<AndroidReleaseInfo> latest() {
        if (dir == null) {
            return Optional.empty();
        }
        Path apk = dir.resolve(APK);
        Path meta = dir.resolve(META);
        if (!Files.isRegularFile(apk) || !Files.isRegularFile(meta)) {
            return Optional.empty();
        }
        try {
            JsonNode json = objectMapper.readTree(Files.readString(meta));
            int code = json.path("versionCode").asInt(0);
            String name = json.path("versionName").asString("");
            if (code <= 0 || name.isBlank()) {
                log.warn("{} ohne gueltige versionCode/versionName", meta);
                return Optional.empty();
            }
            long size = Files.size(apk);
            return Optional.of(new AndroidReleaseInfo(code, name, size, sha256(apk, size)));
        } catch (IOException | RuntimeException e) {
            log.warn("Android-Version nicht lesbar in {}", dir, e);
            return Optional.empty();
        }
    }

    public Path apk() {
        return dir == null ? null : dir.resolve(APK);
    }

    private String sha256(Path apk, long size) throws IOException {
        long modified = Files.getLastModifiedTime(apk).toMillis();
        Digest known = digest;
        if (known != null && known.modified() == modified && known.size() == size) {
            return known.sha256();
        }
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (InputStream in = Files.newInputStream(apk)) {
            byte[] buffer = new byte[64 * 1024];
            for (int read; (read = in.read(buffer)) > 0; ) {
                md.update(buffer, 0, read);
            }
        }
        String hex = HexFormat.of().formatHex(md.digest());
        digest = new Digest(modified, size, hex);
        return hex;
    }
}

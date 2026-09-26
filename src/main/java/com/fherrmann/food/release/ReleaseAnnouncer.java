package com.fherrmann.food.release;

import com.fherrmann.food.dto.AndroidReleaseInfo;
import com.fherrmann.food.push.PushNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Sagt den Android-Geraeten Bescheid, sobald eine neue Version veroeffentlicht ist.
 *
 * <p>Schaut alle paar Minuten selbst nach, statt vom Veroeffentlichen angestossen zu
 * werden: das Skript auf dem Mac muss so nichts ueber den Dienst wissen, und eine
 * Nachricht, die beim Hochladen verloren ginge, holt der naechste Blick nach.
 *
 * <p>Welche Version zuletzt angekuendigt wurde, steht in einer kleinen Datei. Fehlt
 * sie, wird die vorhandene Version nur vermerkt und nicht angekuendigt - die hat
 * jeder, der die App gerade erst von Hand installiert hat.
 */
@Component
public class ReleaseAnnouncer {

    private static final Logger log = LoggerFactory.getLogger(ReleaseAnnouncer.class);

    private final AndroidRelease release;
    private final PushNotifier notifier;
    private final Path announcedFile;
    private final ObjectMapper objectMapper;

    public ReleaseAnnouncer(
            AndroidRelease release,
            PushNotifier notifier,
            @Value("${food.android.announced-file:data/android-release.json}") String announcedFile,
            ObjectMapper objectMapper) {
        this.release = release;
        this.notifier = notifier;
        this.announcedFile = Path.of(announcedFile);
        this.objectMapper = objectMapper;
    }

    @Scheduled(initialDelayString = "${food.android.announce-initial-delay:PT1M}",
               fixedDelayString = "${food.android.announce-interval:PT5M}")
    public void check() {
        Optional<AndroidReleaseInfo> latest = release.latest();
        if (latest.isEmpty()) {
            return;
        }
        AndroidReleaseInfo info = latest.get();
        Integer announced = readAnnounced();
        if (announced != null && info.versionCode() <= announced) {
            return;
        }
        if (announced != null) {
            log.info("Kuendige Android-Version {} ({}) an", info.versionName(), info.versionCode());
            notifier.appReleased(info.versionCode(), info.versionName());
        }
        writeAnnounced(info.versionCode());
    }

    private Integer readAnnounced() {
        if (!Files.exists(announcedFile)) {
            return null;
        }
        try {
            return objectMapper.readTree(Files.readString(announcedFile)).path("versionCode").asInt(0);
        } catch (IOException | RuntimeException e) {
            log.warn("{} nicht lesbar - behandle es, als fehlte es", announcedFile, e);
            return null;
        }
    }

    private void writeAnnounced(int versionCode) {
        try {
            if (announcedFile.getParent() != null) {
                Files.createDirectories(announcedFile.getParent());
            }
            Files.writeString(announcedFile, objectMapper.writeValueAsString(Map.of("versionCode", versionCode)));
        } catch (IOException e) {
            log.warn("{} nicht schreibbar", announcedFile, e);
        }
    }
}

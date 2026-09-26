package com.fherrmann.food.release;

import com.fherrmann.food.dto.AndroidReleaseInfo;
import com.fherrmann.food.push.PushNotifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class AndroidReleaseTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    private void publish(int code, String name, byte[] apk) throws Exception {
        Files.write(tempDir.resolve("healthy.apk"), apk);
        Files.writeString(tempDir.resolve("latest.json"),
                "{\"versionCode\": " + code + ", \"versionName\": \"" + name + "\"}");
    }

    @Test
    void withoutADirectoryThereIsNoApp() {
        assertThat(new AndroidRelease("", mapper).latest()).isEmpty();
    }

    /** Halb veroeffentlicht (nur eine der beiden Dateien) zaehlt nicht. */
    @Test
    void aHalfPublishedReleaseDoesNotCount() throws Exception {
        Files.write(tempDir.resolve("healthy.apk"), new byte[]{1, 2, 3});
        assertThat(new AndroidRelease(tempDir.toString(), mapper).latest()).isEmpty();
    }

    @Test
    void theChecksumIsComputedFromTheFileThatIsServed() throws Exception {
        byte[] apk = "PK fake apk".getBytes();
        publish(3, "1.2", apk);
        Optional<AndroidReleaseInfo> info = new AndroidRelease(tempDir.toString(), mapper).latest();
        assertThat(info).isPresent();
        assertThat(info.get().versionCode()).isEqualTo(3);
        assertThat(info.get().versionName()).isEqualTo("1.2");
        assertThat(info.get().sizeBytes()).isEqualTo(apk.length);
        assertThat(info.get().sha256())
                .isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(apk)));
    }

    @Test
    void garbageMetadataMeansNoRelease() throws Exception {
        Files.write(tempDir.resolve("healthy.apk"), new byte[]{1});
        Files.writeString(tempDir.resolve("latest.json"), "{\"versionCode\": 0}");
        assertThat(new AndroidRelease(tempDir.toString(), mapper).latest()).isEmpty();
    }

    /**
     * Die erste gesehene Version wird nur vermerkt - wer die App gerade von Hand
     * installiert hat, hat genau diese. Erst eine neuere wird angekuendigt, und
     * jede nur einmal.
     */
    @Test
    void onlyNewerVersionsAreAnnouncedAndEachOnlyOnce() throws Exception {
        PushNotifier notifier = mock(PushNotifier.class);
        ReleaseAnnouncer announcer = new ReleaseAnnouncer(new AndroidRelease(tempDir.toString(), mapper),
                notifier, tempDir.resolve("data/android-release.json").toString(), mapper);

        announcer.check();
        verify(notifier, never()).appReleased(anyInt(), anyString());

        publish(1, "1.0", new byte[]{1});
        announcer.check();
        verify(notifier, never()).appReleased(anyInt(), anyString());

        publish(2, "1.1", new byte[]{2});
        announcer.check();
        announcer.check();
        verify(notifier, times(1)).appReleased(2, "1.1");
    }
}

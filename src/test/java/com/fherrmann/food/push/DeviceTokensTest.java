package com.fherrmann.food.push;

import com.fherrmann.food.push.DeviceTokens.Platform;
import com.fherrmann.food.security.HealthUsers;
import com.fherrmann.food.security.UserFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeviceTokensTest {

    @TempDir
    Path tempDir;

    private DeviceTokens tokens() {
        HealthUsers users = new HealthUsers("felix", "torben:0123456789abcdef0123456789abcdef");
        return new DeviceTokens(tempDir.resolve("devices.json").toString(),
                tempDir.resolve("devices-android.json").toString(),
                new ObjectMapper(), new UserFiles(users), users);
    }

    /** Die iOS-Datei der Eigentuemerin ist dieselbe wie immer - Form eingeschlossen. */
    @Test
    void theOwnersIosDevicesStayInTheExistingFile() throws Exception {
        Files.writeString(tempDir.resolve("devices.json"), "[ \"apns-alt\" ]");
        DeviceTokens tokens = tokens();
        assertThat(tokens.all("felix", Platform.IOS)).containsExactly("apns-alt");
        tokens.add("felix", Platform.IOS, "apns-neu");
        assertThat(tokens.all("felix", Platform.IOS)).containsExactly("apns-alt", "apns-neu");
        assertThat(tokens.all("felix", Platform.ANDROID)).isEmpty();
    }

    @Test
    void peopleAndPlatformsAreKeptApart() {
        DeviceTokens tokens = tokens();
        tokens.add("torben", Platform.ANDROID, "fcm-1");
        tokens.add("felix", Platform.IOS, "apns-1");
        assertThat(tokens.all("torben", Platform.ANDROID)).containsExactly("fcm-1");
        assertThat(tokens.all("torben", Platform.IOS)).isEmpty();
        assertThat(tokens.all("felix", Platform.ANDROID)).isEmpty();
        assertThat(tempDir.resolve("users/torben/devices-android.json")).exists();
    }

    /**
     * Ein Handy, das erst mit Felix' und dann mit Torbens Token eingerichtet wurde,
     * gehoert danach Torben - sonst bekaeme es weiter Felix' Benachrichtigungen.
     */
    @Test
    void aDeviceMovesToThePersonWhoRegisteredItLast() {
        DeviceTokens tokens = tokens();
        tokens.add("felix", Platform.ANDROID, "fcm-handy");
        tokens.add("torben", Platform.ANDROID, "fcm-handy");
        assertThat(tokens.all("felix", Platform.ANDROID)).isEmpty();
        assertThat(tokens.all("torben", Platform.ANDROID)).containsExactly("fcm-handy");
    }

    @Test
    void removedTokensAreGone() {
        DeviceTokens tokens = tokens();
        tokens.add("torben", Platform.ANDROID, "fcm-1");
        tokens.remove("torben", Platform.ANDROID, "fcm-1");
        assertThat(tokens.all("torben", Platform.ANDROID)).isEmpty();
    }

    @Test
    void platformNamesAreParsedLeniently() {
        assertThat(Platform.parse(null)).isEqualTo(Platform.IOS);
        assertThat(Platform.parse("")).isEqualTo(Platform.IOS);
        assertThat(Platform.parse("android")).isEqualTo(Platform.ANDROID);
        assertThat(Platform.parse(" Android ")).isEqualTo(Platform.ANDROID);
        assertThatThrownBy(() -> Platform.parse("symbian")).isInstanceOf(IllegalArgumentException.class);
    }
}

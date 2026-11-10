package com.fherrmann.food.push;

import com.fherrmann.food.push.DeviceTokens.Environment;
import com.fherrmann.food.push.DeviceTokens.IosDevice;
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
                tempDir.resolve("devices-production.json").toString(),
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

    // --- APNs-Umgebungen (seit 2026-11-10) ---------------------------------------

    /**
     * Was schon in devices.json steht, ist eine Sandbox-Kennung - ohne dass jemand die
     * Datei anfasst. Produktions-Kennungen liegen daneben, in derselben schlichten Form.
     */
    @Test
    void existingRegistrationsAreSandboxAndProductionLivesInASecondFile() throws Exception {
        Files.writeString(tempDir.resolve("devices.json"), "[ \"apns-alt\" ]");
        DeviceTokens tokens = tokens();

        tokens.add("felix", Platform.IOS, Environment.PRODUCTION, "apns-testflight");

        assertThat(tokens.ios("felix")).containsExactly(
                new IosDevice("apns-alt", Environment.SANDBOX),
                new IosDevice("apns-testflight", Environment.PRODUCTION));
        // Die bisherige Datei bleibt, wie sie war - ein aelteres Jar liest sie weiter.
        assertThat(new ObjectMapper().readValue(tempDir.resolve("devices.json").toFile(), String[].class))
                .containsExactly("apns-alt");
        assertThat(new ObjectMapper().readValue(tempDir.resolve("devices-production.json").toFile(), String[].class))
                .containsExactly("apns-testflight");
        assertThat(tokens.all("felix", Platform.IOS)).containsExactly("apns-alt");
    }

    /** Ohne Umgebung angemeldet heisst Sandbox - so meldet sich jede aeltere App. */
    @Test
    void registeringWithoutAnEnvironmentMeansSandbox() {
        DeviceTokens tokens = tokens();
        tokens.add("felix", Platform.IOS, "apns-1");
        assertThat(tokens.ios("felix")).containsExactly(new IosDevice("apns-1", Environment.SANDBOX));
    }

    /** Dieselbe Kennung steht nie in beiden Umgebungen: die letzte Anmeldung gilt. */
    @Test
    void aTokenLivesInExactlyOneEnvironment() {
        DeviceTokens tokens = tokens();
        tokens.add("felix", Platform.IOS, Environment.SANDBOX, "apns-1");
        tokens.add("felix", Platform.IOS, Environment.PRODUCTION, "apns-1");
        assertThat(tokens.ios("felix")).containsExactly(new IosDevice("apns-1", Environment.PRODUCTION));

        tokens.move("felix", "apns-1", Environment.SANDBOX);
        assertThat(tokens.ios("felix")).containsExactly(new IosDevice("apns-1", Environment.SANDBOX));
    }

    /** Ein iPhone, das die Person wechselt, verschwindet bei der alten in beiden Umgebungen. */
    @Test
    void anIosDeviceMovesBetweenPeopleAcrossEnvironments() {
        DeviceTokens tokens = tokens();
        tokens.add("felix", Platform.IOS, Environment.PRODUCTION, "apns-handy");
        tokens.add("torben", Platform.IOS, Environment.SANDBOX, "apns-handy");
        assertThat(tokens.ios("felix")).isEmpty();
        assertThat(tokens.ios("torben")).containsExactly(new IosDevice("apns-handy", Environment.SANDBOX));
        assertThat(tempDir.resolve("users/torben/devices.json")).exists();
    }

    @Test
    void removingAnIosTokenClearsBothEnvironments() {
        DeviceTokens tokens = tokens();
        tokens.add("felix", Platform.IOS, Environment.SANDBOX, "apns-1");
        tokens.add("felix", Platform.IOS, Environment.PRODUCTION, "apns-2");
        tokens.remove("felix", Platform.IOS, "apns-2");
        tokens.remove("felix", Platform.IOS, "apns-1");
        assertThat(tokens.ios("felix")).isEmpty();
    }

    /** Android kennt keine Umgebung - die Angabe aendert nichts an der Datei. */
    @Test
    void androidIgnoresTheEnvironment() {
        DeviceTokens tokens = tokens();
        tokens.add("torben", Platform.ANDROID, Environment.PRODUCTION, "fcm-1");
        assertThat(tokens.all("torben", Platform.ANDROID)).containsExactly("fcm-1");
        assertThat(tokens.ios("torben")).isEmpty();
        assertThat(tempDir.resolve("users/torben/devices-production.json")).doesNotExist();
    }

    @Test
    void environmentNamesAreParsedLeniently() {
        assertThat(Environment.parse(null)).isEqualTo(Environment.SANDBOX);
        assertThat(Environment.parse(" ")).isEqualTo(Environment.SANDBOX);
        assertThat(Environment.parse("sandbox")).isEqualTo(Environment.SANDBOX);
        assertThat(Environment.parse(" Production ")).isEqualTo(Environment.PRODUCTION);
        assertThatThrownBy(() -> Environment.parse("staging")).isInstanceOf(IllegalArgumentException.class);
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

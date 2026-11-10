package com.fherrmann.food.push;

import com.fherrmann.food.security.HealthUsers;
import com.fherrmann.food.security.UserFiles;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Die Geraetekennungen, an die Benachrichtigungen gehen - je Person und je Plattform.
 *
 * <p>Eine eigene kleine Datei neben {@code food.json} und nicht darin: das
 * Tagebuch ist der Datenbestand, den man aufhebt und sichert - Geraetekennungen
 * sind Fluechtiges, das Apple oder Google jederzeit fuer ungueltig erklaeren
 * koennen. Sie in denselben Topf zu werfen hiesse, bei jedem App-Neustart die
 * Datei umzuschreiben, in der die Ernaehrungsdaten stehen.
 *
 * <p>iOS-Kennungen (APNs) und Android-Kennungen (Firebase) liegen getrennt, weil
 * sie ueber verschiedene Dienste zugestellt werden. Die iOS-Datei der Eigentuemerin
 * ist dieselbe {@code data/devices.json} wie immer.
 *
 * <p>iOS-Kennungen liegen ausserdem je <b>APNs-Umgebung</b> getrennt (seit 2026-11-10):
 * eine Entwicklungs-Installation bekommt Kennungen, die nur die Sandbox kennt, ein
 * TestFlight-Build welche fuer die Produktion. Die Sandbox bleibt in der bisherigen
 * Datei, die Produktion bekommt eine zweite daneben ({@code devices-production.json}) -
 * so ist jede alte Anmeldung von selbst eine Sandbox-Kennung, die Dateiform bleibt die
 * schlichte Liste, und ein aelteres Jar liest {@code devices.json} weiter wie immer.
 *
 * <p>Ein Geraet meldet sich bei jedem Start neu an; doppelte Eintraege
 * verhindert das Set. Ungueltige Kennungen fliegen raus, sobald der Dienst sie
 * ablehnt - erst dann weiss man es sicher.
 */
@Repository
public class DeviceTokens {

    public enum Platform {
        IOS, ANDROID;

        /**
         * Aus der Anmeldung der App. Ohne Angabe iOS - so meldet sich die
         * iPhone-App seit jeher.
         *
         * @throws IllegalArgumentException bei einer unbekannten Plattform
         */
        public static Platform parse(String value) {
            if (value == null || value.isBlank()) {
                return IOS;
            }
            return Platform.valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }

    /** An welchen APNs-Rechner eine iOS-Kennung gehoert - das entscheidet die Signatur der App. */
    public enum Environment {
        SANDBOX, PRODUCTION;

        /**
         * Aus der Anmeldung der App. Ohne Angabe die Sandbox - so meldet sich jede
         * iPhone-App, die das Feld noch nicht schickt, und so war es immer.
         *
         * @throws IllegalArgumentException bei einer unbekannten Umgebung
         */
        public static Environment parse(String value) {
            if (value == null || value.isBlank()) {
                return SANDBOX;
            }
            return Environment.valueOf(value.trim().toUpperCase(Locale.ROOT));
        }

        public Environment other() {
            return this == SANDBOX ? PRODUCTION : SANDBOX;
        }
    }

    /** Eine iOS-Kennung mit der Umgebung, unter der sie angemeldet ist. */
    public record IosDevice(String token, Environment environment) {
    }

    private final Path iosFile;
    private final Path iosProductionFile;
    private final Path androidFile;
    private final ObjectMapper objectMapper;
    private final UserFiles userFiles;
    private final HealthUsers users;

    public DeviceTokens(
            @Value("${food.push.devices-file:data/devices.json}") String iosFile,
            @Value("${food.push.devices-production-file:data/devices-production.json}") String iosProductionFile,
            @Value("${food.push.android-devices-file:data/devices-android.json}") String androidFile,
            ObjectMapper objectMapper,
            UserFiles userFiles,
            HealthUsers users) {
        this.iosFile = Path.of(iosFile);
        this.iosProductionFile = Path.of(iosProductionFile);
        this.androidFile = Path.of(androidFile);
        this.objectMapper = objectMapper;
        this.userFiles = userFiles;
        this.users = users;
    }

    /**
     * Die Kennungen der Person auf dieser Plattform. Bei iOS die der Sandbox - also
     * genau die bisherige Datei; mit Umgebung: {@link #ios}.
     */
    public synchronized List<String> all(String user, Platform platform) {
        return List.copyOf(load(file(user, platform, Environment.SANDBOX)));
    }

    /** Alle iOS-Kennungen der Person, jede mit ihrer Umgebung - Sandbox zuerst. */
    public synchronized List<IosDevice> ios(String user) {
        List<IosDevice> all = new ArrayList<>();
        for (Environment environment : Environment.values()) {
            for (String token : load(file(user, Platform.IOS, environment))) {
                all.add(new IosDevice(token, environment));
            }
        }
        return List.copyOf(all);
    }

    /** Wie {@link #add(String, Platform, Environment, String)} fuer die Sandbox. */
    public synchronized void add(String user, Platform platform, String token) {
        add(user, platform, Environment.SANDBOX, token);
    }

    /**
     * Meldet eine Kennung fuer diese Person an - und nimmt sie allen anderen weg.
     * Ein Handy, das erst mit einem Token und dann mit einem anderen eingerichtet
     * wurde, bekaeme sonst weiter die Benachrichtigungen der ersten Person.
     *
     * <p>Eine iOS-Kennung steht danach in genau einer Umgebung: meldet dieselbe App
     * sie erst als Sandbox und spaeter als Produktion (oder umgekehrt), gilt die
     * letzte Angabe. Bei Android ist die Umgebung ohne Bedeutung.
     */
    public synchronized void add(String user, Platform platform, Environment environment, String token) {
        for (String other : users.names()) {
            if (!other.equals(user)) {
                removeEverywhere(other, platform, token);
            }
        }
        if (platform == Platform.IOS) {
            removeFrom(file(user, platform, environment.other()), token);
        }
        Path file = file(user, platform, environment);
        Set<String> tokens = load(file);
        if (tokens.add(token)) {
            save(file, tokens);
        }
    }

    /** Vergisst die Kennung - bei iOS in beiden Umgebungen. */
    public synchronized void remove(String user, Platform platform, String token) {
        removeEverywhere(user, platform, token);
    }

    /**
     * Traegt eine iOS-Kennung in die andere Umgebung um: APNs hat sie dort angenommen
     * und unter der angemeldeten abgelehnt.
     */
    public synchronized void move(String user, String token, Environment to) {
        add(user, Platform.IOS, to, token);
    }

    private void removeEverywhere(String user, Platform platform, String token) {
        if (platform == Platform.ANDROID) {
            removeFrom(file(user, platform, Environment.SANDBOX), token);
            return;
        }
        for (Environment environment : Environment.values()) {
            removeFrom(file(user, platform, environment), token);
        }
    }

    private Path file(String user, Platform platform, Environment environment) {
        Path ownerFile = platform == Platform.ANDROID ? androidFile
                : environment == Environment.PRODUCTION ? iosProductionFile : iosFile;
        return userFiles.resolve(user, ownerFile);
    }

    private void removeFrom(Path file, String token) {
        Set<String> tokens = load(file);
        if (tokens.remove(token)) {
            save(file, tokens);
        }
    }

    private Set<String> load(Path file) {
        if (!Files.exists(file)) {
            return new LinkedHashSet<>();
        }
        try {
            String[] tokens = objectMapper.readValue(Files.readAllBytes(file), String[].class);
            return new LinkedHashSet<>(List.of(tokens));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read device tokens: " + file, e);
        }
    }

    private void save(Path file, Set<String> tokens) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), tokens);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write device tokens: " + file, e);
        }
    }
}

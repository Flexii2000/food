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

    private final Path iosFile;
    private final Path androidFile;
    private final ObjectMapper objectMapper;
    private final UserFiles userFiles;
    private final HealthUsers users;

    public DeviceTokens(
            @Value("${food.push.devices-file:data/devices.json}") String iosFile,
            @Value("${food.push.android-devices-file:data/devices-android.json}") String androidFile,
            ObjectMapper objectMapper,
            UserFiles userFiles,
            HealthUsers users) {
        this.iosFile = Path.of(iosFile);
        this.androidFile = Path.of(androidFile);
        this.objectMapper = objectMapper;
        this.userFiles = userFiles;
        this.users = users;
    }

    public synchronized List<String> all(String user, Platform platform) {
        return List.copyOf(load(file(user, platform)));
    }

    /**
     * Meldet eine Kennung fuer diese Person an - und nimmt sie allen anderen weg.
     * Ein Handy, das erst mit einem Token und dann mit einem anderen eingerichtet
     * wurde, bekaeme sonst weiter die Benachrichtigungen der ersten Person.
     */
    public synchronized void add(String user, Platform platform, String token) {
        for (String other : users.names()) {
            if (!other.equals(user)) {
                removeFrom(file(other, platform), token);
            }
        }
        Path file = file(user, platform);
        Set<String> tokens = load(file);
        if (tokens.add(token)) {
            save(file, tokens);
        }
    }

    public synchronized void remove(String user, Platform platform, String token) {
        removeFrom(file(user, platform), token);
    }

    private Path file(String user, Platform platform) {
        return userFiles.resolve(user, platform == Platform.ANDROID ? androidFile : iosFile);
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

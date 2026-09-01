package com.fherrmann.food.push;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Die Geraetekennungen, an die Benachrichtigungen gehen.
 *
 * <p>Eine eigene kleine Datei neben {@code food.json} und nicht darin: das
 * Tagebuch ist der Datenbestand, den man aufhebt und sichert - Geraetekennungen
 * sind Fluechtiges, das Apple jederzeit fuer ungueltig erklaeren kann. Sie in
 * denselben Topf zu werfen hiesse, bei jedem App-Neustart die Datei
 * umzuschreiben, in der die Ernaehrungsdaten stehen.
 *
 * <p>Ein Geraet meldet sich bei jedem Start neu an; doppelte Eintraege
 * verhindert das Set. Ungueltige Kennungen fliegen raus, sobald Apple sie
 * ablehnt - erst dann weiss man es sicher.
 */
@Repository
public class DeviceTokens {

    private final Path file;
    private final ObjectMapper objectMapper;

    public DeviceTokens(
            @Value("${food.push.devices-file:data/devices.json}") String file,
            ObjectMapper objectMapper) {
        this.file = Path.of(file);
        this.objectMapper = objectMapper;
    }

    public synchronized List<String> all() {
        return List.copyOf(load());
    }

    public synchronized void add(String token) {
        Set<String> tokens = load();
        if (tokens.add(token)) {
            save(tokens);
        }
    }

    public synchronized void remove(String token) {
        Set<String> tokens = load();
        if (tokens.remove(token)) {
            save(tokens);
        }
    }

    private Set<String> load() {
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

    private void save(Set<String> tokens) {
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

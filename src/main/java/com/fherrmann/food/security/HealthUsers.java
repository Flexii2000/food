package com.fherrmann.food.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Wer den Kalorienzaehler benutzt: die Person, der die bisherigen Daten gehoeren,
 * und jede weitere mit eigenem Healthy-Token.
 *
 * <p>Die Token gelten fuer Kalorienzaehler <b>und</b> Weight Tracker - eine Person,
 * ein Token, ein Setup-Link. Beide Dienste lesen dieselbe Liste aus der Umgebung
 * ({@code HEALTH_TOKENS=torben:…,…}), jeder aus seiner eigenen env-Datei. Dieselbe
 * Klasse steht im Weight Tracker; wer hier etwas aendert, zieht es dort nach.
 *
 * <p>Der Privat-Cookie von fherrmann.com ({@code fh_private}) bleibt gueltig und
 * meint die Eigentuemerin ({@code health.owner}). Damit laufen iPhone-App, Habits,
 * Statusboard und jeder eingerichtete Browser ohne jede Aenderung weiter; ohne
 * {@code HEALTH_TOKENS} verhaelt sich der Dienst genau wie vorher.
 *
 * <p>Die Namen landen in Dateipfaden ({@code data/users/<name>/}) - deshalb die
 * enge Form, und deshalb scheitert der Start an einem ungueltigen Namen, statt
 * ihn irgendwie zurechtzubiegen.
 */
@Component
public class HealthUsers {

    private static final Pattern VALID_NAME = Pattern.compile("[a-z][a-z0-9_-]{0,31}");

    /** 64 Bit sind das Mindeste; setup-health-users.sh erzeugt 192. */
    private static final int MIN_TOKEN_LENGTH = 16;

    private record Entry(String name, byte[] token) {
    }

    private final String owner;
    private final List<Entry> entries = new ArrayList<>();

    public HealthUsers(
            @Value("${health.owner:felix}") String owner,
            @Value("${health.tokens:}") String configured) {
        this.owner = owner == null ? "" : owner.trim();
        if (!isValidName(this.owner)) {
            throw new IllegalStateException("health.owner: ungueltiger Name '" + owner + "'");
        }
        if (configured == null) {
            return;
        }
        for (String part : configured.split(",")) {
            String piece = part.trim();
            if (piece.isEmpty()) {
                continue;
            }
            int colon = piece.indexOf(':');
            if (colon <= 0 || colon == piece.length() - 1) {
                throw new IllegalStateException("health.tokens: erwartet name:token, bekommen: " + mask(piece));
            }
            String name = piece.substring(0, colon).trim();
            String token = piece.substring(colon + 1).trim();
            if (!isValidName(name)) {
                throw new IllegalStateException("health.tokens: ungueltiger Name '" + name + "'");
            }
            if (token.length() < MIN_TOKEN_LENGTH) {
                throw new IllegalStateException("health.tokens: der Token von " + name + " ist zu kurz.");
            }
            if (entries.stream().anyMatch(e -> e.name().equals(name))) {
                throw new IllegalStateException("health.tokens: " + name + " steht doppelt drin.");
            }
            if (nameFor(token).isPresent()) {
                throw new IllegalStateException("health.tokens: der Token von " + name + " ist schon vergeben.");
            }
            entries.add(new Entry(name, token.getBytes(StandardCharsets.UTF_8)));
        }
    }

    public String owner() {
        return owner;
    }

    public boolean isOwner(String name) {
        return owner.equals(name);
    }

    /** Der Name zum Token - in konstanter Zeit ueber alle Eintraege, ohne fruehes Ende. */
    public Optional<String> nameFor(String supplied) {
        if (supplied == null || supplied.isEmpty()) {
            return Optional.empty();
        }
        byte[] candidate = supplied.getBytes(StandardCharsets.UTF_8);
        String found = null;
        for (Entry entry : entries) {
            if (MessageDigest.isEqual(candidate, entry.token()) && found == null) {
                found = entry.name();
            }
        }
        return Optional.ofNullable(found);
    }

    /** Alle bekannten Personen, die Eigentuemerin zuerst. */
    public List<String> names() {
        Set<String> names = new LinkedHashSet<>();
        names.add(owner);
        entries.forEach(e -> names.add(e.name()));
        return List.copyOf(names);
    }

    public static boolean isValidName(String name) {
        return name != null && VALID_NAME.matcher(name).matches();
    }

    /** Ein Token gehoert nicht in eine Fehlermeldung, die im Journal landet. */
    private static String mask(String piece) {
        int colon = piece.indexOf(':');
        return colon < 0 ? "(ohne Doppelpunkt)" : piece.substring(0, colon) + ":…";
    }
}

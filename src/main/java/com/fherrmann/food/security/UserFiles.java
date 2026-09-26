package com.fherrmann.food.security;

import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Wo die Dateien einer Person liegen.
 *
 * <p>Die Eigentuemerin behaelt die bisherigen Pfade ({@code data/food.json} usw.),
 * alle anderen bekommen einen eigenen Ordner daneben
 * ({@code data/users/<name>/food.json}). Bewusst kein Umzug der vorhandenen
 * Dateien: ein Rollback auf das alte Jar findet sie so weiter dort, wo es sie
 * erwartet, und am Live-Bestand aendert sich beim Deploy nichts.
 */
@Component
public class UserFiles {

    private final HealthUsers users;

    public UserFiles(HealthUsers users) {
        this.users = users;
    }

    public boolean isOwner(String user) {
        return users.isOwner(user);
    }

    /**
     * Die Datei dieser Person zu einer konfigurierten Datei der Eigentuemerin.
     *
     * @throws IllegalArgumentException bei einem Namen, der kein gueltiger ist -
     *         der Filter laesst so einen nie durch, das hier ist die zweite Schranke
     *         davor, dass ein Name zum Pfad ausserhalb von {@code data/} wird
     */
    public Path resolve(String user, Path ownerFile) {
        if (users.isOwner(user)) {
            return ownerFile;
        }
        if (!HealthUsers.isValidName(user)) {
            throw new IllegalArgumentException("Ungueltiger Name: " + user);
        }
        Path parent = ownerFile.getParent();
        Path usersDir = parent == null ? Path.of("users") : parent.resolve("users");
        return usersDir.resolve(user).resolve(ownerFile.getFileName());
    }
}

package com.fherrmann.food.featurerequest;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.Optional;

/**
 * Fuer welche App ein Wunsch ist. Die Kennung steht in der Datei und geht ueber die
 * Leitung; der Name ist zugleich die Elternaufgabe im To-Do (Bereich „Server").
 *
 * <p>Was die App kann, steht nicht hier, sondern in der {@code CLAUDE.md} des
 * Story-Agents - dort braucht es Claude, hier niemand.
 */
public enum FeatureApp {

    HEALTHY("healthy", "Healthy"),
    COHABIT("cohabit", "coHabit"),
    FOKUS("fokus", "Fokus"),
    EINKAUFSLISTE("einkaufsliste", "Einkaufsliste");

    /** Was gilt, wenn nichts angegeben ist - und fuer alle Anfragen von vor der Auswahl. */
    public static final FeatureApp DEFAULT = HEALTHY;

    private final String id;
    private final String displayName;

    FeatureApp(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public static Optional<FeatureApp> byId(String id) {
        return id == null ? Optional.empty()
                : Arrays.stream(values()).filter(a -> a.id.equalsIgnoreCase(id.strip())).findFirst();
    }

    /** Fuer Eingaben von aussen: leer heisst {@link #DEFAULT}, unbekannt ist ein 400. */
    public static FeatureApp parse(String id) {
        if (id == null || id.isBlank()) {
            return DEFAULT;
        }
        return byId(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Diese App gibt es hier nicht: " + id.strip()));
    }

    /** Fuer gespeicherte Anfragen: was nicht (mehr) bekannt ist, faellt auf {@link #DEFAULT}. */
    public static FeatureApp stored(String id) {
        return byId(id).orElse(DEFAULT);
    }
}

package com.fherrmann.food.featurerequest;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Liefert die Seite aus - fuer die Liste ({@code /feature-requests/}) und fuer jede
 * Kartenseite ({@code /feature-requests/<id>}, der Link aus dem To-Do). Welche
 * Ansicht gemeint ist, liest die Seite selbst aus der Adresse.
 *
 * <p>Das Muster der Kartenseite schliesst Punkte aus: {@code app.js} und
 * {@code styles.css} liegen im selben Pfad und sollen als Dateien kommen, nicht als
 * noch eine Kopie der Seite.
 */
@Controller
public class FeatureRequestPage {

    private static final Resource INDEX = new ClassPathResource("static/feature-requests/index.html");
    private static final MediaType HTML = MediaType.parseMediaType("text/html;charset=UTF-8");

    /** Ohne Schraegstrich zeigten die relativen Pfade der Seite eine Ebene zu hoch. */
    @GetMapping("/feature-requests")
    public ResponseEntity<Void> withoutSlash() {
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, "/feature-requests/")
                .build();
    }

    @GetMapping({"/feature-requests/", "/feature-requests/{id:[^.]+}"})
    public ResponseEntity<Resource> page() {
        return ResponseEntity.ok()
                .contentType(HTML)
                // Immer frisch nachfragen: nach einem Deploy soll die neue Seite
                // kommen, nicht die aus dem Cache neben einem neuen app.js.
                .cacheControl(CacheControl.noCache())
                .body(INDEX);
    }
}

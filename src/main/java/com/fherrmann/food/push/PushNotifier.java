package com.fherrmann.food.push;

import com.fherrmann.food.dto.QuickCapturePreview;
import org.springframework.stereotype.Component;

/**
 * Meldet einem wartenden Geraet, dass seine Schnellerfassung fertig ist.
 *
 * <p>Der Grund fuer das Ganze: die Auswertung dauert bis zu einer Minute. Ohne
 * Benachrichtigung muss die App offen bleiben und nachfragen - legt man das
 * Handy weg, friert iOS sie nach etwa dreissig Sekunden ein und das Ergebnis
 * kommt erst beim naechsten Oeffnen.
 *
 * <p>Ohne eingerichteten Schluessel passiert hier schlicht nichts. Die
 * Schnellerfassung funktioniert davon unabhaengig weiter; die App fragt ja
 * ohnehin nach, solange sie laeuft.
 */
@Component
public class PushNotifier {

    private final ApnsClient apns;
    private final DeviceTokens devices;

    public PushNotifier(ApnsClient apns, DeviceTokens devices) {
        this.apns = apns;
        this.devices = devices;
    }

    public void quickCaptureFinished(String status, QuickCapturePreview preview, String error) {
        if (!apns.isConfigured()) {
            return;
        }
        if (preview != null) {
            send("Vorschlag ist fertig", preview.name() + " – antippen zum Übernehmen.");
        } else {
            send("Auswertung fehlgeschlagen",
                 error == null ? "Bitte noch einmal versuchen." : error);
        }
    }

    private void send(String title, String body) {
        for (String token : devices.all()) {
            // Lehnt Apple eine Kennung ab, ist sie tot - aufheben hiesse, es
            // bei jeder Benachrichtigung erneut zu versuchen.
            if (!apns.send(token, title, body)) {
                devices.remove(token);
            }
        }
    }
}

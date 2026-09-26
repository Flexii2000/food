package com.fherrmann.food.push;

import com.fherrmann.food.dto.QuickCapturePreview;
import com.fherrmann.food.push.DeviceTokens.Platform;
import com.fherrmann.food.security.HealthUsers;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Meldet einem wartenden Geraet, dass seine Schnellerfassung fertig ist - und
 * Android-Geraeten, dass es eine neue App-Version gibt.
 *
 * <p>Der Grund fuer das Erste: die Auswertung dauert bis zu einer Minute. Ohne
 * Benachrichtigung muss die App offen bleiben und nachfragen - legt man das
 * Handy weg, friert das System sie ein und das Ergebnis kommt erst beim
 * naechsten Oeffnen. Der Grund fuer das Zweite: die Android-App kommt nicht aus
 * dem Play Store, niemand sonst sagt Bescheid.
 *
 * <p>iPhones bekommen ihre Nachricht ueber APNs, Android-Handys ueber Firebase.
 * Ohne eingerichteten Schluessel faellt der jeweilige Weg einfach weg; die
 * Schnellerfassung funktioniert davon unabhaengig weiter.
 */
@Component
public class PushNotifier {

    private final ApnsClient apns;
    private final FcmClient fcm;
    private final DeviceTokens devices;
    private final HealthUsers users;

    public PushNotifier(ApnsClient apns, FcmClient fcm, DeviceTokens devices, HealthUsers users) {
        this.apns = apns;
        this.fcm = fcm;
        this.devices = devices;
        this.users = users;
    }

    /** An die Geraete der Person, die den Auftrag gestartet hat - an niemanden sonst. */
    public void quickCaptureFinished(String user, String jobId, String status,
                                     QuickCapturePreview preview, String error) {
        String title;
        String body;
        if (preview != null) {
            title = "Vorschlag ist fertig";
            body = preview.name() + " – antippen zum Übernehmen.";
        } else {
            title = "Auswertung fehlgeschlagen";
            body = error == null ? "Bitte noch einmal versuchen." : error;
        }
        if (apns.isConfigured()) {
            for (String token : devices.all(user, Platform.IOS)) {
                // Lehnt Apple eine Kennung ab, ist sie tot - aufheben hiesse, es
                // bei jeder Benachrichtigung erneut zu versuchen.
                if (!apns.send(token, title, body)) {
                    devices.remove(user, Platform.IOS, token);
                }
            }
        }
        if (fcm.isConfigured()) {
            Map<String, String> data = new LinkedHashMap<>();
            data.put("kind", "quick-capture");
            data.put("jobId", jobId);
            data.put("status", status);
            data.put("title", title);
            data.put("body", body);
            sendToAndroid(user, data);
        }
    }

    /** An jedes angemeldete Android-Geraet, egal wessen. */
    public void appReleased(int versionCode, String versionName) {
        if (!fcm.isConfigured()) {
            return;
        }
        Map<String, String> data = new LinkedHashMap<>();
        data.put("kind", "app-update");
        data.put("versionCode", String.valueOf(versionCode));
        data.put("versionName", versionName);
        data.put("title", "Neue Version " + versionName);
        data.put("body", "Antippen zum Installieren.");
        for (String user : users.names()) {
            sendToAndroid(user, data);
        }
    }

    private void sendToAndroid(String user, Map<String, String> data) {
        for (String token : devices.all(user, Platform.ANDROID)) {
            if (!fcm.send(token, data)) {
                devices.remove(user, Platform.ANDROID, token);
            }
        }
    }
}

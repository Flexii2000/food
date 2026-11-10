package com.fherrmann.food.push;

import com.fherrmann.food.dto.QuickCapturePreview;
import com.fherrmann.food.push.DeviceTokens.Environment;
import com.fherrmann.food.push.DeviceTokens.IosDevice;
import com.fherrmann.food.push.DeviceTokens.Platform;
import com.fherrmann.food.security.HealthUsers;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Schickt Mitteilungen an die Geraete einer Person: dass ihre Schnellerfassung fertig
 * ist, was der Weight Tracker ueber den internen Endpunkt meldet (Chat-Antwort,
 * Stupser, Serien-Warnung - Healthy-Vertrag §11.5), und Android-Geraeten, dass es eine
 * neue App-Version gibt.
 *
 * <p>Der Grund fuer das Erste: die Auswertung dauert bis zu einer Minute. Ohne
 * Benachrichtigung muss die App offen bleiben und nachfragen - legt man das
 * Handy weg, friert das System sie ein und das Ergebnis kommt erst beim
 * naechsten Oeffnen. Der Grund fuer das Letzte: die Android-App kommt nicht aus
 * dem Play Store, niemand sonst sagt Bescheid.
 *
 * <p>iPhones bekommen ihre Nachricht ueber APNs, Android-Handys ueber Firebase.
 * Ohne eingerichteten Schluessel faellt der jeweilige Weg einfach weg; die
 * Schnellerfassung funktioniert davon unabhaengig weiter.
 */
@Component
public class PushNotifier {

    /** Die Schluessel, die jede Nachricht selbst setzt - {@code data} ueberschreibt sie nicht. */
    private static final Set<String> RESERVED = Set.of("kind", "title", "body", "aps");

    /**
     * Wie viele Geraete die Nachricht angenommen bekommen haben, je Plattform.
     * "Angenommen" heisst: von Apple bzw. Google - nicht, dass sie schon angezeigt wurde.
     */
    public record Delivery(int ios, int android) {
    }

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
        Map<String, String> data = new LinkedHashMap<>();
        data.put("jobId", jobId);
        data.put("status", status);
        send(user, "quick-capture", title, body, data);
    }

    /**
     * Eine Mitteilung dieser Art an alle Geraete der Person.
     *
     * <p>iPhones bekommen Titel und Text als sichtbare Mitteilung, {@code kind} und
     * {@code data} stehen daneben in der Nutzlast. Android bekommt alles als
     * Datennachricht ({@code kind}, {@code data}, {@code title}, {@code body}) und baut
     * die Mitteilung selbst - eine Art, die die installierte App nicht kennt, zeigt sie
     * nicht an.
     *
     * @param data weitere Schluessel, oder {@code null}; {@code kind}, {@code title},
     *             {@code body} und {@code aps} darin bleiben unbeachtet
     */
    public Delivery send(String user, String kind, String title, String body, Map<String, String> data) {
        Map<String, String> extra = new LinkedHashMap<>();
        if (data != null) {
            data.forEach((key, value) -> {
                if (key != null && value != null && !RESERVED.contains(key)) {
                    extra.put(key, value);
                }
            });
        }
        int ios = 0;
        if (apns.isConfigured()) {
            for (IosDevice device : devices.ios(user)) {
                if (deliverToIos(user, device, title, body, kind, extra)) {
                    ios++;
                }
            }
        }
        int android = 0;
        if (fcm.isConfigured()) {
            Map<String, String> message = new LinkedHashMap<>();
            message.put("kind", kind);
            message.putAll(extra);
            message.put("title", title);
            message.put("body", body);
            android = sendToAndroid(user, message);
        }
        return new Delivery(ios, android);
    }

    /**
     * Stellt einem iPhone zu - und raeumt auf, wenn Apple die Kennung nicht will.
     *
     * <p>{@code BadDeviceToken} heisst meist: falsche Umgebung. Eine App, die ihre
     * Umgebung falsch oder (aeltere Fassung) gar nicht meldet, verloere sonst bei der
     * ersten Mitteilung still ihre Kennung. Deshalb genau ein Versuch am anderen
     * Rechner: nimmt der sie an, wandert die Kennung dorthin; lehnt auch er sie ab, ist
     * sie tot. Bleibt der zweite Versuch ohne Auskunft (Netz), bleibt die Kennung, wo
     * sie ist - die naechste Mitteilung klaert es.
     */
    private boolean deliverToIos(String user, IosDevice device, String title, String body,
                                 String kind, Map<String, String> data) {
        String token = device.token();
        switch (apns.send(device.environment(), token, title, body, kind, data)) {
            case DELIVERED:
                return true;
            case GONE:
                // Lehnt Apple eine Kennung ab, ist sie tot - aufheben hiesse, es
                // bei jeder Benachrichtigung erneut zu versuchen.
                devices.remove(user, Platform.IOS, token);
                return false;
            case BAD_TOKEN:
                Environment other = device.environment().other();
                ApnsClient.Result second = apns.send(other, token, title, body, kind, data);
                if (second == ApnsClient.Result.DELIVERED) {
                    devices.move(user, token, other);
                    return true;
                }
                if (second != ApnsClient.Result.KEPT) {
                    devices.remove(user, Platform.IOS, token);
                }
                return false;
            default:
                return false;
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

    /** @return wie viele Geraete die Nachricht angenommen bekommen haben */
    private int sendToAndroid(String user, Map<String, String> data) {
        int delivered = 0;
        for (String token : devices.all(user, Platform.ANDROID)) {
            switch (fcm.deliver(token, data)) {
                case DELIVERED -> delivered++;
                case GONE -> devices.remove(user, Platform.ANDROID, token);
                default -> {
                }
            }
        }
        return delivered;
    }
}

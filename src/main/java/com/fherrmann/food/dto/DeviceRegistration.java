package com.fherrmann.food.dto;

/**
 * Die Push-Kennung eines Geraets, wie die App sie meldet.
 *
 * @param token    bei iOS hexadezimal (APNs), bei Android die Registrierungskennung
 *                 von Firebase. Sie aendert sich gelegentlich von selbst - die Apps
 *                 melden sie deshalb bei jedem Start neu
 * @param platform {@code "android"} fuer Firebase; fehlt das Feld (wie bei der
 *                 iOS-App), ist es eine APNs-Kennung
 * @param environment nur iOS: {@code "sandbox"} (Entwicklungs-Installation) oder
 *                 {@code "production"} (TestFlight) - an welchen APNs-Rechner die
 *                 Kennung gehoert. Fehlt das Feld (aeltere App), ist es die Sandbox,
 *                 wie bisher. Bei Android unbeachtet
 */
public record DeviceRegistration(String token, String platform, String environment) {

    /** Ohne Umgebung - so meldet sich jede App, die sie nicht kennt. */
    public DeviceRegistration(String token, String platform) {
        this(token, platform, null);
    }
}

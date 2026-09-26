package com.fherrmann.food.dto;

/**
 * Die Push-Kennung eines Geraets, wie die App sie meldet.
 *
 * @param token    bei iOS hexadezimal (APNs), bei Android die Registrierungskennung
 *                 von Firebase. Sie aendert sich gelegentlich von selbst - die Apps
 *                 melden sie deshalb bei jedem Start neu
 * @param platform {@code "android"} fuer Firebase; fehlt das Feld (wie bei der
 *                 iOS-App), ist es eine APNs-Kennung
 */
public record DeviceRegistration(String token, String platform) {
}

package com.fherrmann.food.dto;

/**
 * Die Push-Kennung eines Geraets, wie die App sie meldet.
 *
 * @param token Hexadezimal, wie iOS sie liefert. Sie aendert sich gelegentlich
 *              von selbst - die App meldet sie deshalb bei jedem Start neu.
 */
public record DeviceRegistration(String token) {
}

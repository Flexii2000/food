package com.fherrmann.food.dto;

import java.time.LocalDate;

/**
 * Freitext aus der Schnellerfassung, z. B. "mittags einen grossen Teller
 * Spaghetti Bolognese und ein Glas Milch".
 *
 * @param date Tag, auf den gebucht wird; ohne Angabe heute
 * @param text die Beschreibung, so wie sie eingetippt wurde
 */
public record QuickCaptureRequest(LocalDate date, String text) {
}

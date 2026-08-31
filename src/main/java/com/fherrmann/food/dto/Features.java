package com.fherrmann.food.dto;

/**
 * Welche optionalen Funktionen dieser Server anbietet. Die Oberflaeche fragt das
 * beim Laden ab, statt einen Knopf anzubieten, der dann mit einem Fehler
 * antwortet.
 *
 * @param quickCapture Schnellerfassung per Freitext - nur verfuegbar, wenn ein
 *                     Claude-API-Schluessel hinterlegt ist
 */
public record Features(boolean quickCapture) {
}

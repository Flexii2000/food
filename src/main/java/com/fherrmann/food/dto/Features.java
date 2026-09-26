package com.fherrmann.food.dto;

/**
 * Welche optionalen Funktionen dieser Server anbietet. Die Oberflaeche fragt das
 * beim Laden ab, statt einen Knopf anzubieten, der dann mit einem Fehler
 * antwortet.
 *
 * @param quickCapture Schnellerfassung per Freitext - nur verfuegbar, wenn der Agent
 *                     eingerichtet und sie fuer diese Person freigeschaltet ist
 * @param me           der Name zum Token, mit dem gefragt wurde
 * @param detailedNutrients ob diese Person die Detailwerte erfasst (gesaettigte
 *                     Fettsaeuren, Zucker, Ballaststoffe, Salz) - dann bieten die
 *                     Oberflaechen die Felder an
 */
public record Features(boolean quickCapture, String me, boolean detailedNutrients) {

    public Features(boolean quickCapture, String me) {
        this(quickCapture, me, false);
    }
}

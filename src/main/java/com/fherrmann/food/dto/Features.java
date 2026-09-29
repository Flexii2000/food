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
 * @param micronutrients ob diese Person Mikronaehrstoffe erfasst - dann gibt es die
 *                     Felder, die Tagesziele dafuer und die Uebersicht des Tages
 * @param veganMode    ob der vegane Modus dieser Person an ist - dann zeigen die
 *                     Oberflaechen nur vegane Gerichte zur Auswahl. Fehlt nie, damit
 *                     ein Client nicht zwischen "aus" und "unbekannt" raten muss
 */
public record Features(boolean quickCapture, String me, boolean detailedNutrients, boolean micronutrients,
                       boolean veganMode) {

    public Features(boolean quickCapture, String me) {
        this(quickCapture, me, false, false, false);
    }
}

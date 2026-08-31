package com.fherrmann.food.dto;

import com.fherrmann.food.model.Meal;

/**
 * Was die Schnellerfassung aus dem Freitext gemacht hat.
 *
 * @param day       der Tag nach dem Eintrag - dieselbe Form, die auch die
 *                  uebrigen schreibenden Endpunkte zurueckgeben
 * @param dishName  Name des angelegten bzw. wiederverwendeten Gerichts
 * @param grams     die verbuchte Menge
 * @param estimated ob Naehrwerte geschaetzt werden mussten, weil im Text keine
 *                  konkreten Zahlen standen
 * @param note      ein Satz dazu, worauf die Werte beruhen ("Portion auf 350 g
 *                  geschaetzt"). Wird in der Oberflaeche angezeigt, damit eine
 *                  Schaetzung nicht wie eine Messung aussieht.
 * @param meal      unter welcher Mahlzeit der Eintrag gelandet ist
 */
public record QuickCaptureResult(
        DaySummary day,
        String dishName,
        double grams,
        boolean estimated,
        String note,
        Meal meal) {
}

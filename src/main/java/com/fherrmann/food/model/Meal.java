package com.fherrmann.food.model;

/**
 * Zu welcher Mahlzeit ein Eintrag gehoert.
 *
 * <p>Bewusst eine feste, kurze Liste statt frei waehlbarer Namen: die
 * Oberflaeche zeigt genau diese vier Abschnitte, und ein fuenfter, den nur ein
 * einziger Eintrag benutzt, macht den Tag unuebersichtlicher statt klarer -
 * genau das, was die Aufteilung verhindern soll.
 *
 * <p>Am Eintrag ist das Feld {@code null}-erlaubt: Eintraege aus der Zeit vor
 * dieser Aufteilung haben keine Zuordnung, und die nachtraeglich zu raten hiesse,
 * eine Vermutung wie eine Angabe aussehen zu lassen. Die Oberflaeche zeigt sie
 * in einem eigenen Abschnitt, der verschwindet, sobald es keine mehr gibt.
 */
public enum Meal {
    BREAKFAST,
    LUNCH,
    DINNER,
    SNACK
}

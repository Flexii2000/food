package com.fherrmann.food.dto;

import com.fherrmann.food.model.Meal;

import java.time.LocalDate;

/**
 * Freitext aus der Schnellerfassung, z. B. "mittags einen grossen Teller
 * Spaghetti Bolognese und ein Glas Milch".
 *
 * @param date Tag, auf den gebucht wird; ohne Angabe heute
 * @param text die Beschreibung, so wie sie eingetippt wurde
 * @param meal die Mahlzeit, falls die Eingabe aus einem bestimmten Abschnitt
 *             kam. Ohne Angabe entscheidet der Agent anhand des Textes -
 *             "mittags einen Teller ..." sagt es ja selbst
 */
public record QuickCaptureRequest(LocalDate date, String text, Meal meal) {
}

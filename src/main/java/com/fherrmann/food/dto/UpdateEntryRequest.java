package com.fherrmann.food.dto;

import com.fherrmann.food.model.Meal;

import java.time.LocalDate;

/**
 * Einen vorhandenen Eintrag berichtigen: Menge, Mahlzeit, Tag.
 *
 * <p>Das Gericht selbst bleibt - wer etwas anderes gegessen hat, loescht
 * den Eintrag und legt ihn neu an. Sonst hinge an einem Eintrag ploetzlich
 * ein anderer Name mit anderen Werten, und die Tagessumme von damals
 * stimmte nicht mehr mit dem ueberein, was eingetragen wurde.
 *
 * @param grams neue Menge, Pflicht
 * @param meal  neue Mahlzeit; ohne Angabe bleibt die alte
 * @param date  neuer Tag; ohne Angabe bleibt der alte
 */
public record UpdateEntryRequest(Double grams, Meal meal, LocalDate date) {
}

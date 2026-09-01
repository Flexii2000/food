package com.fherrmann.food.dto;

import com.fherrmann.food.model.Meal;

import java.util.Map;

/**
 * Payload for changing the daily goals.
 *
 * @param mealShares optional: neue Aufteilung des kcal-Ziels auf die Mahlzeiten,
 *                   als Anteile. Ohne Angabe bleibt die bisherige stehen
 */
public record TargetsRequest(
        Double kcal, Double proteinG, Double carbsG, Double fatG,
        Map<Meal, Double> mealShares) {
}

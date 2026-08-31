package com.fherrmann.food.dto;

/**
 * Payload for creating or editing a dish in the library. Nutrition is given per 100 g;
 * {@code portionG} is optional and only prefills the amount field in the UI.
 */
public record DishRequest(
        String name,
        Double kcal,
        Double proteinG,
        Double carbsG,
        Double fatG,
        Double portionG) {
}

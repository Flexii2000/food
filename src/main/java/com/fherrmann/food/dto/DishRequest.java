package com.fherrmann.food.dto;

/**
 * Payload for creating or editing a dish in the library. Nutrition is given per 100 g;
 * {@code portionG} is optional and only prefills the amount field in the UI.
 *
 * <p>Die vier Detailwerte sind freiwillig - nur wer detailliert erfasst, bekommt die
 * Felder angeboten (siehe {@code DetailedNutrition}).
 */
public record DishRequest(
        String name,
        Double kcal,
        Double proteinG,
        Double carbsG,
        Double fatG,
        Double portionG,
        Double saturatedFatG,
        Double sugarG,
        Double fiberG,
        Double saltG) {

    /** Ohne Detailwerte - so schicken es die iPhone-App und die Weboberflaeche fuer Felix. */
    public DishRequest(String name, Double kcal, Double proteinG, Double carbsG, Double fatG, Double portionG) {
        this(name, kcal, proteinG, carbsG, fatG, portionG, null, null, null, null);
    }
}

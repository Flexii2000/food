package com.fherrmann.food.dto;

import java.util.Map;

/**
 * Payload for creating or editing a dish in the library. Nutrition is given per 100 g;
 * {@code portionG} is optional and only prefills the amount field in the UI.
 *
 * <p>Die vier Detailwerte sind freiwillig - nur wer detailliert erfasst, bekommt die
 * Felder angeboten (siehe {@code DetailedNutrition}).
 *
 * <p>{@code micros} ebenso, je 100 g und nur fuer Personen, die Mikronaehrstoffe
 * erfassen; bei allen anderen bleibt das Feld unbeachtet. Weil PUT das Gericht ganz
 * ersetzt, loescht ein Speichern ohne {@code micros} die gespeicherten.
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
        Double saltG,
        Map<String, Double> micros) {

    /** Ohne Detailwerte - so schicken es die iPhone-App und die Weboberflaeche fuer Felix. */
    public DishRequest(String name, Double kcal, Double proteinG, Double carbsG, Double fatG, Double portionG) {
        this(name, kcal, proteinG, carbsG, fatG, portionG, null, null, null, null, null);
    }

    /** Mit Detailwerten, ohne Mikronaehrstoffe. */
    public DishRequest(String name, Double kcal, Double proteinG, Double carbsG, Double fatG, Double portionG,
                       Double saturatedFatG, Double sugarG, Double fiberG, Double saltG) {
        this(name, kcal, proteinG, carbsG, fatG, portionG, saturatedFatG, sugarG, fiberG, saltG, null);
    }
}

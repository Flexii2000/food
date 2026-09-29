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
 *
 * <p>{@code vegan} ebenso fuer alle: {@code true}, {@code false} oder weggelassen fuer
 * unbekannt. Auch hier ersetzt PUT - wer das Kennzeichen behalten will, schickt es mit.
 * Beim Anlegen im veganen Modus gilt ein fehlendes Kennzeichen als {@code true}.
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
        Map<String, Double> micros,
        Boolean vegan) {

    /** Ohne Kennzeichen vegan - so schicken es alle Clients, die den veganen Modus nicht kennen. */
    public DishRequest(String name, Double kcal, Double proteinG, Double carbsG, Double fatG, Double portionG,
                       Double saturatedFatG, Double sugarG, Double fiberG, Double saltG,
                       Map<String, Double> micros) {
        this(name, kcal, proteinG, carbsG, fatG, portionG, saturatedFatG, sugarG, fiberG, saltG, micros, null);
    }

    /** Ohne Detailwerte - so schicken es die iPhone-App und die Weboberflaeche fuer Felix. */
    public DishRequest(String name, Double kcal, Double proteinG, Double carbsG, Double fatG, Double portionG) {
        this(name, kcal, proteinG, carbsG, fatG, portionG, null, null, null, null, null, null);
    }

    /** Mit Detailwerten, ohne Mikronaehrstoffe. */
    public DishRequest(String name, Double kcal, Double proteinG, Double carbsG, Double fatG, Double portionG,
                       Double saturatedFatG, Double sugarG, Double fiberG, Double saltG) {
        this(name, kcal, proteinG, carbsG, fatG, portionG, saturatedFatG, sugarG, fiberG, saltG, null, null);
    }

    /** Dasselbe Gericht mit anderem Kennzeichen vegan. */
    public DishRequest withVegan(Boolean vegan) {
        return new DishRequest(name, kcal, proteinG, carbsG, fatG, portionG, saturatedFatG, sugarG, fiberG, saltG,
                micros, vegan);
    }
}

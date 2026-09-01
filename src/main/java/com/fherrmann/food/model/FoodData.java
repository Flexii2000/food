package com.fherrmann.food.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Root object of the single source of truth ({@code food.json}).
 *
 * @param targets    daily goals; the big gauge measures the day against these
 * @param mealShares wie sich das kcal-Tagesziel auf die vier Mahlzeiten verteilt,
 *                   als Anteile. Bewusst Anteile und keine absoluten Werte: so
 *                   bleiben die Mahlzeitenziele stimmig, wenn das Tagesziel
 *                   geaendert wird, statt an zweiter Stelle nachgezogen werden
 *                   zu muessen
 * @param dishes     the remembered dish library
 * @param entries    every logged entry, across all days (unordered on disk)
 */
public record FoodData(
        Nutrients targets,
        Map<Meal, Double> mealShares,
        List<Dish> dishes,
        List<FoodEntry> entries) {

    /**
     * Daily goals used when {@code food.json} does not exist yet: 2300 kcal with 200 g
     * protein and 62 g fat, and carbohydrates filling the remainder exactly
     * (200*4 + 62*9 = 1358 kcal, leaving 942 kcal = 235.5 g).
     */
    public static final Nutrients DEFAULT_TARGETS = new Nutrients(2300, 200, 235.5, 62);

    /**
     * Uebliche Verteilung eines Tages: Mittag am groessten, Abend knapp dahinter,
     * Fruehstueck ein Viertel, der Rest fuer Zwischendurch. Bei 2300 kcal sind das
     * 575 / 805 / 690 / 230.
     */
    public static final Map<Meal, Double> DEFAULT_MEAL_SHARES = Map.of(
            Meal.BREAKFAST, 0.25,
            Meal.LUNCH, 0.35,
            Meal.DINNER, 0.30,
            Meal.SNACK, 0.10);

    public FoodData {
        targets = targets == null ? DEFAULT_TARGETS : targets;
        // Eine unvollstaendig gespeicherte Aufteilung waere schlimmer als gar
        // keine - dann summierten sich die Mahlzeitenziele nicht mehr auf den Tag.
        mealShares = mealShares == null || mealShares.size() != Meal.values().length
                ? DEFAULT_MEAL_SHARES
                : Map.copyOf(mealShares);
        dishes = dishes == null ? new ArrayList<>() : new ArrayList<>(dishes);
        entries = entries == null ? new ArrayList<>() : new ArrayList<>(entries);
    }

    public static FoodData empty() {
        return new FoodData(DEFAULT_TARGETS, DEFAULT_MEAL_SHARES, List.of(), List.of());
    }
}

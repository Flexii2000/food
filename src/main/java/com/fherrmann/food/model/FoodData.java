package com.fherrmann.food.model;

import com.fasterxml.jackson.annotation.JsonInclude;

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
 * @param microTargets die Tagesziele fuer Mikronaehrstoffe, getrennt von {@code targets}:
 *                   {@code null} heisst "nie gespeichert" (dann gilt die DGE-Vorgabe),
 *                   eine Liste - auch eine leere - ist eine Entscheidung, und ein
 *                   fehlender Schluessel darin heisst "bewusst ohne Ziel". In
 *                   {@code targets} liesse sich beides nicht auseinanderhalten, weil
 *                   eine leere Liste dort gar nicht im JSON steht
 * @param veganMode  der vegane Modus: {@code null} heisst "nie eingeschaltet" und
 *                   fehlt in der Datei, {@code true} an, {@code false} aus, aber schon
 *                   einmal an gewesen. Den Unterschied braucht das erste Einschalten -
 *                   nur dann werden die vorhandenen Gerichte als vegan markiert
 */
public record FoodData(
        Nutrients targets,
        Map<Meal, Double> mealShares,
        List<Dish> dishes,
        List<FoodEntry> entries,
        @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Double> microTargets,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean veganMode) {

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
        microTargets = microTargets == null ? null : Micronutrient.ordered(microTargets);
    }

    /** Ohne veganen Modus - so sieht jedes Tagebuch aus, das ihn nie eingeschaltet hat. */
    public FoodData(Nutrients targets, Map<Meal, Double> mealShares, List<Dish> dishes, List<FoodEntry> entries,
                    Map<String, Double> microTargets) {
        this(targets, mealShares, dishes, entries, microTargets, null);
    }

    /** Ohne gespeicherte Mikro-Ziele - so sieht jedes Tagebuch aus, das sie nie hatte. */
    public FoodData(Nutrients targets, Map<Meal, Double> mealShares, List<Dish> dishes, List<FoodEntry> entries) {
        this(targets, mealShares, dishes, entries, null, null);
    }

    public static FoodData empty() {
        return new FoodData(DEFAULT_TARGETS, DEFAULT_MEAL_SHARES, List.of(), List.of());
    }

    /**
     * Derselbe Stand mit anderen Gerichten und Eintraegen, die Ziele bleiben. Jede
     * Aenderung am Tagebuch geht hierueber, damit keine davon die Mikro-Ziele oder den
     * veganen Modus verliert.
     */
    public FoodData with(List<Dish> dishes, List<FoodEntry> entries) {
        return new FoodData(targets, mealShares, dishes, entries, microTargets, veganMode);
    }

    /** Ob der vegane Modus gerade an ist. */
    public boolean veganModeOn() {
        return Boolean.TRUE.equals(veganMode);
    }
}

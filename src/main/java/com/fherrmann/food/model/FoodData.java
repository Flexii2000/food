package com.fherrmann.food.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Root object of the single source of truth ({@code food.json}).
 *
 * @param targets daily goals; the big gauge measures the day against these
 * @param dishes  the remembered dish library
 * @param entries every logged entry, across all days (unordered on disk)
 */
public record FoodData(Nutrients targets, List<Dish> dishes, List<FoodEntry> entries) {

    /**
     * Daily goals used when {@code food.json} does not exist yet: 2300 kcal with 200 g
     * protein and 62 g fat, and carbohydrates filling the remainder exactly
     * (200*4 + 62*9 = 1358 kcal, leaving 942 kcal = 235.5 g).
     */
    public static final Nutrients DEFAULT_TARGETS = new Nutrients(2300, 200, 235.5, 62);

    public FoodData {
        targets = targets == null ? DEFAULT_TARGETS : targets;
        dishes = dishes == null ? new ArrayList<>() : new ArrayList<>(dishes);
        entries = entries == null ? new ArrayList<>() : new ArrayList<>(entries);
    }

    public static FoodData empty() {
        return new FoodData(DEFAULT_TARGETS, List.of(), List.of());
    }
}

package com.fherrmann.food.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fherrmann.food.model.FoodEntry;
import com.fherrmann.food.model.Meal;
import com.fherrmann.food.model.Nutrients;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Everything the day view needs in one response: the goals, what has been eaten
 * against them, and the entries it adds up from.
 *
 * @param date      the day being shown
 * @param targets   daily goals
 * @param consumed  sum of all entries on that day
 * @param remaining {@code targets - consumed}; negative once a goal is exceeded, which
 *                  the UI shows rather than clamping - being over is the thing worth seeing
 * @param entries     the day's entries, oldest first
 * @param mealTargets kcal-Ziel je Mahlzeit, aus dem Tagesziel und der
 *                    hinterlegten Aufteilung gerechnet. Summiert sich damit
 *                    immer auf {@code targets.kcal()}
 * @param detailGaps  die Detailwerte (Feldnamen aus {@link Nutrients#DETAIL_FIELDS}),
 *                    bei denen mindestens ein Eintrag des Tages keine Angabe hat - dort
 *                    ist die Summe in {@code consumed} eine Untergrenze. Leer, und dann
 *                    gar nicht im JSON, wenn es keine Luecke gibt oder der Tag ueberhaupt
 *                    keine Detailwerte kennt (Felix' Tagebuch)
 */
public record DaySummary(
        LocalDate date,
        Nutrients targets,
        Nutrients consumed,
        Nutrients remaining,
        List<FoodEntry> entries,
        Map<Meal, Double> mealTargets,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> detailGaps) {

    public DaySummary(LocalDate date, Nutrients targets, Nutrients consumed, Nutrients remaining,
                      List<FoodEntry> entries, Map<Meal, Double> mealTargets) {
        this(date, targets, consumed, remaining, entries, mealTargets, List.of());
    }
}

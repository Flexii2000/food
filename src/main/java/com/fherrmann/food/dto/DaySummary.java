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
 * @param targets   daily goals, mit dem Eiweiß-Erhalt (siehe {@link TargetsView})
 * @param consumed  sum of all entries on that day
 * @param remaining {@code targets - consumed}; negative once a goal is exceeded, which
 *                  the UI shows rather than clamping - being over is the thing worth seeing
 * @param entries     the day's entries, oldest first
 * @param mealTargets kcal-Ziel je Mahlzeit, aus dem Tagesziel und der
 *                    hinterlegten Aufteilung gerechnet. Summiert sich damit
 *                    immer auf {@code targets.kcal()} - mit Cappuccino-Zaehler
 *                    zusammen mit {@code cappuccino.target}
 * @param detailGaps  die Detailwerte (Feldnamen aus {@link Nutrients#DETAIL_FIELDS}),
 *                    bei denen mindestens ein Eintrag des Tages keine Angabe hat - dort
 *                    ist die Summe in {@code consumed} eine Untergrenze. Leer, und dann
 *                    gar nicht im JSON, wenn es keine Luecke gibt oder der Tag ueberhaupt
 *                    keine Detailwerte kennt (Felix' Tagebuch)
 * @param microGaps   dasselbe fuer die Mikronaehrstoffe (Schluessel aus
 *                    {@link com.fherrmann.food.model.Micronutrient#KEYS})
 * @param cappuccino  der Cappuccino-Zaehler, nur fuer Personen, die ihn haben (sonst nicht
 *                    im JSON): sein eigenes Budget als Anteil am Tagesziel, neben den
 *                    Mahlzeiten (siehe {@link CappuccinoDay})
 */
public record DaySummary(
        LocalDate date,
        TargetsView targets,
        Nutrients consumed,
        Nutrients remaining,
        List<FoodEntry> entries,
        Map<Meal, Double> mealTargets,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> detailGaps,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> microGaps,
        @JsonInclude(JsonInclude.Include.NON_NULL) CappuccinoDay cappuccino) {

    /** Ohne Cappuccino-Zaehler. */
    public DaySummary(LocalDate date, TargetsView targets, Nutrients consumed, Nutrients remaining,
                      List<FoodEntry> entries, Map<Meal, Double> mealTargets,
                      List<String> detailGaps, List<String> microGaps) {
        this(date, targets, consumed, remaining, entries, mealTargets, detailGaps, microGaps, null);
    }

    /** Ohne Luecken und mit der Vorgabe fuer den Eiweiß-Erhalt. */
    public DaySummary(LocalDate date, Nutrients targets, Nutrients consumed, Nutrients remaining,
                      List<FoodEntry> entries, Map<Meal, Double> mealTargets) {
        this(date, TargetsView.of(targets, null), consumed, remaining, entries, mealTargets, List.of(), List.of());
    }
}

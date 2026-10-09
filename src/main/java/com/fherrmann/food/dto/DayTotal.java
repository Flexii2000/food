package com.fherrmann.food.dto;

import com.fherrmann.food.model.Meal;
import com.fherrmann.food.model.Nutrients;

import java.time.LocalDate;
import java.util.List;

/**
 * One day's totals, without the individual entries. This is what the weight tracker
 * pulls to draw its kcal overlay, so it stays deliberately small.
 *
 * <p>{@code meals} nennt die Mahlzeiten, zu denen es an dem Tag mindestens einen
 * Eintrag gibt, in der Reihenfolge des Enums. Der Weight Tracker braucht das fuer
 * dieselbe Regel "getrackter Tag", die coHabit fuer "Track food" anwendet (80 % des
 * Ziels oder Fruehstueck, Mittag und Abend) - ohne dafuer jeden Tag einzeln ueber
 * {@code /day} zu holen. Eintraege ohne Mahlzeit (aus der Zeit vor der Aufteilung)
 * zaehlen nicht: eine Vermutung soll keinen Tag zum getrackten machen.
 */
public record DayTotal(LocalDate date, Nutrients consumed, List<Meal> meals) {

    public DayTotal(LocalDate date, Nutrients consumed) {
        this(date, consumed, List.of());
    }
}

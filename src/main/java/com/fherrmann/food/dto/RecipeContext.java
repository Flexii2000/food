package com.fherrmann.food.dto;

import com.fherrmann.food.model.Meal;

import java.time.LocalDate;
import java.util.List;

/**
 * Was der Rezept-Chat der Einkaufsliste ueber <b>eine</b> Person wissen darf, damit er
 * Fragen wie "Wie passt das zu meinen Zielen?" beantworten kann
 * ({@code POST /api/internal/recipe-context}, Healthy-Vertrag §11.3).
 *
 * <p>Bewusst knapp und nur Ernaehrung: Ziele, heute, die letzten zwei Wochen, was die
 * Person oft isst. Kein Gewicht, keine Naechte, keine Namen anderer Personen - die
 * Einkaufsliste fragt je Person und gibt nur deren Antwort an deren Chat weiter.
 *
 * @param date     heute (Europe/Berlin)
 * @param days     die 14 Tage vor heute, an denen etwas eingetragen ist, aelteste zuerst
 * @param average7 Mittel ueber die getrackten Tage der letzten sieben, oder {@code null}
 * @param frequent die haeufigsten Eintraege der letzten 90 Tage
 * @param vegan    ob der vegane Modus der Person an ist
 */
public record RecipeContext(
        LocalDate date,
        Goals targets,
        Today today,
        List<Day> days,
        Average average7,
        List<Favorite> frequent,
        boolean vegan) {

    /** Die Tagesziele; {@code proteinMinG} ist der Eiweiß-Erhalt. */
    public record Goals(double kcal, double proteinG, double proteinMinG, double carbsG, double fatG) {
    }

    public record Macros(double kcal, double proteinG, double carbsG, double fatG) {
    }

    /** {@code remaining} ist Ziel minus gegessen - negativ heisst drueber. */
    public record Today(Macros eaten, Macros remaining, List<Eaten> entries) {
    }

    public record Eaten(Meal meal, String name, double grams, double kcal, double proteinG) {
    }

    /**
     * @param tracked dieselbe Regel wie ueberall in Healthy: mindestens 80 % des
     *                kcal-Ziels oder Fruehstueck, Mittag- und Abendessen eingetragen -
     *                ein Tag mit nur einem Snack sagt nichts ueber den Tag
     */
    public record Day(LocalDate date, double kcal, double proteinG, double carbsG, double fatG, boolean tracked) {
    }

    public record Average(int days, double kcal, double proteinG) {
    }

    public record Favorite(String name, int count, double avgGrams, double kcalPer100g, double proteinPer100g) {
    }
}

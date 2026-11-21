package com.fherrmann.food.service;

import com.fherrmann.food.dto.CappuccinoDay;
import com.fherrmann.food.dto.DishRequest;
import com.fherrmann.food.model.Dish;
import com.fherrmann.food.model.FoodEntry;
import com.fherrmann.food.model.Nutrients;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Der Cappuccino-Zaehler ueber dem Fruehstueck (Felix, 21.11.2026): jeden Morgen derselbe
 * Cappuccino, statt ihn jedes Mal als Essen einzutragen nur ein "+".
 *
 * <p><b>Ein Cappuccino ist ein ganz normaler Eintrag</b> des Cappuccino-Gerichts, eine
 * Portion schwer. Kein eigener Zaehlerstand neben dem Tagebuch: so zaehlt er ohne
 * Sonderweg zu kcal und Eiweiß, im Verlauf, im Weight Tracker, im Chat - und ein frueher
 * von Hand eingetragener Cappuccino zaehlt im Zaehler mit.
 *
 * <p><b>Eigenes Budget:</b> bis zu {@link #BUDGET} Cappuccinos am Tag kommen zum Tagesziel
 * dazu, statt es aufzufressen. Das gespeicherte Ziel bleibt dabei unangetastet - die
 * Oberflaechen fuellen ihre Ziel-Formulare aus {@code targets}, und ein Ziel, das um die
 * Cappuccinos des Tages gewachsen ist, wuerde beim naechsten Speichern zur festen Zahl.
 * Stattdessen steht der Zuschlag als {@link CappuccinoDay#bonus()} im Tag, und
 * {@code remaining} rechnet ihn ein.
 *
 * <p>Welches Gericht der Cappuccino ist: das, dessen Name "Cappu…"/"Capu…" enthaelt (Felix:
 * "Cappuchino (zuhause)"), mit Portion vor ohne, zuletzt gegessen zuerst. Seine Portion
 * ist ein Cappuccino - wer die Tasse groesser macht, aendert die Portion des Gerichts. Gibt
 * es keins, legt das erste "+" eins mit den Werten aus {@link #DEFAULT_DISH} an.
 *
 * <p>Je Person schaltbar wie die anderen Vorlieben ({@code FOOD_CAPPUCCINO=felix,torben},
 * {@code *} fuer alle); ohne Einstellung hat ihn nur die Eigentuemerin.
 */
@Component
public class CappuccinoCounter {

    /** So viele Cappuccinos am Tag gehen aufs eigene Budget; jeder weitere zaehlt normal. */
    public static final int BUDGET = 4;

    /**
     * Gilt nur, wenn die Person noch kein Cappuccino-Gericht hat: eine Tasse mit Espresso
     * und etwa 180 ml aufgeschaeumter Oatly Barista - rund 210 g, 105 kcal, 2 g Eiweiß.
     */
    static final DishRequest DEFAULT_DISH = new DishRequest(
            "Cappuccino (Hafermilch)", 50.0, 0.95, 5.7, 2.6, 210.0,
            null, null, null, null, null, true);

    private final PeopleSelection people;

    @Autowired
    public CappuccinoCounter(@Value("${food.cappuccino-people:}") String configured,
                             @Value("${health.owner:felix}") String owner) {
        this.people = PeopleSelection.parse(configured, Set.of(owner.trim()), "food.cappuccino-people");
    }

    /** Niemand - fuer Tests und Aufrufer ohne Spring. */
    public static CappuccinoCounter none() {
        return of("");
    }

    private CappuccinoCounter(PeopleSelection people) {
        this.people = people;
    }

    /** Nur diese Personen - fuer Tests. */
    public static CappuccinoCounter of(String people) {
        return new CappuccinoCounter(PeopleSelection.parse(people, Set.of(), "food.cappuccino-people"));
    }

    public boolean isEnabled(String user) {
        return people.contains(user);
    }

    /** Das Cappuccino-Gericht der Person, wenn sie eins hat. */
    static Optional<Dish> dish(List<Dish> dishes) {
        return dishes.stream()
                .filter(d -> isCappuccino(d.name()))
                .min(Comparator
                        .comparing((Dish d) -> d.portionG() == null || d.portionG() <= 0)
                        .thenComparing(Dish::lastUsedOn, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(d -> d.name().toLowerCase(Locale.ROOT)));
    }

    static boolean isCappuccino(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("cappu") || lower.contains("capu");
    }

    /** Eine Portion des Gerichts in Gramm - ohne Portion die der Vorgabe. */
    static double portion(Dish dish) {
        return dish != null && dish.portionG() != null && dish.portionG() > 0
                ? dish.portionG()
                : DEFAULT_DISH.portionG();
    }

    /**
     * Der Zaehler fuer einen Tag.
     *
     * @param dish    das Cappuccino-Gericht, oder {@code null}, wenn es noch keins gibt
     * @param entries die Eintraege des Tages, aelteste zuerst
     */
    static CappuccinoDay day(Dish dish, List<FoodEntry> entries) {
        Nutrients perCup;
        if (dish == null) {
            perCup = new Nutrients(DEFAULT_DISH.kcal(), DEFAULT_DISH.proteinG(), DEFAULT_DISH.carbsG(),
                    DEFAULT_DISH.fatG()).scaled(DEFAULT_DISH.portionG() / 100.0);
            return new CappuccinoDay(null, DEFAULT_DISH.name(), 0, BUDGET, DEFAULT_DISH.portionG(), perCup.rounded(),
                    Nutrients.ZERO);
        }
        double portion = portion(dish);
        perCup = dish.per100g().scaled(portion / 100.0);

        // Die Eintraege tragen ihre eigene Kopie der Naehrwerte - der Zuschlag nimmt die,
        // nicht die heutigen Werte des Gerichts, sonst aenderte eine Korrektur am Gericht
        // nachtraeglich das Budget vergangener Tage.
        double grams = 0;
        double budgetLeft = BUDGET * portion;
        Nutrients bonus = Nutrients.ZERO;
        for (FoodEntry entry : entries) {
            if (!dish.id().equals(entry.dishId())) {
                continue;
            }
            grams += entry.grams();
            double counted = Math.min(entry.grams(), budgetLeft);
            if (counted > 0 && entry.per100g() != null) {
                bonus = bonus.plus(entry.per100g().scaled(counted / 100.0));
                budgetLeft -= counted;
            }
        }
        int count = (int) Math.round(grams / portion);
        // Eiweiß ist ein Mindestziel - ein hoeheres Ziel waere eine Strafe, kein Budget.
        Nutrients budget = new Nutrients(bonus.kcal(), 0, bonus.carbsG(), bonus.fatG());
        return new CappuccinoDay(dish.id(), dish.name(), count, BUDGET, portion,
                new Nutrients(perCup.kcal(), perCup.proteinG(), perCup.carbsG(), perCup.fatG()).rounded(),
                budget.rounded());
    }
}

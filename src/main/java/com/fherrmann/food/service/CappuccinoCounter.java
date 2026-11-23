package com.fherrmann.food.service;

import com.fherrmann.food.dto.CappuccinoDay;
import com.fherrmann.food.dto.DishRequest;
import com.fherrmann.food.model.Dish;
import com.fherrmann.food.model.FoodEntry;
import com.fherrmann.food.model.Meal;
import com.fherrmann.food.model.Nutrients;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * <p><b>Eigenes Budget</b> (Felix, 23.11.): ein Anteil am kcal-Tagesziel wie Fruehstueck,
 * Mittag, Abend und Snacks, zusammen 100 % - einstellbar wie die anderen Anteile. Die
 * Eintraege des Gerichts zaehlen in dieses Budget und nicht mehr zu der Mahlzeit, unter der
 * sie stehen. Nur der Zaehler-Cappuccino, also dieses eine Gericht: ein anderer Cappuccino
 * (Café) bleibt in seiner Mahlzeit (Felix' Wahl). Bis 23.11. kamen stattdessen bis zu vier
 * Cappuccinos am Tag zum Tagesziel dazu; das Ziel waechst seitdem nicht mehr.
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

    /**
     * Der Anteil, solange keiner gespeichert ist - genommen vom Fruehstueck, weil die
     * Cappuccinos bis zum 23.11. dort mitzaehlten (Felix' Startwert: Fruehstueck 25 -> 15 %,
     * Cappuccino 10 %; drei Cappuccinos sind bei ihm 226 kcal).
     */
    public static final double DEFAULT_SHARE = 0.10;

    /**
     * Die Aufteilung des kcal-Tagesziels samt Cappuccino.
     *
     * @param meals      die Anteile der vier Mahlzeiten
     * @param cappuccino der Anteil der Cappuccinos; mit den Mahlzeiten zusammen 1
     */
    public record Shares(Map<Meal, Double> meals, double cappuccino) {
    }

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

    /**
     * Die Aufteilung, wie sie fuer eine Person mit Zaehler gilt. Ist noch kein Cappuccino-Anteil
     * gespeichert, nimmt er {@link #DEFAULT_SHARE} vom Fruehstueck, hoechstens so viel, wie das
     * Fruehstueck hat. Gespeichert wird das erst, wenn die Person ihre Ziele sichert - bis
     * dahin bleibt die Datei, wie sie war, und die Vorgabe gilt nur beim Lesen.
     *
     * @param meals  die gespeicherten Anteile der vier Mahlzeiten
     * @param stored der gespeicherte Cappuccino-Anteil, oder {@code null}
     */
    public static Shares shares(Map<Meal, Double> meals, Double stored) {
        if (stored != null) {
            return new Shares(meals, stored);
        }
        double breakfast = meals.getOrDefault(Meal.BREAKFAST, 0.0);
        double taken = Math.min(DEFAULT_SHARE, breakfast);
        Map<Meal, Double> rest = new EnumMap<>(meals);
        // Auf vier Stellen: sonst wird aus 0,25 - 0,1 eine 0,15000000000000002.
        rest.put(Meal.BREAKFAST, Math.round((breakfast - taken) * 10_000) / 10_000.0);
        return new Shares(Map.copyOf(rest), taken);
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
     * @param entries die Eintraege des Tages
     * @param share   der Anteil der Cappuccinos am kcal-Tagesziel ({@link #shares})
     * @param dayKcal das kcal-Tagesziel
     */
    static CappuccinoDay day(Dish dish, List<FoodEntry> entries, double share, double dayKcal) {
        // Auf eine Stelle wie die Mahlzeitenziele - so gehen beide zusammen genau auf den Tag auf.
        double target = Math.round(dayKcal * share * 10) / 10.0;
        if (dish == null) {
            Nutrients perCup = new Nutrients(DEFAULT_DISH.kcal(), DEFAULT_DISH.proteinG(), DEFAULT_DISH.carbsG(),
                    DEFAULT_DISH.fatG()).scaled(DEFAULT_DISH.portionG() / 100.0);
            return new CappuccinoDay(null, DEFAULT_DISH.name(), 0, DEFAULT_DISH.portionG(), perCup.rounded(),
                    share, target);
        }
        double portion = portion(dish);
        Nutrients perCup = dish.per100g().scaled(portion / 100.0);
        double grams = entries.stream()
                .filter(entry -> dish.id().equals(entry.dishId()))
                .mapToDouble(FoodEntry::grams)
                .sum();
        int count = (int) Math.round(grams / portion);
        return new CappuccinoDay(dish.id(), dish.name(), count, portion,
                new Nutrients(perCup.kcal(), perCup.proteinG(), perCup.carbsG(), perCup.fatG()).rounded(),
                share, target);
    }
}

package com.fherrmann.food.model;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One thing eaten on one day.
 *
 * <p>The entry carries its own copy of the name and the per-100 g values rather than
 * only pointing at a {@link Dish}. That is deliberate: correcting a dish's nutrition
 * today must not silently rewrite what last month's days added up to, and deleting a
 * dish from the library must not blank out the history that referenced it. The
 * {@code dishId} is kept as a back-reference for the UI (and to bump
 * {@link Dish#lastUsedOn()}), but nothing is computed from it.
 *
 * @param id        stable identifier, generated on creation
 * @param date      the day this was eaten
 * @param dishId    the library dish this came from, or {@code null} for a one-off
 * @param name      dish name as it was at the time of logging
 * @param grams     amount eaten, in grams
 * @param per100g   nutrition per 100 g as it was at the time of logging
 * @param createdAt when the entry was made; orders entries within a day
 */
public record FoodEntry(
        String id,
        LocalDate date,
        String dishId,
        String name,
        double grams,
        Nutrients per100g,
        Instant createdAt) {

    /** What this entry actually contributes to the day: per-100 g values times the amount. */
    public Nutrients total() {
        return per100g == null ? Nutrients.ZERO : per100g.scaled(grams / 100.0);
    }
}

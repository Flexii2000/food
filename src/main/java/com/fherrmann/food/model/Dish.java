package com.fherrmann.food.model;

import java.time.LocalDate;

/**
 * A dish the app has seen before, so it can be entered again without retyping its
 * nutrition values.
 *
 * <p>Nutrition is stored <b>per 100 g</b> rather than per portion: that is how the
 * numbers appear on packaging, and it is the only basis on which an arbitrary amount
 * can be computed. {@code portionG} is the convenience on top - the usual serving size
 * in grams, so a normal helping is one click instead of a guess. It is optional; a
 * dish without one is simply always entered by weight.
 *
 * @param id         stable identifier, generated on creation
 * @param name       display name, unique (case-insensitively) within the library
 * @param per100g    nutrition values for 100 g of this dish
 * @param portionG   usual serving size in grams, or {@code null} if there is none
 * @param lastUsedOn day this dish was last logged, or {@code null} if never; only used
 *                   to sort the picker so the things eaten regularly stay on top
 */
public record Dish(
        String id,
        String name,
        Nutrients per100g,
        Double portionG,
        LocalDate lastUsedOn) {
}

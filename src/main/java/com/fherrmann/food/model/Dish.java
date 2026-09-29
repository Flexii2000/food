package com.fherrmann.food.model;

import com.fasterxml.jackson.annotation.JsonInclude;

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
 * @param vegan      ob das Gericht vegan ist: {@code true}, {@code false}, oder
 *                   {@code null} fuer unbekannt. Unbekannt fehlt im JSON ganz - so
 *                   sieht jedes Gericht aus, das nie jemand eingeordnet hat, und
 *                   Felix' Liste bleibt, wie sie war
 */
public record Dish(
        String id,
        String name,
        Nutrients per100g,
        Double portionG,
        LocalDate lastUsedOn,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean vegan) {

    /** Ohne Kennzeichen - vegan unbekannt. */
    public Dish(String id, String name, Nutrients per100g, Double portionG, LocalDate lastUsedOn) {
        this(id, name, per100g, portionG, lastUsedOn, null);
    }

    /**
     * Ob das Gericht ausdruecklich als vegan gekennzeichnet ist - unbekannt zaehlt nicht.
     * Bewusst nicht {@code isVegan}: das hielte Jackson fuer den Getter von {@code vegan}
     * und schriebe ein unbekanntes Gericht als {@code false} in die Datei.
     */
    public boolean markedVegan() {
        return Boolean.TRUE.equals(vegan);
    }

    public Dish withVegan(Boolean vegan) {
        return new Dish(id, name, per100g, portionG, lastUsedOn, vegan);
    }
}

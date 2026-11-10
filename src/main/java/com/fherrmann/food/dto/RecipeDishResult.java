package com.fherrmann.food.dto;

/**
 * Was aus dem Rezept-Gericht bei einer Person geworden ist.
 *
 * @param person  der Name aus der Anfrage
 * @param status  {@code created}, {@code updated}, {@code unchanged}, {@code not-linked}
 *                (keine Healthy-Person dieses Namens) oder {@code rejected}
 * @param message bei {@code rejected} der Grund, sonst {@code null}
 */
public record RecipeDishResult(String person, String status, String message) {

    public static final String CREATED = "created";
    public static final String UPDATED = "updated";
    public static final String UNCHANGED = "unchanged";
    public static final String NOT_LINKED = "not-linked";
    public static final String REJECTED = "rejected";

    public static RecipeDishResult of(String person, String status) {
        return new RecipeDishResult(person, status, null);
    }

    public static RecipeDishResult rejected(String person, String message) {
        return new RecipeDishResult(person, REJECTED, message);
    }
}

package com.fherrmann.food.dto;

import java.time.LocalDate;

/**
 * Payload for logging something eaten.
 *
 * <p>Two ways in, because both happen in practice: either the dish is already in the
 * library ({@code dishId} set), or it is being typed for the first time ({@code dish}
 * set), in which case it is remembered on the way through so the next time it can be
 * picked. Exactly one of the two must be present.
 *
 * @param date   day to log it on; defaults to today when omitted
 * @param dishId id of a remembered dish
 * @param dish   a new dish to store and log in one go
 * @param grams  amount eaten in grams
 */
public record NewEntryRequest(LocalDate date, String dishId, DishRequest dish, Double grams) {
}

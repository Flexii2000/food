package com.fherrmann.food.dto;

import com.fherrmann.food.model.Nutrients;

import java.time.LocalDate;

/**
 * One day's totals, without the individual entries. This is what the weight tracker
 * pulls to draw its kcal overlay, so it stays deliberately small.
 */
public record DayTotal(LocalDate date, Nutrients consumed) {
}

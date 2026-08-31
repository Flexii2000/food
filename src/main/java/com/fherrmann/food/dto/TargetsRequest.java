package com.fherrmann.food.dto;

/** Payload for changing the daily goals. */
public record TargetsRequest(Double kcal, Double proteinG, Double carbsG, Double fatG) {
}

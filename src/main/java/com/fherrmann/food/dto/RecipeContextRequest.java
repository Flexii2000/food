package com.fherrmann.food.dto;

/**
 * Fuer wen die Einkaufsliste den Zusammenhang fuer ihren Rezept-Chat holt
 * ({@code POST /api/internal/recipe-context}) - genau eine Person: die, die fragt.
 */
public record RecipeContextRequest(String person) {
}

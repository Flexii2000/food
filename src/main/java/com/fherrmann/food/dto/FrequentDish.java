package com.fherrmann.food.dto;

import java.time.LocalDate;

/**
 * Ein Gericht, das die Person oft isst - gezaehlt aus ihren Eintraegen, nicht aus der
 * Gerichteliste ({@code GET /api/food/frequent-dishes}, Healthy-Vertrag §11.4).
 *
 * <p>Die Gerichteliste weiss nur, wann etwas zuletzt gegessen wurde; fuer "was koennte
 * ich heute noch essen" zaehlt, was regelmaessig auf dem Teller liegt und in welcher
 * Menge.
 *
 * @param name       der Name, wie er beim letzten Eintrag stand
 * @param count      wie oft es im Zeitraum eingetragen wurde
 * @param lastUsedOn der Tag des letzten Eintrags im Zeitraum
 * @param avgGrams   die mittlere Menge je Eintrag, auf ganze Gramm
 * @param kcalPer100g und die drei Makros je 100 g, wie beim letzten Eintrag
 */
public record FrequentDish(String name, int count, LocalDate lastUsedOn, double avgGrams,
                           double kcalPer100g, double proteinPer100g, double carbsPer100g, double fatPer100g) {
}

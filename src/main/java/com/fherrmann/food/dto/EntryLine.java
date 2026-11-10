package com.fherrmann.food.dto;

import com.fherrmann.food.model.Meal;

import java.time.LocalDate;

/**
 * Ein Eintrag als eine Zeile: was, wie viel, und was es zum Tag beitraegt.
 *
 * <p>Fuer {@code GET /api/food/entries} (Healthy-Vertrag §11.4) - der Weight Tracker
 * gibt dem Chat damit die Mahlzeiten der letzten Tage mit. Bewusst flach und ohne
 * {@code id}, {@code dishId} und die Werte je 100 g: gelesen wird hier nur.
 *
 * @param meal  die Mahlzeit, oder {@code null} bei Eintraegen aus der Zeit davor
 * @param grams die Menge
 * @param kcal  und die drei Makros: der Beitrag dieses Eintrags, nicht je 100 g
 */
public record EntryLine(LocalDate date, Meal meal, String name, double grams,
                        double kcal, double proteinG, double carbsG, double fatG) {
}

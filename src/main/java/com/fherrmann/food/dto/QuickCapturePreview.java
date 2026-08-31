package com.fherrmann.food.dto;

import com.fherrmann.food.model.Meal;
import com.fherrmann.food.model.Nutrients;

import java.util.Map;

/**
 * Der Vorschlag der Schnellerfassung - <b>noch nichts eingetragen</b>.
 *
 * <p>Die Auswertung schreibt bewusst nicht selbst: eine geschaetzte Zahl, die
 * ungefragt im Tagebuch landet, sieht hinterher genauso aus wie eine abgelesene.
 * Der Nutzer bekommt den Vorschlag zu sehen, sieht je Wert, woher er stammt, und
 * bestaetigt ihn dann ueber den normalen Eintrags-Endpunkt.
 *
 * @param known        ob ein bereits gespeichertes Gericht erkannt wurde
 * @param dishId       dessen Id - beim Bestaetigen genuegt sie, dann bleiben die
 *                     gepflegten Werte unangetastet; {@code null} bei einem neuen Gericht
 * @param name         Name des Gerichts
 * @param per100g      Naehrwerte je 100 g
 * @param portionG     uebliche Portionsgroesse, oder {@code null}
 * @param grams        vorgeschlagene Menge
 * @param meal         vorgeschlagene Mahlzeit
 * @param valueSources je Feld ({@code kcal}, {@code proteinG}, {@code carbsG},
 *                     {@code fatG}, {@code grams}, {@code portionG}) die Herkunft:
 *                     {@code stored} aus der Gerichteliste, {@code read} als Zahl
 *                     im Text gestanden, {@code estimated} geschaetzt
 * @param note         ein Satz zur Herleitung
 */
public record QuickCapturePreview(
        boolean known,
        String dishId,
        String name,
        Nutrients per100g,
        Double portionG,
        double grams,
        Meal meal,
        Map<String, String> valueSources,
        String note) {
}

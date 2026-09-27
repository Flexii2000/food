package com.fherrmann.food.dto;

import com.fherrmann.food.model.Meal;

import java.util.Map;

/**
 * Payload for changing the daily goals.
 *
 * @param mealShares optional: neue Aufteilung des kcal-Ziels auf die Mahlzeiten,
 *                   als Anteile. Ohne Angabe bleibt die bisherige stehen
 * @param micros     optional: die Tagesziele fuer Mikronaehrstoffe, jedes groesser als 0.
 *                   Ein fehlender Schluessel heisst "kein Ziel fuer diesen Naehrstoff";
 *                   fehlt das Feld ganz, bleiben die gespeicherten stehen - so loescht ein
 *                   Client, der es nicht kennt, nichts. Nur fuer Personen, die
 *                   Mikronaehrstoffe erfassen
 */
public record TargetsRequest(
        Double kcal, Double proteinG, Double carbsG, Double fatG,
        Map<Meal, Double> mealShares,
        Map<String, Double> micros) {

    /** Ohne Mikro-Ziele - so schicken es alle Clients, die sie nicht kennen. */
    public TargetsRequest(Double kcal, Double proteinG, Double carbsG, Double fatG, Map<Meal, Double> mealShares) {
        this(kcal, proteinG, carbsG, fatG, mealShares, null);
    }
}

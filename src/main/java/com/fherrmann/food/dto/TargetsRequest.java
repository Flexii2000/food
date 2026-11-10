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
 * @param proteinMinG optional: der Eiweiß-Erhalt in Gramm, groesser als 0 und hoechstens
 *                   {@code proteinG}. Fehlt das Feld, bleibt der gespeicherte stehen (und
 *                   sinkt still mit, wenn das Eiweißziel darunter faellt); {@code 0} heisst
 *                   "zurueck auf die Vorgabe"
 */
public record TargetsRequest(
        Double kcal, Double proteinG, Double carbsG, Double fatG,
        Map<Meal, Double> mealShares,
        Map<String, Double> micros,
        Double proteinMinG) {

    /** Ohne Eiweiß-Erhalt - so schicken es alle Clients, die ihn nicht kennen. */
    public TargetsRequest(Double kcal, Double proteinG, Double carbsG, Double fatG, Map<Meal, Double> mealShares,
                          Map<String, Double> micros) {
        this(kcal, proteinG, carbsG, fatG, mealShares, micros, null);
    }

    /** Ohne Mikro-Ziele - so schicken es alle Clients, die sie nicht kennen. */
    public TargetsRequest(Double kcal, Double proteinG, Double carbsG, Double fatG, Map<Meal, Double> mealShares) {
        this(kcal, proteinG, carbsG, fatG, mealShares, null, null);
    }
}

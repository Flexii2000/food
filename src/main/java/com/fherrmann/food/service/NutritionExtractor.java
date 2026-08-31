package com.fherrmann.food.service;

import com.fherrmann.food.model.Dish;
import com.fherrmann.food.model.Nutrients;

import java.util.List;

/**
 * Wandelt eine Freitext-Beschreibung in ein erfassbares Gericht.
 *
 * <p>Eigene Schnittstelle, damit {@link FoodService} nichts vom Sprachmodell
 * dahinter weiss und in Tests eine feste Antwort eingesetzt werden kann - eine
 * Testsuite, die fuer jeden Lauf echte API-Aufrufe braucht, ist keine.
 */
public interface NutritionExtractor {

    /** Ob die Schnellerfassung ueberhaupt einsatzbereit ist (API-Schluessel gesetzt). */
    boolean isAvailable();

    /**
     * @param text    die Beschreibung, so wie sie eingetippt wurde
     * @param targets die Tagesziele - Kontext dafuer, wie gross eine Portion
     *                bei diesem Nutzer plausibel ist
     * @param known   die bereits gespeicherten Gerichte, damit Bekanntes
     *                wiedererkannt statt als Beinahe-Dublette neu angelegt wird
     */
    ExtractedDish extract(String text, Nutrients targets, List<Dish> known);
}

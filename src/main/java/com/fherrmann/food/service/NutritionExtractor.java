package com.fherrmann.food.service;

import com.fherrmann.food.model.Dish;
import com.fherrmann.food.model.Nutrients;

import java.nio.file.Path;
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
     * @param text    die Beschreibung, so wie sie eingetippt wurde - mit Foto
     *                darf sie leer sein
     * @param photo   ein Foto der Mahlzeit als Datei, die der Agent ansehen
     *                darf, oder {@code null}
     * @param targets die Tagesziele - Kontext dafuer, wie gross eine Portion
     *                bei diesem Nutzer plausibel ist
     * @param known   die bereits gespeicherten Gerichte, damit Bekanntes
     *                wiedererkannt statt als Beinahe-Dublette neu angelegt wird
     * @param detailed ob diese Person auch die Detailwerte erfasst (gesaettigte
     *                Fettsaeuren, Zucker, Ballaststoffe, Salz) - nur dann danach fragen
     */
    ExtractedDish extract(String text, Path photo, Nutrients targets, List<Dish> known, boolean detailed);
}

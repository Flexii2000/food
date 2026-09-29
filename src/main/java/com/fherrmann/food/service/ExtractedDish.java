package com.fherrmann.food.service;

import com.fherrmann.food.model.Meal;
import com.fherrmann.food.model.Micronutrient;

import java.util.List;
import java.util.Map;

/**
 * Das Ergebnis der Freitext-Auswertung, bevor daraus ein Gericht und ein
 * Eintrag werden. Naehrwerte immer je 100 g - das ist die Basis, in der die App
 * rechnet, und die einzige, aus der sich eine beliebige Menge ableiten laesst.
 *
 * @param name      Titel des Gerichts
 * @param kcal      kcal je 100 g
 * @param proteinG  Eiweiss je 100 g
 * @param carbsG    Kohlenhydrate je 100 g
 * @param fatG      Fett je 100 g
 * @param grams     wie viel davon gegessen wurde
 * @param portionG  uebliche Portionsgroesse, die am Gericht hinterlegt wird
 * @param lookedUpFields Feldnamen, die im Netz nachgeschlagen wurden
 * @param estimatedFields Feldnamen, die geschaetzt wurden. Was in keiner der
 *                  beiden Listen steht, stand als Zahl im Text
 * @param note      ein Satz zur Herleitung, auf Deutsch
 * @param meal      die aus dem Text erschlossene Mahlzeit, oder {@code null}
 * @param saturatedFatG gesaettigte Fettsaeuren je 100 g, nur bei detaillierter Erfassung
 * @param sugarG    Zucker je 100 g, dito
 * @param fiberG    Ballaststoffe je 100 g, dito
 * @param saltG     Salz je 100 g, dito
 * @param micros    Mikronaehrstoffe je 100 g, nur fuer Personen, die sie erfassen;
 *                  leer, wenn der Agent keine liefert oder niemand danach gefragt hat
 * @param vegan     ob das Gericht vegan ist, oder {@code null}, wenn der Agent es
 *                  offen laesst oder niemand danach gefragt hat. Herkunft ueber den
 *                  Feldnamen {@code vegan} in denselben beiden Listen
 */
public record ExtractedDish(
        String name,
        double kcal,
        double proteinG,
        double carbsG,
        double fatG,
        double grams,
        Double portionG,
        List<String> lookedUpFields,
        List<String> estimatedFields,
        String note,
        Meal meal,
        Double saturatedFatG,
        Double sugarG,
        Double fiberG,
        Double saltG,
        Map<String, Double> micros,
        Boolean vegan) {

    public ExtractedDish {
        lookedUpFields = lookedUpFields == null ? List.of() : List.copyOf(lookedUpFields);
        estimatedFields = estimatedFields == null ? List.of() : List.copyOf(estimatedFields);
        micros = Micronutrient.ordered(micros);
    }

    /** Ohne Kennzeichen vegan. */
    public ExtractedDish(String name, double kcal, double proteinG, double carbsG, double fatG, double grams,
                         Double portionG, List<String> lookedUpFields, List<String> estimatedFields,
                         String note, Meal meal, Double saturatedFatG, Double sugarG, Double fiberG, Double saltG,
                         Map<String, Double> micros) {
        this(name, kcal, proteinG, carbsG, fatG, grams, portionG, lookedUpFields, estimatedFields, note, meal,
                saturatedFatG, sugarG, fiberG, saltG, micros, null);
    }

    /** Mit Detailwerten, ohne Mikronaehrstoffe. */
    public ExtractedDish(String name, double kcal, double proteinG, double carbsG, double fatG, double grams,
                         Double portionG, List<String> lookedUpFields, List<String> estimatedFields,
                         String note, Meal meal, Double saturatedFatG, Double sugarG, Double fiberG, Double saltG) {
        this(name, kcal, proteinG, carbsG, fatG, grams, portionG, lookedUpFields, estimatedFields, note, meal,
                saturatedFatG, sugarG, fiberG, saltG, null, null);
    }

    /** Ohne Detailwerte - so antwortet der Agent, wenn niemand danach fragt. */
    public ExtractedDish(String name, double kcal, double proteinG, double carbsG, double fatG, double grams,
                         Double portionG, List<String> lookedUpFields, List<String> estimatedFields,
                         String note, Meal meal) {
        this(name, kcal, proteinG, carbsG, fatG, grams, portionG, lookedUpFields, estimatedFields, note, meal,
                null, null, null, null, null, null);
    }
}

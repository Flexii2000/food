package com.fherrmann.food.service;

import com.fherrmann.food.model.Meal;

import java.util.List;

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
        Meal meal) {

    public ExtractedDish {
        lookedUpFields = lookedUpFields == null ? List.of() : List.copyOf(lookedUpFields);
        estimatedFields = estimatedFields == null ? List.of() : List.copyOf(estimatedFields);
    }
}

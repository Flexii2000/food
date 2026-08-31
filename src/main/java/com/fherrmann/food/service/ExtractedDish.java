package com.fherrmann.food.service;

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
 * @param estimated true, wenn die Werte geschaetzt statt aus dem Text abgelesen sind
 * @param note      ein Satz zur Herleitung, auf Deutsch
 */
public record ExtractedDish(
        String name,
        double kcal,
        double proteinG,
        double carbsG,
        double fatG,
        double grams,
        Double portionG,
        boolean estimated,
        String note) {
}

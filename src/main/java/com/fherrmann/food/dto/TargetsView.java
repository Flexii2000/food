package com.fherrmann.food.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fherrmann.food.model.FoodData;
import com.fherrmann.food.model.Nutrients;

import java.util.Map;

/**
 * Die Tagesziele, wie die Oberflaechen sie bekommen: die Felder von {@link Nutrients}
 * und dazu der Eiweiß-Erhalt.
 *
 * <p>Bewusst flach und eine Obermenge des bisherigen JSON: wer {@code targets} als
 * {@code Nutrients} liest (aeltere Apps, Weight Tracker, Habits), findet jede Zahl an
 * ihrem Platz und uebersieht die zwei neuen Felder einfach.
 *
 * <p>Die Farbe des Eiweißwerts haengt an beiden Grenzen: unter {@code proteinMinG} rot,
 * ab {@code proteinMinG} gelb, ab {@code proteinG} gruen.
 *
 * @param proteinMinG       der Erhalt in Gramm - der gespeicherte oder, solange keiner
 *                          gespeichert ist, die Vorgabe aus dem Eiweißziel
 * @param proteinMinDefault ob {@code proteinMinG} die Vorgabe ist. Die Oberflaechen
 *                          schicken den Wert dann beim Speichern nicht mit, sonst wuerde
 *                          aus der Vorgabe eine feste Zahl, die dem Ziel nicht mehr folgt
 */
public record TargetsView(
        double kcal,
        double proteinG,
        double carbsG,
        double fatG,
        @JsonInclude(JsonInclude.Include.NON_NULL) Double saturatedFatG,
        @JsonInclude(JsonInclude.Include.NON_NULL) Double sugarG,
        @JsonInclude(JsonInclude.Include.NON_NULL) Double fiberG,
        @JsonInclude(JsonInclude.Include.NON_NULL) Double saltG,
        double proteinMinG,
        boolean proteinMinDefault,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Double> micros) {

    /**
     * @param targets          die Ziele, schon gerundet und mit den Mikro-Zielen der Person
     * @param storedProteinMin der gespeicherte Erhalt, oder {@code null} fuer die Vorgabe
     */
    public static TargetsView of(Nutrients targets, Double storedProteinMin) {
        boolean byDefault = storedProteinMin == null;
        double min = byDefault
                ? FoodData.defaultProteinMin(targets.proteinG())
                // Ueber dem Ziel kann der Erhalt nie liegen - der Dienst speichert ihn so
                // nicht, und eine von Hand verstellte Datei soll keine Oberflaeche verwirren.
                : Math.min(storedProteinMin, targets.proteinG());
        return new TargetsView(targets.kcal(), targets.proteinG(), targets.carbsG(), targets.fatG(),
                targets.saturatedFatG(), targets.sugarG(), targets.fiberG(), targets.saltG(),
                Math.round(min * 10.0) / 10.0, byDefault, targets.micros());
    }

    /** Das Mikro-Ziel zum Schluessel, oder {@code null} ohne Ziel. */
    public Double micro(String key) {
        return micros == null ? null : micros.get(key);
    }
}

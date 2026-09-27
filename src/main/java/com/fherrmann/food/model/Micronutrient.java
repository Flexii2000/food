package com.fherrmann.food.model;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Die Mikronaehrstoffe, die eine Person zusaetzlich erfassen kann - in der Reihenfolge,
 * in der sie ueberall stehen: erst die sieben Vitamine, dann die sieben Mineralstoffe.
 *
 * <p>Der Schluessel ist der Name im JSON und traegt die Einheit wie {@code proteinG}
 * oder {@code saltG}: {@code Mg} Milligramm, {@code Ug} Mikrogramm. Vitamin A zaehlt in
 * Retinol-Aktivitaets-Aequivalenten, Folat in Folat-Aequivalenten - so, wie die DGE ihre
 * Referenzwerte angibt, damit Summe und Ziel dieselbe Groesse meinen.
 *
 * <p>Die Vorgabe fuer die Tagesziele sind die DGE-Referenzwerte fuer Maenner von 25 bis
 * unter 51 Jahren, geprueft am 2026-09-27 auf dge.de/wissenschaft/referenzwerte/. Wo die
 * DGE nur einen Schaetzwert hat, steht der. Vitamin D gilt ohne Eigensynthese in der Haut,
 * Zink fuer hohe Phytatzufuhr - die DGE nimmt sie an, sobald die Proteinquellen
 * vorrangig pflanzlich sind.
 */
public enum Micronutrient {

    VITAMIN_A("vitaminAUg", Unit.MICROGRAM, 850),     // Empfehlung 2020
    VITAMIN_D("vitaminDUg", Unit.MICROGRAM, 20),      // Schaetzwert 2012
    VITAMIN_E("vitaminEMg", Unit.MILLIGRAM, 8),       // Schaetzwert 2024
    VITAMIN_C("vitaminCMg", Unit.MILLIGRAM, 110),     // 2015
    VITAMIN_B2("vitaminB2Mg", Unit.MILLIGRAM, 1.4),   // 2015
    VITAMIN_B12("vitaminB12Ug", Unit.MICROGRAM, 4.0), // Schaetzwert 2018
    FOLATE("folateUg", Unit.MICROGRAM, 300),          // 2015
    CALCIUM("calciumMg", Unit.MILLIGRAM, 1000),       // 2013
    MAGNESIUM("magnesiumMg", Unit.MILLIGRAM, 350),    // Schaetzwert 2021
    POTASSIUM("potassiumMg", Unit.MILLIGRAM, 4000),   // Schaetzwert 2016
    IRON("ironMg", Unit.MILLIGRAM, 11),               // 2023
    ZINC("zincMg", Unit.MILLIGRAM, 16),               // 2019
    IODINE("iodineUg", Unit.MICROGRAM, 150),          // 2025
    SELENIUM("seleniumUg", Unit.MICROGRAM, 70);       // Schaetzwert 2015

    /**
     * Die Einheit samt Obergrenze. Die Grenze ist das Gegenstueck von 10 g - so viel eines
     * Vitamins oder Minerals steckt in 100 g von nichts, das man isst. Sie faengt nur
     * Tippfehler und Einheitenverwechslungen ab (mg statt µg ist Faktor 1000).
     */
    public enum Unit {
        MILLIGRAM(10_000),
        MICROGRAM(10_000_000);

        private final double limit;

        Unit(double limit) {
            this.limit = limit;
        }
    }

    /** Alle Schluessel in der festen Reihenfolge. */
    public static final List<String> KEYS = Arrays.stream(values()).map(Micronutrient::key).toList();

    private final String key;
    private final Unit unit;
    private final double defaultTarget;

    Micronutrient(String key, Unit unit, double defaultTarget) {
        this.key = key;
        this.unit = unit;
        this.defaultTarget = defaultTarget;
    }

    public String key() {
        return key;
    }

    public Unit unit() {
        return unit;
    }

    /** Hoechstwert je 100 g und fuer ein Tagesziel, siehe {@link Unit}. */
    public double limit() {
        return unit.limit;
    }

    public double defaultTarget() {
        return defaultTarget;
    }

    public static Optional<Micronutrient> byKey(String key) {
        return Arrays.stream(values()).filter(m -> m.key.equals(key)).findFirst();
    }

    /** Die DGE-Vorgabe fuer alle 14 Tagesziele. */
    public static Map<String, Double> defaultTargets() {
        Map<String, Double> targets = new LinkedHashMap<>();
        for (Micronutrient m : values()) {
            targets.put(m.key, m.defaultTarget);
        }
        return Collections.unmodifiableMap(targets);
    }

    /**
     * Nur die bekannten Schluessel mit Wert, in der festen Reihenfolge. Die Reihenfolge
     * haelt das JSON stabil; was unbekannt ist oder keinen Wert hat, ist keine Angabe.
     *
     * @return nie {@code null} - ohne Angabe eine leere Liste
     */
    public static Map<String, Double> ordered(Map<String, Double> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, Double> ordered = new LinkedHashMap<>();
        for (String key : KEYS) {
            Double value = values.get(key);
            if (value != null) {
                ordered.put(key, value);
            }
        }
        return Collections.unmodifiableMap(ordered);
    }
}

package com.fherrmann.food.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The four nutrition figures this app tracks for everyone: calories and the three
 * macronutrients - plus, optionally, the rest of the EU nutrition label.
 *
 * <p>Used for three different things - the per-100 g values of a dish, the daily targets,
 * and a computed total - because they are the same shape and the same arithmetic applies
 * to all of them.
 *
 * <p><b>Die Detailwerte</b> (gesaettigte Fettsaeuren, Zucker, Ballaststoffe, Salz) sind
 * die uebrigen Zeilen der Naehrwerttabelle auf jeder Packung in der EU. Wer sie erfasst,
 * ist je Person eingestellt ({@code food.detailed-people}); bei allen anderen sind sie
 * {@code null} und erscheinen im JSON gar nicht - Felix' Tagebuch und alles, was es liest
 * (iPhone-App, Weight Tracker, Habits), sieht dieselben vier Zahlen wie immer.
 *
 * @param kcal          kilocalories
 * @param proteinG      protein in grams
 * @param carbsG        carbohydrates in grams
 * @param fatG          fat in grams
 * @param saturatedFatG davon gesaettigte Fettsaeuren in Gramm, oder {@code null}
 * @param sugarG        davon Zucker in Gramm, oder {@code null}
 * @param fiberG        Ballaststoffe in Gramm, oder {@code null}
 * @param saltG         Salz in Gramm, oder {@code null}
 */
public record Nutrients(
        double kcal,
        double proteinG,
        double carbsG,
        double fatG,
        @JsonInclude(JsonInclude.Include.NON_NULL) Double saturatedFatG,
        @JsonInclude(JsonInclude.Include.NON_NULL) Double sugarG,
        @JsonInclude(JsonInclude.Include.NON_NULL) Double fiberG,
        @JsonInclude(JsonInclude.Include.NON_NULL) Double saltG) {

    public static final Nutrients ZERO = new Nutrients(0, 0, 0, 0);

    /** Die Feldnamen der Detailwerte, wie sie im JSON stehen. */
    public static final List<String> DETAIL_FIELDS = List.of("saturatedFatG", "sugarG", "fiberG", "saltG");

    /** Nur die vier Grundwerte - so sieht jede Zahl aus, die nicht aus einem detaillierten Tagebuch kommt. */
    public Nutrients(double kcal, double proteinG, double carbsG, double fatG) {
        this(kcal, proteinG, carbsG, fatG, null, null, null, null);
    }

    /** Ob irgendein Detailwert gesetzt ist. */
    public boolean hasDetails() {
        return saturatedFatG != null || sugarG != null || fiberG != null || saltG != null;
    }

    /** Der Detailwert zum Feldnamen aus {@link #DETAIL_FIELDS}. */
    public Double detail(String field) {
        return switch (field) {
            case "saturatedFatG" -> saturatedFatG;
            case "sugarG" -> sugarG;
            case "fiberG" -> fiberG;
            case "saltG" -> saltG;
            default -> throw new IllegalArgumentException("Unbekannter Detailwert: " + field);
        };
    }

    /** All values scaled by the same factor (used to turn per-100 g into a portion). */
    public Nutrients scaled(double factor) {
        return new Nutrients(kcal * factor, proteinG * factor, carbsG * factor, fatG * factor,
                times(saturatedFatG, factor), times(sugarG, factor), times(fiberG, factor), times(saltG, factor));
    }

    /**
     * Summe zweier Stände. Ein Detailwert, den nur eine Seite hat, geht mit seinem Wert
     * ein - die Summe ist dann eine Untergrenze, und das muss der Aufrufer sagen
     * (siehe {@code DaySummary.detailGaps}); eine Luecke als null zu behandeln hiesse,
     * einen ganzen Tag Zucker wegen eines einzigen Apfels ohne Angabe zu verlieren.
     */
    public Nutrients plus(Nutrients other) {
        if (other == null) {
            return this;
        }
        return new Nutrients(
                kcal + other.kcal,
                proteinG + other.proteinG,
                carbsG + other.carbsG,
                fatG + other.fatG,
                sum(saturatedFatG, other.saturatedFatG),
                sum(sugarG, other.sugarG),
                sum(fiberG, other.fiberG),
                sum(saltG, other.saltG));
    }

    /**
     * Difference to a goal, per value. Negative means the goal has been exceeded.
     * Detailwerte haben kein Ziel - der Rest dazu bleibt leer.
     */
    public Nutrients minus(Nutrients other) {
        if (other == null) {
            return this;
        }
        return new Nutrients(
                kcal - other.kcal,
                proteinG - other.proteinG,
                carbsG - other.carbsG,
                fatG - other.fatG);
    }

    /**
     * Rounded to one decimal - stored values come from user input, totals from summation.
     * Die Detailwerte auf zwei: Salz steht auf der Packung als "0,03 g", und eine Stelle
     * machte daraus 0,0.
     */
    public Nutrients rounded() {
        return new Nutrients(round(kcal), round(proteinG), round(carbsG), round(fatG),
                round2(saturatedFatG), round2(sugarG), round2(fiberG), round2(saltG));
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static Double round2(Double value) {
        return value == null ? null : Math.round(value * 100.0) / 100.0;
    }

    private static Double times(Double value, double factor) {
        return value == null ? null : value * factor;
    }

    private static Double sum(Double a, Double b) {
        if (a == null) {
            return b;
        }
        return b == null ? a : a + b;
    }
}

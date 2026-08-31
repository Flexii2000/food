package com.fherrmann.food.model;

/**
 * The four nutrition figures this app tracks, deliberately no more: calories and the
 * three macronutrients. Used for three different things - the per-100 g values of a
 * dish, the daily targets, and a computed total - because they are the same shape and
 * the same arithmetic applies to all of them.
 *
 * @param kcal     kilocalories
 * @param proteinG protein in grams
 * @param carbsG   carbohydrates in grams
 * @param fatG     fat in grams
 */
public record Nutrients(double kcal, double proteinG, double carbsG, double fatG) {

    public static final Nutrients ZERO = new Nutrients(0, 0, 0, 0);

    /** All four values scaled by the same factor (used to turn per-100 g into a portion). */
    public Nutrients scaled(double factor) {
        return new Nutrients(kcal * factor, proteinG * factor, carbsG * factor, fatG * factor);
    }

    public Nutrients plus(Nutrients other) {
        if (other == null) {
            return this;
        }
        return new Nutrients(
                kcal + other.kcal,
                proteinG + other.proteinG,
                carbsG + other.carbsG,
                fatG + other.fatG);
    }

    /** Difference to a goal, per value. Negative means the goal has been exceeded. */
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

    /** Rounded to one decimal - stored values come from user input, totals from summation. */
    public Nutrients rounded() {
        return new Nutrients(round(kcal), round(proteinG), round(carbsG), round(fatG));
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}

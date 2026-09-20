package com.fherrmann.food.dto;

import java.time.LocalDate;

/**
 * Gleitendes kcal-Mittel fuer einen Tag: das Mittel der Tagessummen im
 * zentrierten Fenster (drei Tage davor, der Tag, drei danach) ueber die
 * abgeschlossenen Tage, an denen etwas eingetragen ist - Tage ohne Eintrag
 * sind unbekannt und zaehlen nicht mit, weder als null noch als Luecke, und
 * der laufende Tag sowie vorerfasste kuenftige Tage bleiben draussen, weil
 * ihre Summe noch waechst. {@code days} sagt, wie viele Tage eingegangen sind;
 * {@code complete} ist falsch, solange das Fenster bis heute oder darueber
 * hinaus reicht - am aktuellen Rand ist der Wert vorlaeufig, die Oberflaechen
 * zeichnen ihn gepunktet, wie das Gewichtsmittel.
 */
public record DayAverage(LocalDate date, double kcal, int days, boolean complete) {
}

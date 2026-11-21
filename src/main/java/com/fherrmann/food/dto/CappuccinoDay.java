package com.fherrmann.food.dto;

import com.fherrmann.food.model.Nutrients;

/**
 * Der Cappuccino-Zaehler eines Tages (siehe {@link com.fherrmann.food.service.CappuccinoCounter}).
 *
 * @param dishId das Cappuccino-Gericht, oder {@code null}, solange es keins gibt - dann
 *               legt das erste "+" eins an
 * @param name   sein Name
 * @param count  Cappuccinos an dem Tag: alle Eintraege des Gerichts zusammen, in Portionen,
 *               gerundet - ein frueher eingetragener doppelter zaehlt als zwei
 * @param budget so viele gehen aufs eigene Budget ({@code n/budget} in der Anzeige)
 * @param portionG ein Cappuccino in Gramm (die Portion des Gerichts) - so viel traegt ein "+" ein
 * @param perCup Naehrwerte eines Cappuccinos (eine Portion)
 * @param bonus  um so viel ist das Ziel des Tages groesser: kcal, Kohlenhydrate und Fett der
 *               Cappuccinos bis zum Budget. Eiweiß bleibt 0 - es ist ein Mindestziel.
 *               {@code remaining} im Tag rechnet das schon ein, {@code targets} nicht
 */
public record CappuccinoDay(String dishId, String name, int count, int budget, double portionG, Nutrients perCup, Nutrients bonus) {
}

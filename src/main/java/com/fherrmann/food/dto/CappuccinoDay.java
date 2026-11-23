package com.fherrmann.food.dto;

import com.fherrmann.food.model.Nutrients;

/**
 * Der Cappuccino-Zaehler eines Tages (siehe {@link com.fherrmann.food.service.CappuccinoCounter}).
 *
 * <p>Seit 23.11.2026 ein eigenes Budget wie eine Mahlzeit: {@code share} vom kcal-Tagesziel,
 * die vier Mahlzeiten haben den Rest. Das Tagesziel waechst nicht mehr um die Cappuccinos, und
 * {@code remaining} im Tag ist wieder schlicht {@code targets - consumed}. Was die Cappuccinos
 * zusammen haben, rechnen die Oberflaechen aus den Eintraegen des Gerichts - wie die
 * Teilsummen der Mahlzeiten -, und diese Eintraege zaehlen dort nicht mehr zu ihrer Mahlzeit.
 *
 * @param dishId   das Cappuccino-Gericht, oder {@code null}, solange es keins gibt - dann
 *                 legt das erste "+" eins an
 * @param name     sein Name
 * @param count    Cappuccinos an dem Tag: alle Eintraege des Gerichts zusammen, in Portionen,
 *                 gerundet - ein frueher eingetragener doppelter zaehlt als zwei
 * @param portionG ein Cappuccino in Gramm (die Portion des Gerichts) - so viel traegt ein "+" ein
 * @param perCup   Naehrwerte eines Cappuccinos (eine Portion)
 * @param share    Anteil am kcal-Tagesziel (0.1 = 10 %), wie die Anteile der Mahlzeiten
 * @param target   das kcal-Budget daraus: Tagesziel mal {@code share}, wie {@code mealTargets}
 */
public record CappuccinoDay(String dishId, String name, int count, double portionG, Nutrients perCup,
                            double share, double target) {
}

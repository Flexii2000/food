package com.fherrmann.food.model;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Die Rechnung mit den Mikronaehrstoffen - dieselbe Bauart wie bei den Detailwerten. */
class NutrientsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static Nutrients withMicros(Map<String, Double> micros) {
        return new Nutrients(100, 10, 10, 1, null, null, null, null, micros);
    }

    @Test
    void microsScaleWithTheAmount() {
        Nutrients per100g = withMicros(Map.of("vitaminCMg", 12.0, "vitaminB12Ug", 0.4));
        Nutrients portion = per100g.scaled(2.5);
        assertThat(portion.micros()).containsExactly(Map.entry("vitaminCMg", 30.0), Map.entry("vitaminB12Ug", 1.0));
    }

    /**
     * Was nur eine Seite hat, geht mit seinem Wert ein - die Summe ist dann eine
     * Untergrenze (das sagt der Tag ueber microGaps), und eine Luecke loescht nicht
     * den ganzen Rest.
     */
    @Test
    void aSumOfMicrosIsAPartialSum() {
        Nutrients a = withMicros(Map.of("vitaminCMg", 10.0, "ironMg", 1.5));
        Nutrients b = withMicros(Map.of("vitaminCMg", 5.0, "calciumMg", 120.0));
        Nutrients none = new Nutrients(50, 1, 1, 1);

        Nutrients sum = a.plus(b).plus(none);

        assertThat(sum.micros()).containsExactly(
                Map.entry("vitaminCMg", 15.0), Map.entry("calciumMg", 120.0), Map.entry("ironMg", 1.5));
        assertThat(none.plus(none).micros()).isNull();
        assertThat(Nutrients.ZERO.plus(a).micros()).isEqualTo(a.micros());
    }

    /** Fuer Mikronaehrstoffe gibt es keinen Rest - nur die Zielerreichung. */
    @Test
    void theDifferenceToAGoalHasNoMicros() {
        Nutrients goal = withMicros(Map.of("vitaminCMg", 110.0));
        Nutrients eaten = withMicros(Map.of("vitaminCMg", 30.0));
        assertThat(goal.minus(eaten).micros()).isNull();
        assertThat(goal.minus(eaten).kcal()).isEqualTo(0.0);
    }

    @Test
    void microsAreRoundedToTwoDecimals() {
        Nutrients rounded = withMicros(Map.of("vitaminDUg", 0.0349, "potassiumMg", 1234.567)).rounded();
        assertThat(rounded.micros()).containsExactly(Map.entry("vitaminDUg", 0.03), Map.entry("potassiumMg", 1234.57));
    }

    /** Die Reihenfolge ist die feste, egal wie die Werte ankamen; Unbekanntes und Leeres faellt weg. */
    @Test
    void onlyKnownKeysWithAValueStayInTheirFixedOrder() {
        Map<String, Double> given = new LinkedHashMap<>();
        given.put("seleniumUg", 5.0);
        given.put("notAVitamin", 1.0);
        given.put("vitaminAUg", 80.0);
        given.put("zincMg", null);
        given.put("vitaminEMg", 0.0);

        Nutrients n = withMicros(given);

        assertThat(n.micros().keySet()).containsExactly("vitaminAUg", "vitaminEMg", "seleniumUg");
        // Eine 0 ist eine Angabe und bleibt stehen.
        assertThat(n.micro("vitaminEMg")).isEqualTo(0.0);
        assertThat(n.micro("zincMg")).isNull();
        assertThat(n.hasMicros()).isTrue();
    }

    /** Ohne einen einzigen Wert fehlt das Feld im JSON ganz - wie bisher bei allen ohne Mikronaehrstoffe. */
    @Test
    void withoutAnyMicroTheJsonLooksAsBefore() throws Exception {
        assertThat(JSON.writeValueAsString(withMicros(Map.of())))
                .isEqualTo("{\"kcal\":100.0,\"proteinG\":10.0,\"carbsG\":10.0,\"fatG\":1.0}");
        assertThat(JSON.writeValueAsString(new Nutrients(100, 10, 10, 1)))
                .isEqualTo("{\"kcal\":100.0,\"proteinG\":10.0,\"carbsG\":10.0,\"fatG\":1.0}");
        assertThat(withMicros(Map.of("unknownUg", 3.0)).hasMicros()).isFalse();
    }

    @Test
    void microsReadAndWriteAsOneObject() throws Exception {
        String json = JSON.writeValueAsString(withMicros(Map.of("ironMg", 2.1, "folateUg", 60.0)));
        assertThat(json).isEqualTo(
                "{\"kcal\":100.0,\"proteinG\":10.0,\"carbsG\":10.0,\"fatG\":1.0,\"micros\":{\"folateUg\":60.0,\"ironMg\":2.1}}");
        assertThat(JSON.readValue(json, Nutrients.class)).isEqualTo(withMicros(Map.of("ironMg", 2.1, "folateUg", 60.0)));
    }

    @Test
    void theDefaultTargetsAreTheDgeValuesForAllFourteen() {
        Map<String, Double> defaults = Micronutrient.defaultTargets();
        assertThat(defaults.keySet()).containsExactlyElementsOf(Micronutrient.KEYS);
        assertThat(defaults).containsEntry("vitaminAUg", 850.0).containsEntry("vitaminDUg", 20.0)
                .containsEntry("vitaminEMg", 8.0).containsEntry("vitaminCMg", 110.0)
                .containsEntry("vitaminB2Mg", 1.4).containsEntry("vitaminB12Ug", 4.0)
                .containsEntry("folateUg", 300.0).containsEntry("calciumMg", 1000.0)
                .containsEntry("magnesiumMg", 350.0).containsEntry("potassiumMg", 4000.0)
                .containsEntry("ironMg", 11.0).containsEntry("zincMg", 16.0)
                .containsEntry("iodineUg", 150.0).containsEntry("seleniumUg", 70.0);
    }
}

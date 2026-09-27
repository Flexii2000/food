package com.fherrmann.food.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Wer Mikronaehrstoffe erfasst (siehe {@link com.fherrmann.food.model.Micronutrient}).
 *
 * <p>Strenger als die Detailwerte: nur fuer diese Personen speichert der Dienst Werte
 * und Ziele, liefert die DGE-Vorgabe und fragt die Schnellerfassung danach. Bei allen
 * anderen wird {@code micros} in jeder Anfrage uebergangen, und ausser
 * {@code micronutrients: false} in {@code /features} bekommt keine Antwort ein neues
 * Feld - Felix' Tagebuch und alles, was es liest, bleibt, wie es war.
 *
 * <p>Form in der Umgebung: {@code FOOD_MICRONUTRIENTS=torben}; {@code *} heisst alle,
 * leer heisst niemand.
 */
@Component
public class MicronutrientTracking {

    private final PeopleSelection people;

    public MicronutrientTracking(@Value("${food.micronutrient-people:}") String configured) {
        this.people = PeopleSelection.parse(configured, Set.of(), "food.micronutrient-people");
    }

    /** Niemand - fuer Tests und Aufrufer ohne Spring. */
    public static MicronutrientTracking none() {
        return new MicronutrientTracking("");
    }

    public boolean isEnabled(String user) {
        return people.contains(user);
    }
}

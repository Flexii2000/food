package com.fherrmann.food.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Wer die Detailwerte erfasst (gesaettigte Fettsaeuren, Zucker, Ballaststoffe, Salz).
 *
 * <p>Eine Vorliebe je Person, keine Pflicht: Torben will die ganze Naehrwerttabelle
 * sehen, Felix bleibt bei kcal und den drei Makros. Die Oberflaechen fragen das ueber
 * {@code /api/food/features} ab und zeigen die Felder nur dann; die Schnellerfassung
 * laesst sie nur dann mitschaetzen. Gespeichert wird, was ankommt - der Dienst
 * lehnt Detailwerte bei niemandem ab, er bietet sie nur nicht jedem an.
 *
 * <p>Form in der Umgebung: {@code FOOD_DETAILED_NUTRIENTS=torben}; {@code *} heisst
 * alle, leer heisst niemand.
 */
@Component
public class DetailedNutrition {

    private final PeopleSelection people;

    public DetailedNutrition(@Value("${food.detailed-people:}") String configured) {
        this.people = PeopleSelection.parse(configured, Set.of(), "food.detailed-people");
    }

    /** Niemand - fuer Tests und Aufrufer ohne Spring. */
    public static DetailedNutrition none() {
        return new DetailedNutrition("");
    }

    public boolean isDetailed(String user) {
        return people.contains(user);
    }
}

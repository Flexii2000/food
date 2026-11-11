package com.fherrmann.food.dto;

import java.util.List;

/**
 * Ein Rezept der Einkaufsliste, das als Gericht in die Tagebuecher mehrerer Personen
 * gelegt wird ({@code POST /api/internal/recipe-dishes}, Healthy-Vertrag §11.3).
 *
 * @param persons      bei wem - Namen, wie die Einkaufsliste sie kennt. Wer hier keine
 *                     Healthy-Person ist, bekommt {@code not-linked} statt eines Fehlers
 * @param dish         Name, Naehrwerte je 100 g und Portion; alles andere daran
 *                     (Detailwerte, Mikronaehrstoffe, vegan) bleibt unbeachtet - das
 *                     gehoert der Person
 * @param previousName der Name des Gerichts vor dem Umbenennen des Rezepts, sonst
 *                     {@code null} - so wird das vorhandene umbenannt statt ein zweites
 *                     angelegt
 */
public record RecipeDishesRequest(List<String> persons, DishRequest dish, String previousName) {
}

package com.fherrmann.food.dto;

import java.util.Map;

/**
 * Eine Mitteilung, die ein anderer Dienst auf dem Server an die Geraete einer Person
 * schicken laesst ({@code POST /api/internal/push}, Healthy-Vertrag §11.3).
 *
 * @param person an wen - der Name einer Healthy-Person
 * @param kind   die Art (§11.5), z. B. {@code friend-nudge}; die Apps lesen daran ab,
 *               wohin ein Tipp fuehrt
 * @param title  Titel der Mitteilung
 * @param body   Text der Mitteilung; darf leer sein
 * @param data   weitere Schluessel fuer die Nutzlast, oder nichts
 */
public record InternalPushRequest(String person, String kind, String title, String body,
                                  Map<String, String> data) {
}

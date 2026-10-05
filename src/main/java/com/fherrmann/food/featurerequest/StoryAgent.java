package com.fherrmann.food.featurerequest;

import com.fherrmann.food.featurerequest.FeatureRequestDtos.StoryCard;

/**
 * Entwirft aus einem Wunsch in eigenen Worten eine Story Card.
 *
 * <p>Eigene Schnittstelle, damit die Auftragsverwaltung nichts von der
 * Claude-Session dahinter weiss und Tests eine feste Antwort einsetzen koennen.
 */
public interface StoryAgent {

    /** Ob Entwuerfe auf diesem Server eingerichtet sind. Sonst schreibt die Person die Karte selbst. */
    boolean isAvailable();

    /**
     * @param author wer den Wunsch geschickt hat - Kontext fuer die Rolle in der User Story
     * @param app    fuer welche App der Wunsch ist
     * @param wish   der Wunsch, so wie er eingetippt wurde
     * @throws org.springframework.web.server.ResponseStatusException mit einer Meldung
     *         fuer die Oberflaeche, wenn kein Entwurf zustande kommt
     */
    StoryCard draft(String author, FeatureApp app, String wish);
}

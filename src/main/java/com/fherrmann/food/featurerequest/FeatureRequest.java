package com.fherrmann.food.featurerequest;

import java.time.Instant;
import java.util.List;

/**
 * Ein abgeschickter Wunsch: die Story Card, so wie die Person sie freigegeben hat,
 * und daneben ihr eigener Text.
 *
 * @param author       wer ihn abgeschickt hat - der Name zum Token
 * @param originalText was die Person selbst geschrieben hat. Bleibt neben der Karte
 *                     stehen, damit sich die Karte am Wortlaut messen laesst
 * @param todoId       die Unteraufgabe in Felix' To-Do, oder {@code null}, solange
 *                     sie noch nicht angelegt werden konnte - der Nachlauf in
 *                     {@link FeatureRequestTodos} holt das nach
 * @param doneAt       zuletzt gesehener Stand der Unteraufgabe. Nur der Rueckfall fuer
 *                     die Zeit, in der das To-Do nicht antwortet: massgeblich ist
 *                     das Brett
 */
public record FeatureRequest(
        String id,
        String author,
        Instant createdAt,
        String originalText,
        String title,
        String story,
        List<String> acceptanceCriteria,
        String todoId,
        Instant doneAt) {

    public FeatureRequest {
        acceptanceCriteria = acceptanceCriteria == null ? List.of() : List.copyOf(acceptanceCriteria);
    }

    public FeatureRequest withTodoId(String todoId) {
        return new FeatureRequest(id, author, createdAt, originalText, title, story, acceptanceCriteria,
                todoId, doneAt);
    }

    public FeatureRequest withDoneAt(Instant doneAt) {
        return new FeatureRequest(id, author, createdAt, originalText, title, story, acceptanceCriteria,
                todoId, doneAt);
    }
}

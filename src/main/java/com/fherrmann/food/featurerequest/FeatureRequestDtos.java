package com.fherrmann.food.featurerequest;

import java.time.Instant;
import java.util.List;

/** Was ueber die Leitung geht - bewusst beieinander, es sind nur kleine Huellen. */
public final class FeatureRequestDtos {

    private FeatureRequestDtos() {
    }

    /** Titel, User Story und Akzeptanzkriterien - der Entwurf und das, was freigegeben wird. */
    public record StoryCard(String title, String story, List<String> acceptanceCriteria) {
        public StoryCard {
            acceptanceCriteria = acceptanceCriteria == null ? List.of() : List.copyOf(acceptanceCriteria);
        }
    }

    /** Der Wunsch in eigenen Worten, aus dem Claude eine Karte entwirft - und fuer welche App. */
    public record DraftRequest(String text, String app) {
    }

    /**
     * Stand eines Entwurfs - wie bei der Schnellerfassung ein Auftrag im Hintergrund,
     * weil eine Claude-Session laenger dauert, als eine Anfrage am Draht haengen sollte.
     *
     * @param status {@code running}, {@code done} oder {@code failed}
     * @param card   der Entwurf, sobald {@code done}
     * @param error  die Meldung, sobald {@code failed}
     */
    public record DraftJob(String jobId, String status, StoryCard card, String error, long elapsedSeconds) {
        public static final String RUNNING = "running";
        public static final String DONE = "done";
        public static final String FAILED = "failed";
    }

    /** Die freigegebene Karte samt Originaltext; {@code app} leer heisst Healthy. */
    public record NewFeatureRequest(String app, String originalText, String title, String story,
                                    List<String> acceptanceCriteria) {
    }

    /**
     * Eine Anfrage, wie Liste und Kartenseite sie zeigen.
     *
     * @param status {@code open} oder {@code done} - erledigt, sobald Felix die
     *               Unteraufgabe abgehakt hat
     * @param inTodo ob die Unteraufgabe schon angelegt ist
     * @param url    die Kartenseite, auf die auch die Unteraufgabe zeigt
     */
    public record FeatureRequestView(
            String id,
            String author,
            String app,
            String appName,
            Instant createdAt,
            String title,
            String story,
            List<String> acceptanceCriteria,
            String originalText,
            String status,
            Instant doneAt,
            boolean inTodo,
            String url) {

        public static final String OPEN = "open";
        public static final String DONE = "done";
    }

    /**
     * Was die Seite dieser Person anbietet.
     *
     * @param owner    die Eigentuemerin sieht alle Anfragen, alle anderen ihre eigenen
     * @param drafting ob Claude entwirft - sonst oeffnet der Editor leer
     * @param apps     wofuer man sich etwas wuenschen kann, die erste ist vorgewaehlt
     */
    public record Features(String me, boolean owner, boolean drafting, List<AppOption> apps) {
    }

    public record AppOption(String id, String name) {
        static AppOption of(FeatureApp app) {
            return new AppOption(app.id(), app.displayName());
        }
    }
}

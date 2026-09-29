package com.fherrmann.food.featurerequest;

import com.fherrmann.food.featurerequest.FeatureRequestDtos.FeatureRequestView;
import com.fherrmann.food.featurerequest.FeatureRequestDtos.NewFeatureRequest;
import com.fherrmann.food.security.HealthUsers;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Die Regeln der Feature Requests: was eine Karte sein darf, wer welche sieht, und
 * wann sie erledigt ist.
 *
 * <p>Sichtbarkeit: die Eigentuemerin sieht alle Anfragen, jede andere Person nur
 * ihre eigenen. Eine fremde Anfrage sieht aus wie eine, die es nicht gibt - dass es
 * sie gibt, geht niemanden sonst etwas an.
 */
@Service
public class FeatureRequestService {

    static final int MAX_TITLE = 120;
    static final int MAX_STORY = 2000;
    static final int MAX_CRITERIA = 10;
    static final int MAX_CRITERION = 300;
    static final int MAX_ORIGINAL = 4000;

    private final FeatureRequestRepository repository;
    private final FeatureRequestTodos todos;
    private final HealthUsers users;
    private final Clock clock;

    public FeatureRequestService(FeatureRequestRepository repository, FeatureRequestTodos todos,
                                 HealthUsers users, Clock clock) {
        this.repository = repository;
        this.todos = todos;
        this.users = users;
        this.clock = clock;
    }

    /**
     * Speichert die freigegebene Karte und legt dann die Unteraufgabe an.
     *
     * <p>In dieser Reihenfolge: steht die Anfrage erst einmal in der Datei, kann ihr
     * ein Ausfall des To-Do nichts mehr anhaben - sie bekommt ihre Aufgabe dann eben
     * vom Nachlauf.
     */
    public FeatureRequestView create(String user, NewFeatureRequest request) {
        if (request == null) {
            throw badRequest("Die Karte fehlt.");
        }
        FeatureRequest saved = new FeatureRequest(
                UUID.randomUUID().toString(),
                user,
                Instant.now(clock),
                cleanOriginal(request.originalText()),
                cleanTitle(request.title()),
                cleanStory(request.story()),
                cleanCriteria(request.acceptanceCriteria()),
                null,
                null);
        repository.add(saved);
        FeatureRequest withTodo = todos.ensureTodo(saved);
        return view(withTodo, Optional.empty());
    }

    /** Die Anfragen, die diese Person sehen darf, die neueste zuerst - mit dem Stand aus dem To-Do. */
    public List<FeatureRequestView> list(String user) {
        List<FeatureRequest> visible = repository.all().stream()
                .filter(r -> canSee(user, r))
                .sorted(Comparator.comparing(FeatureRequest::createdAt).reversed())
                .toList();
        if (visible.isEmpty()) {
            return List.of();
        }
        Optional<TodoBoard> board = todos.board();
        return visible.stream().map(r -> view(r, board)).toList();
    }

    public FeatureRequestView get(String user, String id) {
        FeatureRequest request = repository.find(id)
                .filter(r -> canSee(user, r))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Diese Anfrage gibt es nicht."));
        return view(request, todos.board());
    }

    /**
     * Loescht eine Anfrage samt Unteraufgabe - nur die Eigentuemerin. Fuer alle anderen
     * sieht es aus wie eine Anfrage, die es nicht gibt, auch bei der eigenen: ein 403
     * verriete, dass es hier etwas zu loeschen gaebe, und die Seite bietet es ihnen
     * ohnehin nicht an.
     */
    public void delete(String user, String id) {
        if (!users.isOwner(user) || todos.remove(id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Diese Anfrage gibt es nicht.");
        }
    }

    private boolean canSee(String user, FeatureRequest request) {
        return users.isOwner(user) || request.author().equals(user);
    }

    private FeatureRequestView view(FeatureRequest request, Optional<TodoBoard> board) {
        Status status = status(request, board);
        return new FeatureRequestView(
                request.id(), request.author(), request.createdAt(), request.title(), request.story(),
                request.acceptanceCriteria(), request.originalText(), status.value(), status.doneAt(),
                request.todoId() != null, todos.cardUrl(request.id()));
    }

    /** Offen oder erledigt, und seit wann. */
    record Status(String value, Instant doneAt) {
    }

    /**
     * Der Stand einer Anfrage. Massgeblich ist die Unteraufgabe auf dem Brett: abgehakt
     * heisst erledigt, der Haken zurueck heisst wieder offen. Nur wenn das Brett sie
     * nicht zeigt - To-Do nicht erreichbar, Aufgabe geloescht - gilt der zuletzt
     * gesehene Stand.
     */
    static Status status(FeatureRequest request, Optional<TodoBoard> board) {
        Optional<TodoBoard.Todo> live = board.flatMap(b -> b.todo(request.todoId()));
        Instant doneAt = live.isPresent() ? live.get().doneAt() : request.doneAt();
        return new Status(doneAt == null ? FeatureRequestView.OPEN : FeatureRequestView.DONE, doneAt);
    }

    // MARK: - Pruefungen

    /** Eine Zeile: Umbrueche und Mehrfachleerzeichen aus dem Editor fallen zusammen. */
    private static String cleanTitle(String value) {
        String title = oneLine(value);
        if (title.isEmpty()) {
            throw badRequest("Die Karte braucht einen Titel.");
        }
        if (title.length() > MAX_TITLE) {
            throw badRequest("Der Titel darf höchstens " + MAX_TITLE + " Zeichen haben.");
        }
        return title;
    }

    private static String cleanStory(String value) {
        String story = value == null ? "" : value.strip();
        if (story.isEmpty()) {
            throw badRequest("Die Karte braucht eine User Story.");
        }
        if (story.length() > MAX_STORY) {
            throw badRequest("Die User Story darf höchstens " + MAX_STORY + " Zeichen haben.");
        }
        return story;
    }

    /** Leere Zeilen aus dem Editor fallen weg, statt die Karte abzulehnen. */
    private static List<String> cleanCriteria(List<String> values) {
        List<String> criteria = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                String criterion = oneLine(value);
                if (criterion.isEmpty()) {
                    continue;
                }
                if (criterion.length() > MAX_CRITERION) {
                    throw badRequest("Ein Akzeptanzkriterium darf höchstens " + MAX_CRITERION + " Zeichen haben.");
                }
                criteria.add(criterion);
            }
        }
        if (criteria.size() > MAX_CRITERIA) {
            throw badRequest("Höchstens " + MAX_CRITERIA + " Akzeptanzkriterien.");
        }
        return criteria;
    }

    private static String cleanOriginal(String value) {
        String original = value == null ? "" : value.strip();
        if (original.length() > MAX_ORIGINAL) {
            throw badRequest("Der Originaltext darf höchstens " + MAX_ORIGINAL + " Zeichen haben.");
        }
        return original;
    }

    private static String oneLine(String value) {
        return value == null ? "" : value.strip().replaceAll("\\s+", " ");
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}

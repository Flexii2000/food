package com.fherrmann.food.featurerequest;

import com.fherrmann.food.featurerequest.TodoClient.TodoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Bringt Anfragen als Unteraufgaben in Felix' To-Do: Bereich „Server", darin die
 * offene Aufgabe mit dem Namen der App („Healthy", „coHabit", …), darunter je
 * Anfrage eine Unteraufgabe mit dem Titel der Karte und dem Link auf die Kartenseite.
 *
 * <p>Das To-Do darf dabei ausfallen. Die Anfrage ist zu dem Zeitpunkt schon
 * gespeichert; scheitert das Anlegen, bleibt sie ohne {@code todoId}, und
 * {@link #catchUp()} versucht es alle zehn Minuten wieder. Eine abgeschickte Anfrage
 * geht so nie verloren, sie kommt hoechstens spaeter an.
 */
@Component
public class FeatureRequestTodos {

    private static final Logger log = LoggerFactory.getLogger(FeatureRequestTodos.class);

    static final String AREA = "Server";

    private final TodoClient client;
    private final FeatureRequestRepository repository;
    private final String baseUrl;

    /**
     * Eine Sperre fuer alles, was im To-Do anlegt. Ohne sie legten eine neue Anfrage
     * und der Nachlauf, die sich zeitlich treffen, dieselbe Unteraufgabe doppelt an -
     * oder zweimal dieselbe Elternaufgabe.
     */
    private final Object creating = new Object();

    /** Ob der letzte Versuch scheiterte - damit ein Ausfall einmal im Journal steht und nicht alle zehn Minuten. */
    private volatile boolean unreachable;

    public FeatureRequestTodos(
            TodoClient client,
            FeatureRequestRepository repository,
            @Value("${food.feature-requests.base-url:https://fherrmann.com/feature-requests}") String baseUrl) {
        this.client = client;
        this.repository = repository;
        String url = baseUrl == null ? "" : baseUrl.trim();
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** Die Kartenseite einer Anfrage - der Link in der Unteraufgabe. */
    public String cardUrl(String id) {
        return baseUrl + "/" + id;
    }

    /** Das Brett, oder leer, wenn das To-Do gerade nicht antwortet. Nie eine Ausnahme. */
    public Optional<TodoBoard> board() {
        if (!client.isConfigured()) {
            return Optional.empty();
        }
        try {
            TodoBoard board = client.board();
            recovered();
            return Optional.of(board);
        } catch (TodoException e) {
            failed(e);
            return Optional.empty();
        }
    }

    /**
     * Legt die Unteraufgabe an, falls die Anfrage noch keine hat.
     *
     * @return die Anfrage mit {@code todoId} - oder unveraendert, wenn das To-Do
     *         nicht mitspielt; der Nachlauf versucht es dann wieder
     */
    public FeatureRequest ensureTodo(FeatureRequest request) {
        if (!client.isConfigured()) {
            return request;
        }
        synchronized (creating) {
            // Frisch lesen: der Nachlauf kann sie inzwischen erledigt haben - oder
            // Felix hat sie geloescht, dann darf keine Unteraufgabe mehr entstehen.
            Optional<FeatureRequest> fresh = repository.find(request.id());
            if (fresh.isEmpty()) {
                return request;
            }
            FeatureRequest current = fresh.get();
            if (current.todoId() != null) {
                return current;
            }
            try {
                String todoId = createSubtask(current);
                recovered();
                return repository.update(current.id(), r -> r.withTodoId(todoId))
                        .orElse(current.withTodoId(todoId));
            } catch (TodoException e) {
                failed(e);
                return current;
            }
        }
    }

    private String createSubtask(FeatureRequest request) {
        TodoBoard board = client.board();
        String link = cardUrl(request.id());

        // Gibt es schon eine Aufgabe mit genau diesem Link, ist sie beim letzten Mal
        // angelegt worden und nur die Antwort kam nicht mehr an. Uebernehmen statt
        // doppelt anlegen.
        Optional<TodoBoard.Todo> existing = board.withLink(link);
        if (existing.isPresent()) {
            return existing.get().id();
        }

        String areaId;
        Optional<TodoBoard.Area> area = board.area(AREA);
        if (area.isPresent()) {
            areaId = area.get().id();
        } else {
            TodoClient.Created created = client.createArea(board, AREA);
            areaId = created.id();
            board = created.board();
            log.info("Bereich „{}“ im To-Do angelegt", AREA);
        }

        // Nur eine offene Aufgabe der obersten Ebene taugt als Eltern: unter einer
        // erledigten ginge die neue Unteraufgabe mit ihr aus dem Brett.
        String parentTitle = request.featureApp().displayName();
        String parentId;
        Optional<TodoBoard.Todo> parent = board.openTopLevel(areaId, parentTitle);
        if (parent.isPresent()) {
            parentId = parent.get().id();
        } else {
            TodoClient.Created created = client.createTodo(board, areaId, null, parentTitle, null);
            parentId = created.id();
            board = created.board();
            log.info("Aufgabe „{}“ im Bereich „{}“ angelegt", parentTitle, AREA);
        }

        String todoId = client.createTodo(board, areaId, parentId, request.title(), link).id();
        log.info("Anfrage {} von {} als Unteraufgabe {} im To-Do", request.id(), request.author(), todoId);
        return todoId;
    }

    /**
     * Entfernt die Anfrage und danach ihre Unteraufgabe.
     *
     * <p>Unter derselben Sperre wie das Anlegen: sonst koennte der Nachlauf einer
     * gerade geloeschten Anfrage noch eine Unteraufgabe nachschieben, die dann ohne
     * Anfrage im To-Do stuende.
     *
     * <p>Das To-Do darf dabei ausfallen, und die Unteraufgabe darf schon weg sein -
     * Felix raeumt dort auch selbst auf. Geloescht ist die Anfrage dann trotzdem; eine
     * verwaiste Unteraufgabe loescht man zur Not von Hand, eine Anfrage, die sich
     * nicht loeschen laesst, waere schlimmer.
     *
     * @return die entfernte Anfrage, oder leer, wenn es sie nicht mehr gab
     */
    public Optional<FeatureRequest> remove(String id) {
        synchronized (creating) {
            Optional<FeatureRequest> removed = repository.remove(id);
            removed.map(FeatureRequest::todoId)
                    .filter(todoId -> client.isConfigured())
                    .ifPresent(todoId -> {
                        try {
                            client.deleteTodo(todoId);
                            log.info("Anfrage {} geloescht, Unteraufgabe {} ebenso", id, todoId);
                        } catch (TodoException e) {
                            log.warn("Anfrage {} geloescht, Unteraufgabe {} nicht: {}", id, todoId, e.getMessage());
                        }
                    });
            return removed;
        }
    }

    /**
     * Der Nachlauf: legt fehlende Unteraufgaben an und merkt sich den Stand der
     * vorhandenen - der Rueckfall, falls das To-Do beim naechsten Blick auf die Liste
     * nicht antwortet.
     */
    @Scheduled(initialDelayString = "${food.todo.sync-initial-delay:PT1M}",
               fixedDelayString = "${food.todo.sync-interval:PT10M}")
    public void catchUp() {
        List<FeatureRequest> requests = repository.all();
        if (requests.isEmpty() || !client.isConfigured()) {
            return;
        }
        // Jede fuer sich und ohne Abbruch beim ersten Fehler: eine Anfrage, die das
        // To-Do aus eigenem Grund ablehnt, soll die uebrigen nicht auf Dauer aufhalten.
        for (FeatureRequest request : requests) {
            if (request.todoId() == null) {
                ensureTodo(request);
            }
        }
        board().ifPresent(this::remember);
    }

    /** Uebernimmt die {@code doneAt} der Unteraufgaben, soweit sie sich geaendert haben. */
    void remember(TodoBoard board) {
        for (FeatureRequest request : repository.all()) {
            board.todo(request.todoId())
                    .filter(todo -> !Objects.equals(todo.doneAt(), request.doneAt()))
                    .ifPresent(todo -> repository.update(request.id(), r -> r.withDoneAt(todo.doneAt())));
        }
    }

    private void failed(TodoException e) {
        if (!unreachable) {
            log.warn("To-Do nicht verfuegbar (neue Anfragen warten auf den Nachlauf): {}", e.getMessage());
        }
        unreachable = true;
    }

    private void recovered() {
        if (unreachable) {
            log.info("To-Do wieder erreichbar");
        }
        unreachable = false;
    }
}

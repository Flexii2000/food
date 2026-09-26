package com.fherrmann.food.featurerequest;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Das Brett des To-Do-Dienstes, soweit es hier gebraucht wird: Bereiche, Aufgaben,
 * eine Ebene Unteraufgaben.
 *
 * <p>Gelesen ueber den Baum statt ueber gebundene Klassen: das Brett hat mehr Felder
 * (Faelligkeit, Erinnerungen, Zaehler), und ein neues Feld dort darf hier nichts
 * brechen.
 */
public record TodoBoard(List<Area> areas) {

    public record Area(String id, String name, List<Todo> todos) {
    }

    /** @param parentId gesetzt bei einer Unteraufgabe - abgeleitet aus der Verschachtelung */
    public record Todo(String id, String parentId, String title, Instant doneAt, String link) {

        public boolean isDone() {
            return doneAt != null;
        }
    }

    public static TodoBoard parse(JsonNode root) {
        List<Area> areas = new ArrayList<>();
        for (JsonNode area : root.path("areas")) {
            List<Todo> todos = new ArrayList<>();
            for (JsonNode todo : area.path("todos")) {
                String id = text(todo.path("id"));
                todos.add(todo(todo, null));
                for (JsonNode child : todo.path("children")) {
                    todos.add(todo(child, id));
                }
            }
            areas.add(new Area(text(area.path("id")), text(area.path("name")), List.copyOf(todos)));
        }
        return new TodoBoard(List.copyOf(areas));
    }

    private static Todo todo(JsonNode node, String parentId) {
        return new Todo(text(node.path("id")), parentId, text(node.path("title")),
                instant(node.path("doneAt")), text(node.path("link")));
    }

    private static String text(JsonNode node) {
        return node.isString() ? node.asString() : null;
    }

    /**
     * Ein Zeitpunkt als ISO-Text, wie Jackson 3 ihn schreibt - oder als Sekunden, wie
     * Jackson 2 es tat. Etwas Unlesbares ist trotzdem "abgehakt": fuer den Stand zaehlt
     * nur, ob da etwas steht.
     */
    private static Instant instant(JsonNode node) {
        if (node.isNumber()) {
            BigDecimal seconds = node.decimalValue();
            return Instant.ofEpochSecond(seconds.longValue(),
                    seconds.remainder(BigDecimal.ONE).movePointRight(9).longValue());
        }
        if (!node.isString() || node.asString().isBlank()) {
            return null;
        }
        try {
            return Instant.parse(node.asString());
        } catch (DateTimeParseException e) {
            return Instant.EPOCH;
        }
    }

    public Stream<Todo> todos() {
        return areas.stream().flatMap(a -> a.todos().stream());
    }

    public Optional<Todo> todo(String id) {
        return id == null ? Optional.empty() : todos().filter(t -> id.equals(t.id())).findFirst();
    }

    /** Der Bereich mit diesem Namen, Gross- und Kleinschreibung egal - wie der Dienst selbst vergleicht. */
    public Optional<Area> area(String name) {
        return areas.stream().filter(a -> a.name() != null && a.name().trim().equalsIgnoreCase(name)).findFirst();
    }

    /** Die erste offene Aufgabe der obersten Ebene mit diesem Titel. */
    public Optional<Todo> openTopLevel(String areaId, String title) {
        return areas.stream()
                .filter(a -> a.id().equals(areaId))
                .flatMap(a -> a.todos().stream())
                .filter(t -> t.parentId() == null && !t.isDone())
                .filter(t -> t.title() != null && t.title().trim().equalsIgnoreCase(title))
                .findFirst();
    }

    /** Eine Aufgabe, die schon auf diese Adresse zeigt - irgendwo auf dem Brett. */
    public Optional<Todo> withLink(String link) {
        return todos().filter(t -> link.equals(t.link())).findFirst();
    }
}

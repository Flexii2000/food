package com.fherrmann.food.featurerequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Spricht den To-Do-Dienst an ({@code fherrmann.com/todo}, intern {@code 127.0.0.1:48210}).
 *
 * <p>Angemeldet wird wie jeder andere Client dort: mit dem Privat-Cookie. Es ist
 * derselbe {@code FH_PRIVATE_TOKEN}, den dieser Dienst selbst prueft - beide lesen
 * ihn aus {@code private-mode.conf}.
 *
 * <p>Jede aendernde Antwort des Dienstes ist das ganze Brett, keine neue Id. Die Id
 * ergibt sich aus dem Vergleich vorher/nachher; wer den Vergleich macht, reicht das
 * Brett von vorher herein und bekommt das von nachher mit zurueck - so dient es dem
 * naechsten Schritt wieder als "vorher".
 */
@Component
public class TodoClient {

    /** Die Antwort auf ein Anlegen: die neue Id und das Brett danach. */
    public record Created(String id, TodoBoard board) {
    }

    /** Was Felix beim Anlegen zusaetzlich als Push in der Fokus-App sieht. */
    public record Notification(String title, String body) {
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final String baseUrl;
    private final String token;
    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public TodoClient(
            @Value("${food.todo.url:}") String baseUrl,
            @Value("${food.security.token}") String token,
            ObjectMapper objectMapper) {
        String url = baseUrl == null ? "" : baseUrl.trim();
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.token = token;
        this.objectMapper = objectMapper;
    }

    /** Ohne Adresse gibt es kein To-Do - die Anfragen bleiben dann einfach ohne Aufgabe. */
    public boolean isConfigured() {
        return !baseUrl.isEmpty();
    }

    /** Das ganze Brett, auch das laengst Erledigte - daran haengt der Stand alter Anfragen. */
    public TodoBoard board() {
        return TodoBoard.parse(send(request("/api/board?all=true").GET()));
    }

    public Created createArea(TodoBoard before, String name) {
        TodoBoard after = TodoBoard.parse(send(post("/api/areas", Map.of("name", name))));
        List<TodoBoard.Area> fresh = after.areas().stream()
                .filter(a -> before.areas().stream().noneMatch(b -> b.id().equals(a.id())))
                .toList();
        List<TodoBoard.Area> named = fresh.stream()
                .filter(a -> a.name() != null && a.name().trim().equalsIgnoreCase(name))
                .toList();
        if (named.size() == 1) {
            return new Created(named.getFirst().id(), after);
        }
        throw new TodoException("Der neue Bereich „" + name + "“ ist im Brett nicht eindeutig zu finden.");
    }

    /**
     * Legt eine Aufgabe an. Kommen zwischen vorher und nachher mehrere neue Aufgaben
     * dazu (etwa die Elternaufgabe aus dem Schritt davor, oder Felix tippt gerade
     * selbst), entscheiden Titel und Elternaufgabe.
     *
     * @param parentId     die Aufgabe darueber, oder {@code null} fuer die oberste Ebene
     * @param link         wohin die Aufgabe zeigt, oder {@code null}
     * @param notification was Fokus dazu meldet, oder {@code null} fuer nichts. Ein
     *                     To-Do von vor der Benachrichtigung uebergeht das Feld still.
     */
    public Created createTodo(TodoBoard before, String areaId, String parentId, String title, String link,
                              Notification notification) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("areaId", areaId);
        if (parentId != null) {
            body.put("parentId", parentId);
        }
        body.put("title", title);
        if (link != null) {
            body.put("link", link);
        }
        if (notification != null) {
            body.put("notification", Map.of("title", notification.title(), "body", notification.body()));
        }
        TodoBoard after = TodoBoard.parse(send(post("/api/todos", body)));
        List<TodoBoard.Todo> fresh = after.todos()
                .filter(t -> before.todo(t.id()).isEmpty())
                .toList();
        List<TodoBoard.Todo> matching = fresh.stream()
                .filter(t -> Objects.equals(t.parentId(), parentId) && title.trim().equals(t.title()))
                .toList();
        if (matching.size() == 1) {
            return new Created(matching.getFirst().id(), after);
        }
        if (matching.isEmpty() && fresh.size() == 1) {
            return new Created(fresh.getFirst().id(), after);
        }
        throw new TodoException("Die neue Aufgabe „" + title + "“ ist im Brett nicht eindeutig zu finden.");
    }

    /** Loescht eine Aufgabe samt ihren Unteraufgaben - so macht es das To-Do selbst. */
    public void deleteTodo(String todoId) {
        send(request("/api/todos/" + URLEncoder.encode(todoId, StandardCharsets.UTF_8)).DELETE());
    }

    private HttpRequest.Builder request(String path) {
        if (!isConfigured()) {
            throw new TodoException("Kein To-Do-Dienst eingerichtet (food.todo.url).");
        }
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(TIMEOUT)
                .header("Cookie", "fh_private=" + token)
                .header("Accept", "application/json");
    }

    private HttpRequest.Builder post(String path, Map<String, ?> body) {
        return request(path)
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));
    }

    private JsonNode send(HttpRequest.Builder builder) {
        HttpRequest request = builder.build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new TodoException("To-Do nicht erreichbar: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TodoException("Abgebrochen", e);
        }
        if (response.statusCode() / 100 != 2) {
            // Der Dienst schickt seine Fehler als Klartext ("Bereich nicht gefunden.").
            throw new TodoException("To-Do antwortete " + response.statusCode() + " auf "
                    + request.method() + " " + request.uri().getPath() + ": " + shorten(response.body()));
        }
        try {
            return objectMapper.readTree(response.body());
        } catch (RuntimeException e) {
            throw new TodoException("Antwort des To-Do nicht lesbar: " + shorten(response.body()), e);
        }
    }

    private static String shorten(String text) {
        String value = text == null ? "" : text.strip();
        return value.length() <= 200 ? value : value.substring(0, 200) + "…";
    }

    /** Alles, was beim To-Do schiefgehen kann - fuer den Aufrufer ist es immer "spaeter nochmal". */
    public static class TodoException extends RuntimeException {
        public TodoException(String message) {
            super(message);
        }

        public TodoException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

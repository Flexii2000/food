package com.fherrmann.food.featurerequest;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Spielt den To-Do-Dienst nach, so weit der Vertrag reicht: Brett lesen, Bereich und
 * Aufgabe anlegen, Anmeldung per Privat-Cookie, Fehler als Klartext. Wie das
 * Original antwortet jedes Anlegen mit dem ganzen Brett - ohne die Erledigten, die
 * aelter als drei Tage sind, denn die zeigt nur {@code all=true}.
 */
final class FakeTodoServer implements AutoCloseable {

    static final String TOKEN = "private-token";

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpServer server;

    record Area(String id, String name) {
    }

    static final class Todo {
        final String id;
        final String areaId;
        final String parentId;
        final String title;
        final String link;
        final Instant createdAt;
        volatile Instant doneAt;

        Todo(String id, String areaId, String parentId, String title, String link, Instant createdAt) {
            this.id = id;
            this.areaId = areaId;
            this.parentId = parentId;
            this.title = title;
            this.link = link;
            this.createdAt = createdAt;
        }
    }

    final List<Area> areas = new CopyOnWriteArrayList<>();
    final List<Todo> todos = new CopyOnWriteArrayList<>();
    /** Die Benachrichtigung, die beim Anlegen mitkam, je Aufgaben-Id - wie das To-Do sie an Fokus weitergaebe. */
    final Map<String, JsonNode> notifications = new ConcurrentHashMap<>();
    /** Jede Anfrage als "METHODE pfad?query". */
    final List<String> requests = new CopyOnWriteArrayList<>();
    final List<String> cookies = new CopyOnWriteArrayList<>();

    /** Wie das To-Do vor der Link-Erweiterung: das Feld wird angenommen und vergessen. */
    volatile boolean storesLinks = true;
    /** Die naechste Anfrage scheitert mit diesem Status und Klartext. */
    volatile Integer failStatus;
    volatile String failBody;
    /** Legt beim naechsten Anlegen einer Aufgabe eine fremde dazu - als tippte Felix gerade selbst. */
    volatile boolean sneakInATodo;

    FakeTodoServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/todo/api/", this::handle);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/todo";
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // MARK: - Bestand herrichten

    Area area(String name) {
        Area area = new Area(UUID.randomUUID().toString(), name);
        areas.add(area);
        return area;
    }

    Todo todo(Area area, Todo parent, String title) {
        Todo todo = new Todo(UUID.randomUUID().toString(), area.id(), parent == null ? null : parent.id,
                title, null, Instant.now());
        todos.add(todo);
        return todo;
    }

    Todo find(String id) {
        return todos.stream().filter(t -> t.id.equals(id)).findFirst().orElse(null);
    }

    List<Todo> children(Todo parent) {
        return todos.stream().filter(t -> parent.id.equals(t.parentId)).toList();
    }

    long count(String method) {
        return requests.stream().filter(r -> r.startsWith(method + " ")).count();
    }

    // MARK: - HTTP

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getQuery();
        requests.add(exchange.getRequestMethod() + " " + path + (query == null ? "" : "?" + query));
        String cookie = exchange.getRequestHeaders().getFirst("Cookie");
        cookies.add(String.valueOf(cookie));
        if (cookie == null || !cookie.contains("fh_private=" + TOKEN)) {
            respond(exchange, 403, "text/plain", "");
            return;
        }
        if (failStatus != null) {
            int status = failStatus;
            failStatus = null;
            respond(exchange, status, "text/plain;charset=UTF-8", failBody == null ? "" : failBody);
            return;
        }
        try {
            switch (exchange.getRequestMethod() + " " + path) {
                case "GET /todo/api/board" -> respond(exchange, 200, "application/json",
                        board("all=true".equals(query)));
                case "POST /todo/api/areas" -> createArea(exchange, body(exchange));
                case "POST /todo/api/todos" -> createTodo(exchange, body(exchange));
                default -> {
                    if (exchange.getRequestMethod().equals("DELETE") && path.startsWith("/todo/api/todos/")) {
                        deleteTodo(exchange, path.substring("/todo/api/todos/".length()));
                    } else {
                        respond(exchange, 404, "text/plain", "unbekannt");
                    }
                }
            }
        } catch (RuntimeException e) {
            respond(exchange, 500, "text/plain", e.toString());
        }
    }

    private JsonNode body(HttpExchange exchange) throws IOException {
        return mapper.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    }

    private void createArea(HttpExchange exchange, JsonNode body) throws IOException {
        String name = body.path("name").asString("").trim();
        if (areas.stream().anyMatch(a -> a.name().equalsIgnoreCase(name))) {
            respond(exchange, 400, "text/plain;charset=UTF-8", "Den Bereich „" + name + "“ gibt es schon.");
            return;
        }
        area(name);
        respond(exchange, 201, "application/json", board(false));
    }

    private void createTodo(HttpExchange exchange, JsonNode body) throws IOException {
        String areaId = body.path("areaId").asString(null);
        String parentId = body.path("parentId").asString(null);
        if (areas.stream().noneMatch(a -> a.id().equals(areaId))) {
            respond(exchange, 404, "text/plain;charset=UTF-8", "Bereich nicht gefunden.");
            return;
        }
        if (parentId != null) {
            Todo parent = find(parentId);
            if (parent == null) {
                respond(exchange, 404, "text/plain;charset=UTF-8", "Aufgabe nicht gefunden.");
                return;
            }
            if (parent.parentId != null) {
                respond(exchange, 400, "text/plain;charset=UTF-8", "Eine Unteraufgabe kann keine Unteraufgaben haben.");
                return;
            }
        }
        if (sneakInATodo) {
            sneakInATodo = false;
            todos.add(new Todo(UUID.randomUUID().toString(), areaId, parentId, "Zwischendurch", null, Instant.now()));
        }
        Todo created = new Todo(UUID.randomUUID().toString(), areaId, parentId, body.path("title").asString("").trim(),
                storesLinks ? body.path("link").asString(null) : null, Instant.now());
        todos.add(created);
        if (body.has("notification")) {
            notifications.put(created.id, body.get("notification"));
        }
        respond(exchange, 201, "application/json", board(false));
    }

    /** Wie das Original: die Aufgabe samt Unteraufgaben, 404 als Klartext, wenn es sie nicht gibt. */
    private void deleteTodo(HttpExchange exchange, String id) throws IOException {
        Todo todo = find(id);
        if (todo == null) {
            respond(exchange, 404, "text/plain;charset=UTF-8", "Aufgabe nicht gefunden.");
            return;
        }
        todos.removeIf(t -> t.id.equals(id) || id.equals(t.parentId));
        respond(exchange, 200, "application/json", board(false));
    }

    private boolean visible(Todo todo, boolean all) {
        return all || todo.doneAt == null || todo.doneAt.plus(Duration.ofDays(3)).isAfter(Instant.now());
    }

    private String board(boolean all) {
        List<Map<String, Object>> areaViews = new ArrayList<>();
        for (Area area : areas) {
            List<Map<String, Object>> tops = new ArrayList<>();
            for (Todo top : todos) {
                if (!top.areaId.equals(area.id()) || top.parentId != null || !visible(top, all)) {
                    continue;
                }
                List<Map<String, Object>> children = new ArrayList<>();
                for (Todo child : children(top)) {
                    if (visible(child, all)) {
                        children.add(view(child, List.of()));
                    }
                }
                tops.add(view(top, children));
            }
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", area.id());
            view.put("name", area.name());
            view.put("position", areaViews.size());
            view.put("todos", tops);
            areaViews.add(view);
        }
        Map<String, Object> board = new LinkedHashMap<>();
        board.put("areas", areaViews);
        board.put("includesHidden", all);
        board.put("now", Instant.now().toString());
        return mapper.writeValueAsString(board);
    }

    private Map<String, Object> view(Todo todo, List<Map<String, Object>> children) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", todo.id);
        view.put("title", todo.title);
        view.put("createdAt", todo.createdAt.toString());
        view.put("doneAt", todo.doneAt == null ? null : todo.doneAt.toString());
        if (storesLinks) {
            view.put("link", todo.link);
        }
        view.put("reminders", List.of());
        view.put("children", children);
        return view;
    }

    private static void respond(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }
}

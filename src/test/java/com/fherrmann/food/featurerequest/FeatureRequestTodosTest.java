package com.fherrmann.food.featurerequest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wo die Unteraufgabe landet: Bereich „Server", offene Aufgabe „Healthy" - beides wird
 * gefunden oder angelegt. Und was passiert, wenn das To-Do nicht mitspielt.
 */
class FeatureRequestTodosTest {

    private static final String BASE = "https://fherrmann.com/feature-requests";

    @TempDir
    Path tempDir;

    private FakeTodoServer todo;
    private FeatureRequestRepository repository;
    private FeatureRequestTodos todos;

    @BeforeEach
    void setUp() throws Exception {
        todo = new FakeTodoServer();
        repository = new FeatureRequestRepository(tempDir.resolve("feature-requests.json").toString(), new ObjectMapper());
        todos = todos(todo.baseUrl());
    }

    @AfterEach
    void tearDown() {
        todo.close();
    }

    private FeatureRequestTodos todos(String url) {
        return new FeatureRequestTodos(new TodoClient(url, FakeTodoServer.TOKEN, new ObjectMapper()), repository, BASE);
    }

    private FeatureRequest stored(String title) {
        FeatureRequest request = new FeatureRequest(UUID.randomUUID().toString(), "torben", Instant.now(),
                "ich will", title, "Als … möchte ich …, damit …", List.of("a"), null, null);
        repository.add(request);
        return request;
    }

    @Test
    void anExistingServerAreaAndOpenHealthyTaskAreUsed() {
        FakeTodoServer.Area server = todo.area("Server");
        FakeTodoServer.Todo healthy = todo.todo(server, null, "Healthy");
        FeatureRequest request = stored("Dunkles Widget");

        FeatureRequest result = todos.ensureTodo(request);

        FakeTodoServer.Todo subtask = todo.find(result.todoId());
        assertThat(subtask.parentId).isEqualTo(healthy.id);
        assertThat(subtask.areaId).isEqualTo(server.id());
        assertThat(subtask.title).isEqualTo("Dunkles Widget");
        assertThat(subtask.link).isEqualTo(BASE + "/" + request.id());
        assertThat(todo.areas).hasSize(1);
        assertThat(todo.count("POST")).isEqualTo(1);
        assertThat(repository.find(request.id()).orElseThrow().todoId()).isEqualTo(result.todoId());
    }

    /** Gross/klein wie im To-Do selbst - sonst lehnte es den zweiten „server" als Doublette ab. */
    @Test
    void theAreaIsMatchedRegardlessOfCase() {
        FakeTodoServer.Area server = todo.area("server");
        FakeTodoServer.Todo healthy = todo.todo(server, null, "healthy");

        FeatureRequest result = todos.ensureTodo(stored("Export als CSV"));

        assertThat(todo.find(result.todoId()).parentId).isEqualTo(healthy.id);
        assertThat(todo.areas).hasSize(1);
    }

    @Test
    void aMissingServerAreaIsCreatedWithItsHealthyTask() {
        todo.area("Privat");

        FeatureRequest result = todos.ensureTodo(stored("Barcode für Getränke"));

        assertThat(todo.areas).extracting(FakeTodoServer.Area::name).containsExactly("Privat", "Server");
        FakeTodoServer.Todo subtask = todo.find(result.todoId());
        FakeTodoServer.Todo parent = todo.find(subtask.parentId);
        assertThat(parent.title).isEqualTo("Healthy");
        assertThat(parent.parentId).isNull();
        assertThat(parent.areaId).isEqualTo(todo.areas.get(1).id());
    }

    /** Unter einer erledigten Aufgabe verschwaende die neue Unteraufgabe mit ihr aus dem Brett. */
    @Test
    void aDoneHealthyTaskGetsAFreshOne() {
        FakeTodoServer.Area server = todo.area("Server");
        FakeTodoServer.Todo old = todo.todo(server, null, "Healthy");
        old.doneAt = Instant.now().minusSeconds(3600 * 24 * 10);

        FeatureRequest result = todos.ensureTodo(stored("Wochenansicht"));

        FakeTodoServer.Todo parent = todo.find(todo.find(result.todoId()).parentId);
        assertThat(parent.id).isNotEqualTo(old.id);
        assertThat(parent.title).isEqualTo("Healthy");
        assertThat(parent.doneAt).isNull();
    }

    /** Nur die oberste Ebene zaehlt: eine Unteraufgabe „Healthy" irgendwo ist keine Elternaufgabe. */
    @Test
    void aHealthySubtaskIsNoParent() {
        FakeTodoServer.Area server = todo.area("Server");
        FakeTodoServer.Todo other = todo.todo(server, null, "Heimserver");
        todo.todo(server, other, "Healthy");

        FeatureRequest result = todos.ensureTodo(stored("Kalender"));

        FakeTodoServer.Todo parent = todo.find(todo.find(result.todoId()).parentId);
        assertThat(parent.parentId).isNull();
        assertThat(parent.title).isEqualTo("Healthy");
        assertThat(parent.id).isNotEqualTo(other.id);
    }

    @Test
    void aSecondHealthyIsNotCreatedForTheNextRequest() {
        todos.ensureTodo(stored("Eins"));
        todos.ensureTodo(stored("Zwei"));

        assertThat(todo.todos.stream().filter(t -> t.title.equals("Healthy"))).hasSize(1);
        assertThat(todo.todos).hasSize(3);
    }

    @Test
    void aRequestWithATodoIsLeftAlone() {
        FeatureRequest request = stored("Schon da").withTodoId("t-1");
        repository.update(request.id(), r -> r.withTodoId("t-1"));

        assertThat(todos.ensureTodo(request).todoId()).isEqualTo("t-1");
        assertThat(todo.requests).isEmpty();
    }

    /**
     * Das Kernversprechen: faellt das To-Do aus, bleibt die Anfrage ohne Aufgabe
     * gespeichert - und der Nachlauf holt die Aufgabe nach, sobald es wieder da ist.
     */
    @Test
    void whileTheTodoIsDownTheRequestWaitsAndTheCatchUpCreatesItLater() throws Exception {
        FeatureRequest request = stored("Später");
        todo.close();

        FeatureRequest result = todos.ensureTodo(request);
        assertThat(result.todoId()).isNull();
        assertThat(repository.find(request.id())).hasValueSatisfying(r -> assertThat(r.todoId()).isNull());

        // Der Dienst ist wieder da (neuer Port, dieselbe Anwendung).
        todo = new FakeTodoServer();
        FeatureRequestTodos later = todos(todo.baseUrl());
        later.catchUp();

        String todoId = repository.find(request.id()).orElseThrow().todoId();
        assertThat(todoId).isNotNull();
        assertThat(todo.find(todoId).title).isEqualTo("Später");
    }

    @Test
    void theCatchUpCreatesOnlyWhatIsMissing() {
        FeatureRequest first = todos.ensureTodo(stored("Erste"));
        FeatureRequest second = stored("Zweite");

        todos.catchUp();

        assertThat(repository.find(second.id()).orElseThrow().todoId()).isNotNull();
        assertThat(repository.find(first.id()).orElseThrow().todoId()).isEqualTo(first.todoId());
        assertThat(todo.todos.stream().filter(t -> t.parentId != null)).hasSize(2);
    }

    /**
     * Kam die Antwort auf das Anlegen nicht mehr an, steht die Aufgabe trotzdem im To-Do.
     * Ihr Link verraet sie - der Nachlauf uebernimmt sie, statt sie doppelt anzulegen.
     */
    @Test
    void aTodoThatAlreadyCarriesTheLinkIsAdopted() {
        FakeTodoServer.Area server = todo.area("Server");
        FakeTodoServer.Todo healthy = todo.todo(server, null, "Healthy");
        FeatureRequest request = stored("Verloren gegangen");
        FakeTodoServer.Todo orphan = new FakeTodoServer.Todo("t-orphan", server.id(), healthy.id,
                "Verloren gegangen", BASE + "/" + request.id(), Instant.now());
        todo.todos.add(orphan);

        todos.catchUp();

        assertThat(repository.find(request.id()).orElseThrow().todoId()).isEqualTo("t-orphan");
        assertThat(todo.count("POST")).isZero();
    }

    /** Das To-Do vor der Link-Erweiterung vergisst das Feld - die Aufgabe entsteht trotzdem. */
    @Test
    void anOldTodoWithoutLinksStillGetsTheSubtask() {
        todo.storesLinks = false;

        FeatureRequest result = todos.ensureTodo(stored("Ohne Link"));

        assertThat(result.todoId()).isNotNull();
        assertThat(todo.find(result.todoId()).link).isNull();
    }

    /** Der zuletzt gesehene Stand wird gemerkt - der Rueckfall, wenn das To-Do spaeter nicht antwortet. */
    @Test
    void theCatchUpRemembersWhenASubtaskWasTicked() {
        FeatureRequest request = todos.ensureTodo(stored("Abhaken"));
        Instant ticked = Instant.parse("2026-09-26T18:00:00Z");
        todo.find(request.todoId()).doneAt = ticked;

        todos.catchUp();
        assertThat(repository.find(request.id()).orElseThrow().doneAt()).isEqualTo(ticked);

        // Haken zurueck: wieder offen.
        todo.find(request.todoId()).doneAt = null;
        todos.catchUp();
        assertThat(repository.find(request.id()).orElseThrow().doneAt()).isNull();
    }

    @Test
    void withoutATodoAddressNothingIsAttempted() {
        FeatureRequestTodos none = todos("");
        FeatureRequest request = stored("Nirgends");

        assertThat(none.ensureTodo(request).todoId()).isNull();
        assertThat(none.board()).isEmpty();
        none.catchUp();
        assertThat(todo.requests).isEmpty();
    }

    @Test
    void theCardUrlIsTheBaseUrlPlusTheId() {
        assertThat(new FeatureRequestTodos(new TodoClient("", "x", new ObjectMapper()), repository,
                "http://localhost:48380/feature-requests/").cardUrl("abc"))
                .isEqualTo("http://localhost:48380/feature-requests/abc");
    }

    // MARK: - Loeschen

    @Test
    void removingARequestDeletesItsSubtaskToo() {
        FeatureRequest request = todos.ensureTodo(stored("Weg damit"));
        FeatureRequest other = todos.ensureTodo(stored("Bleibt"));

        assertThat(todos.remove(request.id())).isPresent();

        assertThat(repository.find(request.id())).isEmpty();
        assertThat(todo.find(request.todoId())).isNull();
        assertThat(todo.find(other.todoId())).isNotNull();
        assertThat(todo.requests).contains("DELETE /todo/api/todos/" + request.todoId());
    }

    /** Felix raeumt im To-Do auch selbst auf - dann ist die Unteraufgabe eben schon weg. */
    @Test
    void anAlreadyDeletedSubtaskDoesNotKeepTheRequest() {
        FeatureRequest request = todos.ensureTodo(stored("Schon abgeräumt"));
        todo.todos.removeIf(t -> t.id.equals(request.todoId()));

        assertThat(todos.remove(request.id())).isPresent();
        assertThat(repository.find(request.id())).isEmpty();
    }

    @Test
    void anUnreachableTodoDoesNotKeepTheRequest() {
        FeatureRequest request = todos.ensureTodo(stored("To-Do schläft"));
        todo.failStatus = 503;
        todo.failBody = "Wartung";

        assertThat(todos.remove(request.id())).isPresent();
        assertThat(repository.find(request.id())).isEmpty();
        // Die Unteraufgabe bleibt stehen - das ist der Preis, und er steht im Journal.
        assertThat(todo.find(request.todoId())).isNotNull();

        assertThat(todos("http://127.0.0.1:9/todo").remove(todos.ensureTodo(stored("Ganz weg")).id())).isPresent();
    }

    /** Ohne Unteraufgabe gibt es im To-Do nichts zu loeschen - und der Nachlauf legt keine mehr an. */
    @Test
    void aRemovedRequestGetsNoLateSubtask() {
        FeatureRequest request = stored("Noch ohne Aufgabe");
        todos.remove(request.id());

        assertThat(todos.ensureTodo(request).todoId()).isNull();
        todos.catchUp();
        assertThat(todo.count("POST")).isZero();
        assertThat(todo.count("DELETE")).isZero();
    }

    @Test
    void removingAnUnknownRequestFindsNothing() {
        assertThat(todos.remove("gibt-es-nicht")).isEmpty();
        assertThat(todo.requests).isEmpty();
    }
}

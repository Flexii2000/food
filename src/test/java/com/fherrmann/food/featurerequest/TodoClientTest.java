package com.fherrmann.food.featurerequest;

import com.fherrmann.food.featurerequest.TodoClient.TodoException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Gegen einen lokalen Server, der das To-Do spielt: was ueber die Leitung geht, und was daraus gelesen wird. */
class TodoClientTest {

    private FakeTodoServer todo;
    private TodoClient client;

    @BeforeEach
    void setUp() throws Exception {
        todo = new FakeTodoServer();
        client = new TodoClient(todo.baseUrl() + "/", FakeTodoServer.TOKEN, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        todo.close();
    }

    @Test
    void theBoardComesWithThePrivateCookieAndIncludesEverythingDone() {
        FakeTodoServer.Area server = todo.area("Server");
        FakeTodoServer.Todo healthy = todo.todo(server, null, "Healthy");
        FakeTodoServer.Todo old = todo.todo(server, healthy, "Längst erledigt");
        old.doneAt = Instant.parse("2026-01-02T03:04:05Z");

        TodoBoard board = client.board();

        assertThat(todo.requests).containsExactly("GET /todo/api/board?all=true");
        assertThat(todo.cookies.getFirst()).isEqualTo("fh_private=" + FakeTodoServer.TOKEN);
        assertThat(board.area("server")).hasValueSatisfying(a -> assertThat(a.id()).isEqualTo(server.id()));
        TodoBoard.Todo child = board.todo(old.id).orElseThrow();
        assertThat(child.parentId()).isEqualTo(healthy.id);
        assertThat(child.title()).isEqualTo("Längst erledigt");
        assertThat(child.doneAt()).isEqualTo(Instant.parse("2026-01-02T03:04:05Z"));
        assertThat(board.todo(healthy.id).orElseThrow().isDone()).isFalse();
    }

    @Test
    void aNewAreaIsFoundByComparingTheBoardBeforeAndAfter() {
        todo.area("Privat");
        TodoBoard before = client.board();

        TodoClient.Created created = client.createArea(before, "Server");

        assertThat(todo.areas).extracting(FakeTodoServer.Area::name).containsExactly("Privat", "Server");
        assertThat(created.id()).isEqualTo(todo.areas.get(1).id());
        assertThat(created.board().area("Server")).isPresent();
    }

    /**
     * Legt Felix im selben Moment selbst eine Aufgabe an, kommen zwei neue Ids zurueck -
     * die richtige ist die mit Titel und Elternaufgabe der Anfrage.
     */
    @Test
    void aNewTodoIsTheOneWithItsTitleAndParentEvenIfAnotherAppearedMeanwhile() {
        FakeTodoServer.Area server = todo.area("Server");
        FakeTodoServer.Todo healthy = todo.todo(server, null, "Healthy");
        TodoBoard before = client.board();
        todo.sneakInATodo = true;

        TodoClient.Created created = client.createTodo(before, server.id(), healthy.id, "Dunkles Widget",
                "https://fherrmann.com/feature-requests/abc", null);

        FakeTodoServer.Todo stored = todo.find(created.id());
        assertThat(stored.title).isEqualTo("Dunkles Widget");
        assertThat(stored.parentId).isEqualTo(healthy.id);
        assertThat(stored.link).isEqualTo("https://fherrmann.com/feature-requests/abc");
        assertThat(todo.children(healthy)).hasSize(2);
    }

    @Test
    void aTopLevelTodoIsSentWithoutParentAndLink() {
        FakeTodoServer.Area server = todo.area("Server");
        TodoClient.Created created = client.createTodo(client.board(), server.id(), null, "Healthy", null, null);

        FakeTodoServer.Todo stored = todo.find(created.id());
        assertThat(stored.parentId).isNull();
        assertThat(stored.link).isNull();
        assertThat(todo.notifications).as("ohne Benachrichtigung fehlt das Feld ganz").isEmpty();
    }

    @Test
    void aNotificationGoesAlongAsTitleAndBody() {
        FakeTodoServer.Area server = todo.area("Server");
        TodoClient.Created created = client.createTodo(client.board(), server.id(), null, "Dunkles Widget",
                "https://fherrmann.com/feature-requests/abc",
                new TodoClient.Notification("Feature Request · Healthy", "Torben: Dunkles Widget"));

        assertThat(todo.notifications.get(created.id()).path("title").asString()).isEqualTo("Feature Request · Healthy");
        assertThat(todo.notifications.get(created.id()).path("body").asString()).isEqualTo("Torben: Dunkles Widget");
    }

    /** Das To-Do schickt Fehler als Klartext - der Grund steht dann in der Ausnahme und im Journal. */
    @Test
    void anErrorCarriesStatusAndPlainTextReason() {
        todo.area("Server");
        todo.failStatus = 404;
        todo.failBody = "Bereich nicht gefunden.";

        assertThatThrownBy(() -> client.board())
                .isInstanceOf(TodoException.class)
                .hasMessageContaining("404")
                .hasMessageContaining("Bereich nicht gefunden.");
    }

    @Test
    void aWrongTokenIsAnErrorToo() {
        TodoClient wrong = new TodoClient(todo.baseUrl(), "falsch", new ObjectMapper());
        assertThatThrownBy(wrong::board).isInstanceOf(TodoException.class).hasMessageContaining("403");
    }

    @Test
    void anUnreachableServiceIsATodoException() {
        String url = todo.baseUrl();
        todo.close();
        TodoClient gone = new TodoClient(url, FakeTodoServer.TOKEN, new ObjectMapper());
        assertThatThrownBy(gone::board).isInstanceOf(TodoException.class).hasMessageContaining("nicht erreichbar");
    }

    @Test
    void withoutAnAddressThereIsNoTodo() {
        TodoClient none = new TodoClient("", FakeTodoServer.TOKEN, new ObjectMapper());
        assertThat(none.isConfigured()).isFalse();
        assertThatThrownBy(none::board).isInstanceOf(TodoException.class);
    }

    /** Jackson 2 schrieb Zeitpunkte als Sekunden - auch das ist "abgehakt". */
    @Test
    void doneAtAsEpochSecondsIsReadToo() throws Exception {
        TodoBoard board = TodoBoard.parse(new ObjectMapper().readTree("""
                {"areas":[{"id":"a","name":"Server","todos":[
                  {"id":"t","title":"Healthy","doneAt":1767323045.5,"children":[]}]}]}
                """));
        assertThat(board.todo("t").orElseThrow().doneAt()).isEqualTo(Instant.ofEpochSecond(1767323045L, 500_000_000));
    }
}

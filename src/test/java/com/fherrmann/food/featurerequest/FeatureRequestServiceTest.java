package com.fherrmann.food.featurerequest;

import com.fherrmann.food.featurerequest.FeatureRequestDtos.FeatureRequestView;
import com.fherrmann.food.featurerequest.FeatureRequestDtos.NewFeatureRequest;
import com.fherrmann.food.security.HealthUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeatureRequestServiceTest {

    private static final HealthUsers USERS = new HealthUsers("felix",
            "torben:0123456789abcdef0123456789abcdef,joana:fedcba9876543210fedcba9876543210");

    @TempDir
    Path tempDir;

    private FeatureRequestRepository repository;
    private FeatureRequestTodos todos;
    private FeatureRequestService service;

    @BeforeEach
    void setUp() {
        repository = new FeatureRequestRepository(tempDir.resolve("feature-requests.json").toString(), new ObjectMapper());
        todos = mock(FeatureRequestTodos.class);
        // Wie das Original: die Unteraufgabe wird angelegt und ihre Id gespeichert.
        when(todos.ensureTodo(any())).thenAnswer(call -> {
            FeatureRequest request = call.getArgument(0);
            return repository.update(request.id(), r -> r.withTodoId("t-new")).orElseThrow();
        });
        when(todos.cardUrl(anyString())).thenAnswer(call -> "https://fherrmann.com/feature-requests/" + call.getArgument(0));
        when(todos.board()).thenReturn(Optional.empty());
        service = new FeatureRequestService(repository, todos, USERS,
                Clock.fixed(Instant.parse("2026-09-26T12:00:00Z"), ZoneOffset.UTC));
    }

    private static NewFeatureRequest card(String title) {
        return new NewFeatureRequest("Ich hätte gern …", title, "Als Android-Nutzer möchte ich …, damit …",
                List.of("Kriterium eins", "Kriterium zwei"));
    }

    private static String repeat(int length) {
        return "x".repeat(length);
    }

    // MARK: - Anlegen

    @Test
    void aCardIsStoredWithAuthorOriginalTextAndTodo() {
        FeatureRequestView view = service.create("torben", card("Dunkles Widget"));

        FeatureRequest stored = repository.find(view.id()).orElseThrow();
        assertThat(stored.author()).isEqualTo("torben");
        assertThat(stored.originalText()).isEqualTo("Ich hätte gern …");
        assertThat(stored.acceptanceCriteria()).containsExactly("Kriterium eins", "Kriterium zwei");
        assertThat(stored.createdAt()).isEqualTo(Instant.parse("2026-09-26T12:00:00Z"));
        assertThat(view.status()).isEqualTo(FeatureRequestView.OPEN);
        assertThat(view.inTodo()).isTrue();
        assertThat(view.url()).isEqualTo("https://fherrmann.com/feature-requests/" + view.id());
    }

    /** Erst gespeichert, dann ans To-Do: faellt es aus, ist die Anfrage trotzdem da. */
    @Test
    void theRequestIsStoredBeforeTheTodoIsAsked() {
        List<Boolean> storedWhenAsked = new ArrayList<>();
        doAnswer(call -> {
            FeatureRequest request = call.getArgument(0);
            storedWhenAsked.add(repository.find(request.id()).isPresent());
            return request;
        }).when(todos).ensureTodo(any());

        FeatureRequestView view = service.create("torben", card("Trotzdem gespeichert"));

        assertThat(storedWhenAsked).containsExactly(true);
        assertThat(view.inTodo()).isFalse();
        assertThat(repository.all()).hasSize(1);
    }

    // MARK: - Pruefungen

    @Test
    void titleIsRequiredAndAtMost120Characters() {
        assertBadRequest(card("   "), "Titel");
        assertBadRequest(card(null), "Titel");
        assertBadRequest(card(repeat(121)), "120");
        assertThat(service.create("torben", card(repeat(120))).title()).hasSize(120);
    }

    @Test
    void aTitleIsOneLine() {
        assertThat(service.create("torben", card("  Zwei\n  Zeilen  ")).title()).isEqualTo("Zwei Zeilen");
    }

    @Test
    void storyIsRequiredAndAtMost2000Characters() {
        assertBadRequest(new NewFeatureRequest("", "T", " ", List.of()), "User Story");
        assertBadRequest(new NewFeatureRequest("", "T", repeat(2001), List.of()), "2000");
        assertThat(service.create("torben", new NewFeatureRequest("", "T", repeat(2000), null)).story()).hasSize(2000);
    }

    @Test
    void atMostTenCriteriaOfAtMost300Characters() {
        assertBadRequest(new NewFeatureRequest("", "T", "S", Collections.nCopies(11, "k")), "10");
        assertBadRequest(new NewFeatureRequest("", "T", "S", List.of(repeat(301))), "300");
        FeatureRequestView view = service.create("torben",
                new NewFeatureRequest("", "T", "S", Collections.nCopies(10, repeat(300))));
        assertThat(view.acceptanceCriteria()).hasSize(10);
    }

    /** Leere Zeilen aus dem Editor sind kein Kriterium - und kein Grund, die Karte abzulehnen. */
    @Test
    void blankCriteriaAreDropped() {
        List<String> criteria = new ArrayList<>(Collections.nCopies(10, "k"));
        criteria.addAll(List.of("", "  "));
        criteria.add(1, null);
        FeatureRequestView view = service.create("torben", new NewFeatureRequest("", "T", "S", criteria));
        assertThat(view.acceptanceCriteria()).hasSize(10);
    }

    @Test
    void theOriginalTextIsOptionalAndAtMost4000Characters() {
        assertBadRequest(new NewFeatureRequest(repeat(4001), "T", "S", List.of()), "4000");
        assertThat(service.create("torben", new NewFeatureRequest(null, "T", "S", List.of())).originalText()).isEmpty();
        assertThat(service.create("torben", new NewFeatureRequest(repeat(4000), "T", "S", List.of())).originalText())
                .hasSize(4000);
    }

    @Test
    void aMissingBodyIsABadRequest() {
        assertThatThrownBy(() -> service.create("torben", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
    }

    private void assertBadRequest(NewFeatureRequest request, String reasonPart) {
        assertThatThrownBy(() -> service.create("torben", request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400")
                .satisfies(e -> assertThat(((ResponseStatusException) e).getReason()).contains(reasonPart));
        verify(todos, never()).ensureTodo(any());
    }

    // MARK: - Wer sieht was

    @Test
    void everyoneSeesTheirOwnAndTheOwnerSeesAll() {
        service.create("torben", card("Von Torben"));
        service.create("felix", card("Von Felix"));
        service.create("joana", card("Von Joana"));

        assertThat(service.list("torben")).extracting(FeatureRequestView::title).containsExactly("Von Torben");
        assertThat(service.list("joana")).extracting(FeatureRequestView::title).containsExactly("Von Joana");
        assertThat(service.list("felix")).extracting(FeatureRequestView::title)
                .containsExactlyInAnyOrder("Von Torben", "Von Felix", "Von Joana");
    }

    @Test
    void theListShowsTheNewestFirst() {
        FeatureRequest older = new FeatureRequest("a", "torben", Instant.parse("2026-09-01T10:00:00Z"),
                "", "Älter", "S", List.of(), null, null);
        FeatureRequest newer = new FeatureRequest("b", "torben", Instant.parse("2026-09-20T10:00:00Z"),
                "", "Neuer", "S", List.of(), null, null);
        repository.add(older);
        repository.add(newer);

        assertThat(service.list("torben")).extracting(FeatureRequestView::title).containsExactly("Neuer", "Älter");
    }

    @Test
    void aForeignCardLooksLikeAMissingOne() {
        String torbens = service.create("torben", card("Nur für Torben und Felix")).id();

        assertThat(service.get("torben", torbens).title()).isEqualTo("Nur für Torben und Felix");
        assertThat(service.get("felix", torbens).author()).isEqualTo("torben");
        assertThatThrownBy(() -> service.get("joana", torbens))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        assertThatThrownBy(() -> service.get("torben", "gibt-es-nicht"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    /** Die Liste fragt das Brett einmal, nicht je Anfrage - und ohne Anfragen gar nicht. */
    @Test
    void theBoardIsReadOncePerList() {
        assertThat(service.list("torben")).isEmpty();
        verify(todos, never()).board();

        service.create("torben", card("Eins"));
        service.create("torben", card("Zwei"));
        service.list("torben");
        verify(todos, org.mockito.Mockito.times(1)).board();
    }

    // MARK: - Stand

    private static FeatureRequest request(String todoId, Instant rememberedDoneAt) {
        return new FeatureRequest("r", "torben", Instant.now(), "", "T", "S", List.of(), todoId, rememberedDoneAt);
    }

    private static TodoBoard board(TodoBoard.Todo... todos) {
        return new TodoBoard(List.of(new TodoBoard.Area("a", "Server", List.of(todos))));
    }

    @Test
    void aTickedSubtaskMeansDone() {
        Instant ticked = Instant.parse("2026-09-26T18:00:00Z");
        FeatureRequestService.Status status = FeatureRequestService.status(request("t", null),
                Optional.of(board(new TodoBoard.Todo("t", "p", "T", ticked, null))));
        assertThat(status.value()).isEqualTo(FeatureRequestView.DONE);
        assertThat(status.doneAt()).isEqualTo(ticked);
    }

    /** Das Brett gewinnt gegen den gemerkten Stand - auch in die andere Richtung (Haken zurueck). */
    @Test
    void anUntickedSubtaskMeansOpenAgainWhateverWasRemembered() {
        FeatureRequestService.Status status = FeatureRequestService.status(request("t", Instant.now()),
                Optional.of(board(new TodoBoard.Todo("t", "p", "T", null, null))));
        assertThat(status.value()).isEqualTo(FeatureRequestView.OPEN);
        assertThat(status.doneAt()).isNull();
    }

    @Test
    void withoutTheBoardTheRememberedStateCounts() {
        Instant remembered = Instant.parse("2026-09-25T08:00:00Z");
        assertThat(FeatureRequestService.status(request("t", remembered), Optional.empty()).value())
                .isEqualTo(FeatureRequestView.DONE);
        assertThat(FeatureRequestService.status(request("t", null), Optional.empty()).value())
                .isEqualTo(FeatureRequestView.OPEN);
    }

    /** Geloeschte Unteraufgabe: nicht auf dem Brett, also bleibt der letzte bekannte Stand. */
    @Test
    void aDeletedSubtaskKeepsTheRememberedState() {
        Instant remembered = Instant.parse("2026-09-25T08:00:00Z");
        assertThat(FeatureRequestService.status(request("t", remembered), Optional.of(board())).value())
                .isEqualTo(FeatureRequestView.DONE);
    }

    @Test
    void aRequestWithoutSubtaskIsOpen() {
        assertThat(FeatureRequestService.status(request(null, null), Optional.of(board())).value())
                .isEqualTo(FeatureRequestView.OPEN);
    }

    @Test
    void theListReadsTheStateFromTheBoard() {
        FeatureRequestView created = service.create("torben", card("Abgehakt"));
        Instant ticked = Instant.parse("2026-09-26T18:00:00Z");
        when(todos.board()).thenReturn(Optional.of(board(new TodoBoard.Todo("t-new", "p", "Abgehakt", ticked, null))));

        FeatureRequestView listed = service.list("torben").getFirst();
        assertThat(listed.id()).isEqualTo(created.id());
        assertThat(listed.status()).isEqualTo(FeatureRequestView.DONE);
        assertThat(listed.doneAt()).isEqualTo(ticked);
        assertThat(service.get("torben", created.id()).status()).isEqualTo(FeatureRequestView.DONE);
    }
}

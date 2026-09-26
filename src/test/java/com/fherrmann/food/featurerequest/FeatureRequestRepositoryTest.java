package com.fherrmann.food.featurerequest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FeatureRequestRepositoryTest {

    @TempDir
    Path tempDir;

    private static FeatureRequest request(String id, String author) {
        return new FeatureRequest(id, author, Instant.parse("2026-09-26T12:00:00Z"), "ich will",
                "Titel " + id, "Als … möchte ich …", List.of("eins", "zwei"), null, null);
    }

    @Test
    void withoutAFileThereAreNoRequests() {
        FeatureRequestRepository repository = new FeatureRequestRepository(
                tempDir.resolve("fehlt.json").toString(), new ObjectMapper());
        assertThat(repository.all()).isEmpty();
        assertThat(repository.find("x")).isEmpty();
    }

    @Test
    void requestsRoundTripThroughOneFileForEveryone() {
        Path file = tempDir.resolve("sub").resolve("feature-requests.json");
        FeatureRequestRepository repository = new FeatureRequestRepository(file.toString(), new ObjectMapper());
        repository.add(request("a", "torben"));
        repository.add(request("b", "felix"));

        FeatureRequestRepository reopened = new FeatureRequestRepository(file.toString(), new ObjectMapper());
        assertThat(reopened.all()).extracting(FeatureRequest::author).containsExactly("torben", "felix");
        assertThat(reopened.find("a")).contains(request("a", "torben"));
    }

    /** Geschrieben wird daneben und dann umbenannt - es bleibt keine halbe oder temporaere Datei liegen. */
    @Test
    void updatesAreWrittenAtomically() throws Exception {
        Path file = tempDir.resolve("feature-requests.json");
        FeatureRequestRepository repository = new FeatureRequestRepository(file.toString(), new ObjectMapper());
        repository.add(request("a", "torben"));
        repository.add(request("b", "torben"));

        assertThat(repository.update("b", r -> r.withTodoId("t-1").withDoneAt(Instant.parse("2026-09-27T08:00:00Z"))))
                .hasValueSatisfying(r -> assertThat(r.todoId()).isEqualTo("t-1"));
        assertThat(repository.update("gibt-es-nicht", r -> r.withTodoId("x"))).isEmpty();

        assertThat(repository.find("a").orElseThrow().todoId()).isNull();
        assertThat(repository.find("b").orElseThrow().doneAt()).isEqualTo(Instant.parse("2026-09-27T08:00:00Z"));
        try (var files = Files.list(tempDir)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("feature-requests.json");
        }
    }
}

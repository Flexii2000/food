package com.fherrmann.food.featurerequest;

import com.fherrmann.food.featurerequest.FeatureRequestDtos.DraftJob;
import com.fherrmann.food.featurerequest.FeatureRequestDtos.StoryCard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoryDraftJobsTest {

    private static final StoryCard CARD = new StoryCard("Dunkles Widget",
            "Als Android-Nutzer möchte ich ein dunkles Widget, damit es nachts nicht blendet.",
            List.of("Das Widget folgt dem Systemdesign."));

    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void tearDown() {
        release.countDown();
    }

    /** Ein Agent, der tut, was der Test ihm sagt, und mitzaehlt. */
    private static final class FakeAgent implements StoryAgent {
        final AtomicInteger calls = new AtomicInteger();
        final boolean available;
        final BiFunction<String, String, StoryCard> answer;

        FakeAgent(boolean available, BiFunction<String, String, StoryCard> answer) {
            this.available = available;
            this.answer = answer;
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public StoryCard draft(String author, String wish) {
            calls.incrementAndGet();
            return answer.apply(author, wish);
        }
    }

    private static DraftJob awaitFinished(StoryDraftJobs jobs, String user, String id) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            DraftJob job = jobs.status(user, id);
            if (!DraftJob.RUNNING.equals(job.status())) {
                return job;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Entwurf wurde nicht fertig");
    }

    @Test
    void theDraftGoesToThePersonWhoAskedAndOnlyThere() throws Exception {
        FakeAgent agent = new FakeAgent(true, (author, wish) -> CARD);
        StoryDraftJobs jobs = new StoryDraftJobs(agent, Clock.systemUTC());

        DraftJob started = jobs.start("torben", "  ein dunkles Widget bitte  ");
        assertThat(started.status()).isEqualTo(DraftJob.RUNNING);
        assertThat(started.jobId()).isNotBlank();

        DraftJob done = awaitFinished(jobs, "torben", started.jobId());
        assertThat(done.status()).isEqualTo(DraftJob.DONE);
        assertThat(done.card()).isEqualTo(CARD);
        assertThat(done.error()).isNull();

        // Den Auftrag einer anderen Person gibt es fuer einen nicht - nicht einmal als 403.
        assertThatThrownBy(() -> jobs.status("felix", started.jobId()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    void theAgentGetsAuthorAndTrimmedWish() throws Exception {
        StringBuilder seen = new StringBuilder();
        StoryDraftJobs jobs = new StoryDraftJobs(new FakeAgent(true, (author, wish) -> {
            seen.append(author).append('|').append(wish);
            return CARD;
        }), Clock.systemUTC());

        awaitFinished(jobs, "torben", jobs.start("torben", "\n  Export als CSV \n").jobId());

        assertThat(seen.toString()).isEqualTo("torben|Export als CSV");
    }

    /** Scheitert der Entwurf, kommt die deutsche Meldung an - die Seite oeffnet dann den leeren Editor. */
    @Test
    void aFailedDraftCarriesItsReason() throws Exception {
        StoryDraftJobs jobs = new StoryDraftJobs(new FakeAgent(true, (author, wish) -> {
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Der Entwurf hat länger als 120 Sekunden gedauert.");
        }), Clock.systemUTC());

        DraftJob failed = awaitFinished(jobs, "torben", jobs.start("torben", "irgendwas").jobId());

        assertThat(failed.status()).isEqualTo(DraftJob.FAILED);
        assertThat(failed.error()).isEqualTo("Der Entwurf hat länger als 120 Sekunden gedauert.");
        assertThat(failed.card()).isNull();
    }

    @Test
    void anUnexpectedErrorBecomesAPlainMessage() throws Exception {
        StoryDraftJobs jobs = new StoryDraftJobs(new FakeAgent(true, (author, wish) -> {
            throw new IllegalStateException("kaputt");
        }), Clock.systemUTC());

        DraftJob failed = awaitFinished(jobs, "torben", jobs.start("torben", "irgendwas").jobId());

        assertThat(failed.error()).isEqualTo("Der Entwurf ist fehlgeschlagen.");
    }

    @Test
    void anEmptyOrOverlongWishIsRejectedBeforeAnythingRuns() {
        FakeAgent agent = new FakeAgent(true, (author, wish) -> CARD);
        StoryDraftJobs jobs = new StoryDraftJobs(agent, Clock.systemUTC());

        assertThatThrownBy(() -> jobs.start("torben", "   ")).hasMessageContaining("400");
        assertThatThrownBy(() -> jobs.start("torben", null)).hasMessageContaining("400");
        assertThatThrownBy(() -> jobs.start("torben", "x".repeat(4001))).hasMessageContaining("400");
        assertThat(agent.calls).hasValue(0);
    }

    @Test
    void withoutAnAgentThereIsNoDraft() {
        StoryDraftJobs jobs = new StoryDraftJobs(new FakeAgent(false, (author, wish) -> CARD), Clock.systemUTC());

        assertThat(jobs.isAvailable()).isFalse();
        assertThatThrownBy(() -> jobs.start("torben", "ein Wunsch")).hasMessageContaining("503");
    }

    /** Eine Seite, die sich verheddert, soll keine Warteschlange voller Sessions aufbauen. */
    @Test
    void atMostThreeOpenDraftsPerPerson() throws Exception {
        FakeAgent agent = new FakeAgent(true, (author, wish) -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return CARD;
        });
        StoryDraftJobs jobs = new StoryDraftJobs(agent, Clock.systemUTC());

        for (int i = 0; i < StoryDraftJobs.MAX_OPEN_PER_PERSON; i++) {
            jobs.start("torben", "Wunsch " + i);
        }
        assertThatThrownBy(() -> jobs.start("torben", "noch einer"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("429");
        // Die Grenze gilt je Person.
        DraftJob felix = jobs.start("felix", "meiner");

        release.countDown();
        assertThat(awaitFinished(jobs, "felix", felix.jobId()).status()).isEqualTo(DraftJob.DONE);
        // Wieder frei, sobald die eigenen fertig sind.
        assertThat(jobs.start("torben", "jetzt wieder").status()).isEqualTo(DraftJob.RUNNING);
    }
}

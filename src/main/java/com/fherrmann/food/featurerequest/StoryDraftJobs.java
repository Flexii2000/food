package com.fherrmann.food.featurerequest;

import com.fherrmann.food.featurerequest.FeatureRequestDtos.DraftJob;
import com.fherrmann.food.featurerequest.FeatureRequestDtos.StoryCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fuehrt Entwuerfe im Hintergrund aus und haelt ihren Stand bereit - dasselbe Muster
 * wie die Schnellerfassung ({@code QuickCaptureJobs}): Start antwortet sofort mit
 * einer Auftragsnummer, die Seite fragt nach. Eine Claude-Session dauert eine halbe
 * Minute und mehr, und eine so lange offene Anfrage scheitert an jeder
 * Zwischenstation mit eigenem Timeout, ohne verwertbaren Fehler.
 *
 * <p>Ein Arbeitsthread fuer alle: jeder Entwurf laeuft auf Felix' Claude-Login und
 * kostet. Dazu eine Obergrenze je Person, damit eine Seite, die sich verheddert,
 * keine Warteschlange voller Sessions aufbaut.
 *
 * <p>Die Auftraege liegen nur im Speicher. Nach einem Neustart sind sie weg; die
 * Seite bekommt dann ein 404 und oeffnet den Editor zum Selbstschreiben.
 */
@Component
public class StoryDraftJobs {

    private static final Logger log = LoggerFactory.getLogger(StoryDraftJobs.class);

    static final int MAX_WISH = FeatureRequestService.MAX_ORIGINAL;

    /** Wie lange ein fertiger Entwurf abholbar bleibt. */
    private static final Duration RETENTION = Duration.ofMinutes(10);

    /** Obergrenze fuer den Speicher, falls irgendetwas Auftraege in Massen startet. */
    private static final int MAX_JOBS = 50;

    /**
     * So viele laufende oder wartende Entwuerfe darf eine Person haben. Mehr als einer,
     * weil ein Neuladen der Seite den laufenden aus den Augen verliert und ein
     * zweiter Versuch dann nicht abgewiesen werden soll.
     */
    static final int MAX_OPEN_PER_PERSON = 3;

    private final StoryAgent agent;
    private final Clock clock;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "story-draft");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    public StoryDraftJobs(StoryAgent agent, Clock clock) {
        this.agent = agent;
        this.clock = clock;
    }

    /** Veraenderliche Felder, weil der Arbeitsthread sie nachtraegt. */
    private static final class Job {
        private final String owner;
        private final Instant startedAt;
        private volatile String status = DraftJob.RUNNING;
        private volatile StoryCard card;
        private volatile String error;
        private volatile Instant finishedAt;

        private Job(String owner, Instant startedAt) {
            this.owner = owner;
            this.startedAt = startedAt;
        }
    }

    public boolean isAvailable() {
        return agent.isAvailable();
    }

    /**
     * Nimmt einen Entwurf an und gibt sofort zurueck. Die Eingabe wird dabei schon
     * geprueft - ein leerer Text soll als 400 ankommen und nicht erst eine halbe
     * Minute spaeter als fehlgeschlagener Entwurf.
     */
    public DraftJob start(String user, String text) {
        if (!agent.isAvailable()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Entwürfe sind auf diesem Server nicht eingerichtet.");
        }
        String wish = text == null ? "" : text.strip();
        if (wish.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bitte den Wunsch beschreiben.");
        }
        if (wish.length() > MAX_WISH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Der Wunsch darf höchstens " + MAX_WISH + " Zeichen haben.");
        }
        purge();
        long open = jobs.values().stream()
                .filter(j -> j.owner.equals(user) && DraftJob.RUNNING.equals(j.status))
                .count();
        if (open >= MAX_OPEN_PER_PERSON) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Es laufen schon Entwürfe – bitte kurz warten.");
        }

        String id = UUID.randomUUID().toString();
        Job job = new Job(user, clock.instant());
        jobs.put(id, job);

        executor.submit(() -> {
            try {
                job.card = agent.draft(user, wish);
                job.status = DraftJob.DONE;
            } catch (ResponseStatusException e) {
                job.error = e.getReason() == null ? "Der Entwurf ist fehlgeschlagen." : e.getReason();
                job.status = DraftJob.FAILED;
            } catch (RuntimeException e) {
                log.warn("Story-Entwurf {} fehlgeschlagen", id, e);
                job.error = "Der Entwurf ist fehlgeschlagen.";
                job.status = DraftJob.FAILED;
            } finally {
                job.finishedAt = clock.instant();
            }
        });

        return view(id, job);
    }

    /** Der Stand eines Entwurfs. Der einer anderen Person sieht aus wie ein unbekannter. */
    public DraftJob status(String user, String id) {
        Job job = jobs.get(id);
        if (job == null || !job.owner.equals(user)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Diesen Entwurf gibt es nicht mehr – lief der Server zwischendurch neu?");
        }
        return view(id, job);
    }

    private DraftJob view(String id, Job job) {
        Instant until = job.finishedAt == null ? clock.instant() : job.finishedAt;
        // Erst den Stand lesen, dann das Ergebnis: der Arbeitsthread schreibt es davor.
        String status = job.status;
        return new DraftJob(id, status,
                DraftJob.DONE.equals(status) ? job.card : null,
                DraftJob.FAILED.equals(status) ? job.error : null,
                Duration.between(job.startedAt, until).toSeconds());
    }

    /** Alte Auftraege wegraeumen, und notfalls die aeltesten, wenn es zu viele werden. */
    private void purge() {
        Instant cutoff = clock.instant().minus(RETENTION);
        jobs.entrySet().removeIf(e -> e.getValue().startedAt.isBefore(cutoff));
        while (jobs.size() >= MAX_JOBS) {
            jobs.entrySet().stream()
                    .min(Map.Entry.comparingByValue(Comparator.comparing((Job j) -> j.startedAt)))
                    .map(Map.Entry::getKey)
                    .ifPresent(jobs::remove);
        }
    }
}

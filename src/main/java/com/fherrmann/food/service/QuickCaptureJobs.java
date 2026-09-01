package com.fherrmann.food.service;

import com.fherrmann.food.dto.QuickCaptureJob;
import com.fherrmann.food.dto.QuickCapturePreview;
import com.fherrmann.food.dto.QuickCaptureRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fuehrt Schnellerfassungen im Hintergrund aus und haelt ihren Stand bereit.
 *
 * <p>Bewusst <b>ein</b> Arbeitsthread: jede Auswertung startet eine
 * Claude-Session, und die kostet Geld. Zwei Klicks kurz hintereinander sollen
 * sich anstellen, statt zwei Sessions parallel zu bezahlen. Ein Mensch traegt
 * ohnehin nacheinander ein.
 *
 * <p>Die Auftraege liegen nur im Speicher. Ein Neustart verliert sie - die
 * Oberflaeche bekommt dann ein 404 und sagt das auch, statt endlos zu warten.
 * Fuer eine Auswertung, die eine Minute dauert, waere alles andere
 * unverhaeltnismaessig.
 */
@Component
public class QuickCaptureJobs {

    private static final Logger log = LoggerFactory.getLogger(QuickCaptureJobs.class);

    /** Wie lange ein fertiger Auftrag abholbar bleibt. */
    private static final Duration RETENTION = Duration.ofMinutes(10);

    /** Obergrenze, damit ein Fehler in der Oberflaeche den Speicher nicht vollschreibt. */
    private static final int MAX_JOBS = 50;

    private final FoodService service;
    private final Clock clock;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "quick-capture");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    public QuickCaptureJobs(FoodService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    /** Ein Auftrag samt Ergebnis. Veraenderliche Felder, weil der Thread sie nachtraegt. */
    private static final class Job {
        private final Instant startedAt;
        private volatile String status = QuickCaptureJob.RUNNING;
        private volatile QuickCapturePreview preview;
        private volatile String error;
        private volatile Instant finishedAt;

        private Job(Instant startedAt) {
            this.startedAt = startedAt;
        }
    }

    /**
     * Nimmt einen Auftrag an und gibt sofort zurueck. Die Eingabe wird dabei
     * schon geprueft - ein Tippfehler soll als 400 ankommen und nicht erst eine
     * Minute spaeter als fehlgeschlagener Auftrag.
     */
    public QuickCaptureJob start(QuickCaptureRequest request) {
        service.validateQuickCapture(request);
        purge();

        String id = UUID.randomUUID().toString();
        Job job = new Job(clock.instant());
        jobs.put(id, job);

        executor.submit(() -> {
            try {
                job.preview = service.quickCapture(request);
                job.status = QuickCaptureJob.DONE;
            } catch (ResponseStatusException e) {
                job.error = e.getReason() == null ? e.getMessage() : e.getReason();
                job.status = QuickCaptureJob.FAILED;
            } catch (RuntimeException e) {
                log.warn("Schnellerfassung {} fehlgeschlagen", id, e);
                job.error = "Die Auswertung ist fehlgeschlagen.";
                job.status = QuickCaptureJob.FAILED;
            } finally {
                job.finishedAt = clock.instant();
            }
        });

        return view(id, job);
    }

    /** Der Stand eines Auftrags. */
    public QuickCaptureJob status(String id) {
        Job job = jobs.get(id);
        if (job == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Diese Auswertung gibt es nicht mehr - lief der Server zwischendurch neu?");
        }
        return view(id, job);
    }

    private QuickCaptureJob view(String id, Job job) {
        Instant until = job.finishedAt == null ? clock.instant() : job.finishedAt;
        return new QuickCaptureJob(
                id, job.status, job.preview, job.error,
                Duration.between(job.startedAt, until).toSeconds());
    }

    /** Alte Auftraege wegraeumen, und notfalls die aeltesten, wenn es zu viele werden. */
    private void purge() {
        Instant cutoff = clock.instant().minus(RETENTION);
        jobs.entrySet().removeIf(e -> e.getValue().startedAt.isBefore(cutoff));
        while (jobs.size() >= MAX_JOBS) {
            jobs.entrySet().stream()
                    .min(Map.Entry.comparingByValue(
                            (a, b) -> a.startedAt.compareTo(b.startedAt)))
                    .map(Map.Entry::getKey)
                    .ifPresent(jobs::remove);
        }
    }
}

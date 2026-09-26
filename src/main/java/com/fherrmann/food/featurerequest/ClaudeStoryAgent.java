package com.fherrmann.food.featurerequest;

import com.fherrmann.food.featurerequest.FeatureRequestDtos.StoryCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Entwirft Story Cards durch eine <b>eigene Claude-Code-Session auf dem Server</b> -
 * dieselbe Bauart wie die Schnellerfassung, aber ein zweiter Agent in einem eigenen
 * Verzeichnis ({@code ~/services/story-agent}, Vorlage {@code deploy/story-agent}).
 *
 * <p>Nicht der Agent der Schnellerfassung: dessen {@code CLAUDE.md} ist ganz auf
 * Mahlzeiten zugeschnitten, und er darf im Netz nachschlagen. Dieser hier darf
 * nichts - kein Werkzeug, keine Datei, kein Netz. Er bekommt einen Text und gibt
 * eine Karte zurueck.
 *
 * <p>Der Prompt geht ueber die Standardeingabe: der Text kommt vom Nutzer, so gibt
 * es nichts zu escapen und keine Laengengrenze einer Kommandozeile.
 */
@Component
public class ClaudeStoryAgent implements StoryAgent {

    private static final Logger log = LoggerFactory.getLogger(ClaudeStoryAgent.class);

    /** Mehr schreibt keine Session, die eine Karte liefert - der Rest wird verworfen. */
    private static final int MAX_OUTPUT = 256 * 1024;

    /** Ein Nutzertext soll die Klammer nicht vorzeitig schliessen koennen. */
    private static final Pattern WISH_TAG = Pattern.compile("(?i)<\\s*(/?)\\s*wunsch\\s*>");

    private final List<String> command;
    private final long timeoutSeconds;
    private final ObjectMapper objectMapper;

    public ClaudeStoryAgent(
            @Value("${food.story-agent.command:}") String command,
            @Value("${food.story-agent.timeout-seconds:120}") long timeoutSeconds,
            ObjectMapper objectMapper) {
        this.command = command == null || command.isBlank()
                ? List.of()
                : List.of(command.trim().split("\\s+"));
        this.timeoutSeconds = timeoutSeconds;
        this.objectMapper = objectMapper;
        if (this.command.isEmpty()) {
            log.info("food.story-agent.command ist leer - Feature Requests ohne Claude-Entwurf.");
        }
    }

    @Override
    public boolean isAvailable() {
        // Nur "ist konfiguriert", keine Dateipruefung - wie bei der Schnellerfassung
        // liegt das Skript unter /home/flexii, wo der Dienstnutzer nicht hinsieht.
        // Ob der Weg ueber sudo traegt, prueft setup-feature-requests.sh und laesst
        // die Einstellung sonst leer.
        return !command.isEmpty();
    }

    @Override
    public StoryCard draft(String author, String wish) {
        if (!isAvailable()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Entwürfe sind auf diesem Server nicht eingerichtet.");
        }
        return parse(run(prompt(author, wish)));
    }

    /**
     * Der veraenderliche Teil des Auftrags. Was immer gilt, steht in der
     * {@code CLAUDE.md} des Agent-Verzeichnisses.
     */
    static String prompt(String author, String wish) {
        String text = WISH_TAG.matcher(wish == null ? "" : wish).replaceAll("[$1wunsch]");
        // Der Nutzertext kommt zuletzt und klar abgegrenzt: alles davor ist Angabe
        // der Anwendung, alles dazwischen Zitat.
        return "Der Wunsch kommt von " + displayName(author) + ".\n\n"
                + "<wunsch>\n" + text + "\n</wunsch>\n";
    }

    private static String displayName(String name) {
        if (name == null || name.isEmpty()) {
            return "einer Person ohne Namen";
        }
        return name.substring(0, 1).toUpperCase(Locale.GERMAN) + name.substring(1);
    }

    /** Startet die Session, schiebt den Prompt hinein und gibt aus, was zurueckkam. */
    private String run(String prompt) {
        Process process;
        try {
            // Fehlerausgabe mit einsammeln: laeuft etwas schief, steht der Grund
            // sonst nirgends.
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException e) {
            log.warn("Story-Agent nicht startbar: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Der Entwurf ist gerade nicht verfügbar.", e);
        }

        // Die Ausgabe nebenher lesen, nicht erst nach dem Warten: sonst haelt ein
        // Prozess, der nicht endet, auch das Lesen fest - und das Zeitlimit griffe nie.
        FutureTask<String> output = new FutureTask<>(() -> readLimited(process.getInputStream()));
        Thread.ofVirtual().name("story-agent-output").start(output);

        try {
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(prompt.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                // Die Session hat die Eingabe vorzeitig geschlossen - ihre Ausgabe sagt, warum.
                log.debug("Story-Agent nahm die Eingabe nicht vollstaendig an", e);
            }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                stop(process);
                throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,
                        "Der Entwurf hat länger als " + timeoutSeconds + " Sekunden gedauert.");
            }
            String text = output.get(10, TimeUnit.SECONDS);
            if (process.exitValue() != 0) {
                log.warn("Story-Agent beendet mit {}: {}", process.exitValue(), tail(text));
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Claude konnte keinen Entwurf erstellen.");
            }
            return text;
        } catch (InterruptedException e) {
            stop(process);
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Der Entwurf wurde abgebrochen.", e);
        } catch (ExecutionException | TimeoutException e) {
            stop(process);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Claude hat nicht geantwortet.", e);
        }
    }

    /**
     * Erst SIGTERM, dann SIGKILL. Die Reihenfolge zaehlt: der Prozess ist {@code sudo},
     * und sudo reicht SIGTERM an die Session weiter - SIGKILL traefe nur sudo, die
     * Session liefe als Waise zu Ende und kostete trotzdem.
     */
    private static void stop(Process process) {
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private static String readLimited(InputStream in) throws IOException {
        byte[] bytes = in.readNBytes(MAX_OUTPUT);
        in.transferTo(OutputStream.nullOutputStream());
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Aus der Ausgabe die Karte herausschaelen. Zwei Huellen sind moeglich: der
     * JSON-Umschlag von {@code claude --output-format json} (die Antwort steckt in
     * {@code result}) oder die blanke Antwort. Beides wird akzeptiert, damit ein
     * Wechsel des Ausgabeformats im Skript nichts bricht.
     */
    StoryCard parse(String output) {
        JsonNode outer = tryReadJson(output);
        if (outer == null) {
            // Eine Warnung auf stderr vor dem Umschlag - stderr laeuft mit ein.
            outer = tryReadJson(extractJsonObject(output));
        }
        String payload = output;
        if (outer != null && outer.has("result")) {
            String subtype = outer.path("subtype").asString("success");
            if (outer.path("is_error").asBoolean(false) || !"success".equals(subtype)) {
                log.warn("Story-Agent meldet einen Fehler ({}): {}", subtype, tail(outer.path("result").asString("")));
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Claude konnte keinen Entwurf erstellen.");
            }
            if (outer.has("total_cost_usd")) {
                log.info("Story-Entwurf fertig ({} ms, {} USD)",
                        outer.path("duration_ms").asLong(0), outer.path("total_cost_usd").asDouble(0));
            }
            payload = outer.path("result").asString("");
        }

        JsonNode node = tryReadJson(extractJsonObject(payload));
        if (node == null || !node.isObject()) {
            log.warn("Unverwertbare Antwort des Story-Agents: {}", tail(output));
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Die Antwort von Claude war nicht lesbar.");
        }

        // Die Grenzen stehen auch in der CLAUDE.md. Haelt sich das Modell nicht daran,
        // wird gekuerzt statt abgelehnt: der Entwurf ist ein Vorschlag, und die Person
        // sieht ihn vor dem Absenden ohnehin.
        String title = clamp(node.path("title").asString("").strip().replaceAll("\\s+", " "),
                FeatureRequestService.MAX_TITLE);
        String story = clamp(node.path("story").asString("").strip(), FeatureRequestService.MAX_STORY);
        List<String> criteria = new ArrayList<>();
        for (JsonNode entry : node.path("acceptanceCriteria")) {
            String criterion = entry.asString("").strip().replaceAll("\\s+", " ");
            if (!criterion.isEmpty() && criteria.size() < FeatureRequestService.MAX_CRITERIA) {
                criteria.add(clamp(criterion, FeatureRequestService.MAX_CRITERION));
            }
        }
        if (title.isEmpty() && story.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Claude hat keine Karte geliefert.");
        }
        return new StoryCard(title, story, criteria);
    }

    /** Kuerzt am letzten Wortende vor der Grenze und markiert es mit einer Ellipse. */
    static String clamp(String value, int max) {
        if (value.length() <= max) {
            return value;
        }
        String cut = value.substring(0, max - 1);
        int space = cut.lastIndexOf(' ');
        if (space > max / 2) {
            cut = cut.substring(0, space);
        }
        return cut.stripTrailing() + "…";
    }

    private JsonNode tryReadJson(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Das aeusserste JSON-Objekt aus einem Text schneiden. Ein Modell haengt gern einen
     * Satz davor oder packt die Antwort in einen Codeblock; beides ist harmlos, solange
     * man das Objekt findet.
     */
    private static String extractJsonObject(String value) {
        if (value == null) {
            return null;
        }
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        return start >= 0 && end > start ? value.substring(start, end + 1) : value;
    }

    private static String tail(String value) {
        String trimmed = value == null ? "" : value.strip();
        return trimmed.length() <= 500 ? trimmed : trimmed.substring(trimmed.length() - 500);
    }
}

package com.fherrmann.food.service;

import com.fherrmann.food.model.Dish;
import com.fherrmann.food.model.Nutrients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Wertet die Freitext-Schnellerfassung durch eine <b>Claude-Code-Session auf dem
 * Server</b> aus - dieselbe Bauart wie der taegliche Lauf des Finance Cockpits.
 *
 * <p>Der Aufruf geht nicht direkt an ein Modell, sondern an ein Wrapper-Skript
 * ({@code food.agent.command}), das {@code claude -p} in einem eigenen
 * Arbeitsverzeichnis startet. Dort liegen {@code CLAUDE.md} mit dem Fachkontext
 * und {@code .claude/settings.json} mit den Rechten. <b>Das ist der Punkt:</b>
 * die Leitplanken stehen im Verzeichnis, nicht im Prompt - der Agent kann sie
 * also nicht wegreden. Sein Rechteprofil erlaubt kein einziges Werkzeug; er kann
 * nichts lesen, nichts schreiben, nichts ausfuehren, nur antworten.
 *
 * <p>Der Prompt geht ueber die Standardeingabe statt als Argument: der Text
 * kommt vom Nutzer, und so gibt es keine Laengengrenze und nichts zu escapen.
 */
@Component
public class ClaudeSessionNutritionExtractor implements NutritionExtractor {

    private static final Logger log = LoggerFactory.getLogger(ClaudeSessionNutritionExtractor.class);

    /**
     * Wie viele bekannte Gerichte als Kontext mitgehen. Genug, damit Bekanntes
     * wiedererkannt wird, wenig genug, dass der Aufruf nicht mit einer wachsenden
     * Liste langsamer wird.
     */
    private static final int MAX_KNOWN_DISHES = 60;

    private final List<String> command;
    private final long timeoutSeconds;
    private final ObjectMapper objectMapper;

    public ClaudeSessionNutritionExtractor(
            @Value("${food.agent.command:}") String command,
            @Value("${food.agent.timeout-seconds}") long timeoutSeconds,
            ObjectMapper objectMapper) {
        this.command = command == null || command.isBlank()
                ? List.of()
                : List.of(command.trim().split("\\s+"));
        this.timeoutSeconds = timeoutSeconds;
        this.objectMapper = objectMapper;
        if (this.command.isEmpty()) {
            log.info("food.agent.command ist leer - Schnellerfassung ist deaktiviert.");
        }
    }

    @Override
    public boolean isAvailable() {
        // Nicht nur "konfiguriert", sondern "liegt auch da": sonst bietet die
        // Oberflaeche einen Knopf an, der erst beim Druecken scheitert.
        return !command.isEmpty() && Files.isExecutable(Path.of(command.getFirst()));
    }

    @Override
    public ExtractedDish extract(String text, Nutrients targets, List<Dish> known) {
        if (!isAvailable()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Schnellerfassung ist auf diesem Server nicht eingerichtet.");
        }

        String output = run(prompt(text, targets, known));
        return parse(output);
    }

    /** Startet die Session, schiebt den Prompt hinein und gibt aus, was zurueckkam. */
    private String run(String prompt) {
        Process process;
        try {
            process = new ProcessBuilder(command)
                    // Fehlerausgabe mit einsammeln: laeuft etwas schief, steht der
                    // Grund sonst nirgends, und die Meldung waere ein leeres Nichts.
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Agent-Skript nicht startbar.", e);
        }

        try {
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(prompt.getBytes(StandardCharsets.UTF_8));
            }
            String output = new String(
                    process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new ResponseStatusException(
                        HttpStatus.GATEWAY_TIMEOUT,
                        "Die Auswertung hat länger als " + timeoutSeconds + " Sekunden gebraucht.");
            }
            if (process.exitValue() != 0) {
                log.warn("Agent beendet mit {}: {}", process.exitValue(), tail(output));
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY, "Die Auswertung ist fehlgeschlagen.");
            }
            return output;
        } catch (IOException e) {
            process.destroyForcibly();
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "Der Agent hat nicht geantwortet.", e);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Auswertung abgebrochen.", e);
        }
    }

    /**
     * Aus der Ausgabe des Wrappers das Ergebnis herausschaelen. Zwei Huellen sind
     * moeglich: der JSON-Umschlag von {@code claude --output-format json} (dann
     * steckt die Antwort in {@code result}) oder die blanke Antwort. Beides wird
     * akzeptiert, damit ein Wechsel des Ausgabeformats im Wrapper nichts bricht.
     */
    private ExtractedDish parse(String output) {
        String payload = output;
        JsonNode envelope = tryReadJson(output);
        if (envelope != null && envelope.has("result")) {
            payload = envelope.path("result").asString("");
        }

        JsonNode node = tryReadJson(extractJsonObject(payload));
        if (node == null) {
            log.warn("Unverwertbare Agent-Antwort: {}", tail(output));
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "Die Antwort des Agents war nicht lesbar.");
        }

        String name = node.path("name").asString("").trim();
        if (name.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "Der Agent hat kein Gericht erkannt.");
        }
        double portion = node.path("portionG").asDouble(0);
        return new ExtractedDish(
                name,
                node.path("kcalPer100g").asDouble(0),
                node.path("proteinPer100g").asDouble(0),
                node.path("carbsPer100g").asDouble(0),
                node.path("fatPer100g").asDouble(0),
                node.path("grams").asDouble(0),
                portion > 0 ? portion : null,
                node.path("estimated").asBoolean(true),
                node.path("note").asString("").trim());
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
     * Das aeusserste JSON-Objekt aus einem Text schneiden. Ein Modell haengt gern
     * einen Satz davor oder packt die Antwort in einen Codeblock; beides ist
     * harmlos, solange man das Objekt findet, statt am Rest zu scheitern.
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

    /**
     * Der fachliche Teil des Auftrags. Die Regeln, die immer gelten, stehen in der
     * {@code CLAUDE.md} des Agent-Verzeichnisses; hier steht nur, was sich von
     * Aufruf zu Aufruf aendert - der Text, die Tagesziele und die schon
     * gespeicherten Gerichte.
     */
    private String prompt(String text, Nutrients targets, List<Dish> known) {
        StringBuilder sb = new StringBuilder();
        sb.append("Tagesziele: ")
                .append(fmt(targets.kcal())).append(" kcal, ")
                .append(fmt(targets.proteinG())).append(" g Eiweiss, ")
                .append(fmt(targets.carbsG())).append(" g Kohlenhydrate, ")
                .append(fmt(targets.fatG())).append(" g Fett.\n\n");

        if (known.isEmpty()) {
            sb.append("Bisher sind keine Gerichte gespeichert.\n\n");
        } else {
            sb.append("Bereits gespeicherte Gerichte (je 100 g). Passt die Beschreibung "
                    + "auf eines davon, nimm dessen Namen und Werte exakt so:\n");
            known.stream().limit(MAX_KNOWN_DISHES).forEach(d -> sb
                    .append("- ").append(d.name())
                    .append(": ").append(fmt(d.per100g().kcal())).append(" kcal, ")
                    .append(fmt(d.per100g().proteinG())).append(" g E, ")
                    .append(fmt(d.per100g().carbsG())).append(" g KH, ")
                    .append(fmt(d.per100g().fatG())).append(" g F")
                    .append(d.portionG() == null ? "" : ", Portion " + fmt(d.portionG()) + " g")
                    .append("\n"));
            sb.append("\n");
        }

        // Der Nutzertext kommt zuletzt und klar abgegrenzt: alles davor sind
        // Angaben der Anwendung, alles danach ist Zitat.
        sb.append("Diese Mahlzeit soll eingetragen werden:\n<beschreibung>\n")
                .append(text)
                .append("\n</beschreibung>\n");
        return sb.toString();
    }

    private static String fmt(double value) {
        return value == Math.rint(value)
                ? String.valueOf((long) value)
                : String.valueOf(Math.round(value * 10) / 10.0);
    }
}

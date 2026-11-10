package com.fherrmann.food.controller;

import com.fherrmann.food.dto.InternalPushRequest;
import com.fherrmann.food.dto.RecipeDishResult;
import com.fherrmann.food.dto.RecipeDishesRequest;
import com.fherrmann.food.push.PushNotifier;
import com.fherrmann.food.security.HealthUsers;
import com.fherrmann.food.service.FoodService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Was nur andere Dienste auf demselben Server rufen (Healthy-Vertrag §11.3). Wer hier
 * ankommt, hat die {@link com.fherrmann.food.security.InternalAccessFilter Schranke}
 * schon hinter sich; die Person, um die es geht, steht deshalb in der Anfrage und nicht
 * im Principal.
 *
 * <ul>
 *   <li>{@code /push} - der Weight Tracker laesst Mitteilungen zustellen (Chat-Antwort,
 *       Stupser, Serien-Warnung): nur der Kalorienzaehler kennt die Geraete.</li>
 *   <li>{@code /recipe-dishes} - die Einkaufsliste legt ein gekochtes Rezept als Gericht
 *       in die Tagebuecher der Personen, die mitessen.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/internal")
public class InternalController {

    private static final Logger log = LoggerFactory.getLogger(InternalController.class);

    /** Wie die Arten im Vertrag aussehen: klein, mit Bindestrichen. */
    private static final Pattern KIND = Pattern.compile("[a-z][a-z0-9-]{0,39}");
    private static final int MAX_TITLE = 200;
    private static final int MAX_BODY = 1_000;
    /** APNs nimmt 4 KB Nutzlast - die Daten daneben bleiben klein. */
    private static final int MAX_DATA_ENTRIES = 20;
    private static final int MAX_DATA_LENGTH = 500;
    /** Mehr Personen hat keine Einkaufsliste - alles darueber ist ein Irrtum. */
    private static final int MAX_PERSONS = 20;

    private final PushNotifier notifier;
    private final FoodService service;
    private final HealthUsers users;

    public InternalController(PushNotifier notifier, FoodService service, HealthUsers users) {
        this.notifier = notifier;
        this.service = service;
        this.users = users;
    }

    /**
     * Schickt eine Mitteilung an alle Geraete der Person. Die Antwort zaehlt, wie viele
     * Geraete sie angenommen bekommen haben - {@code 0/0} ist kein Fehler: dann hat die
     * Person kein angemeldetes Geraet, oder es ist kein Schluessel eingerichtet.
     */
    @PostMapping("/push")
    public PushNotifier.Delivery push(@RequestBody(required = false) InternalPushRequest request) {
        if (request == null) {
            throw badRequest("request body is required");
        }
        String person = request.person() == null ? "" : request.person().trim();
        if (person.isEmpty()) {
            throw badRequest("person is required");
        }
        if (!users.names().contains(person)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown person");
        }
        String kind = request.kind() == null ? "" : request.kind().trim();
        if (!KIND.matcher(kind).matches()) {
            throw badRequest("kind is required: lowercase letters, digits and hyphens");
        }
        String title = request.title() == null ? "" : request.title().trim();
        if (title.isEmpty() || title.length() > MAX_TITLE) {
            throw badRequest("title is required and must be at most " + MAX_TITLE + " characters");
        }
        String body = request.body() == null ? "" : request.body().trim();
        if (body.length() > MAX_BODY) {
            throw badRequest("body must be at most " + MAX_BODY + " characters");
        }
        Map<String, String> data = request.data() == null ? Map.of() : request.data();
        if (data.size() > MAX_DATA_ENTRIES) {
            throw badRequest("data must have at most " + MAX_DATA_ENTRIES + " entries");
        }
        data.forEach((key, value) -> {
            if (key == null || key.isBlank() || key.length() > 40
                    || (value != null && value.length() > MAX_DATA_LENGTH)) {
                throw badRequest("data keys must be 1-40 characters, values at most " + MAX_DATA_LENGTH);
            }
        });
        return notifier.send(person, kind, title, body, data);
    }

    /**
     * Legt das Gericht bei jeder genannten Person an oder bringt es auf den neuen Stand -
     * je Person ein Ergebnis, in der Reihenfolge der Anfrage. Scheitert es bei einer,
     * bleibt es bei den anderen trotzdem stehen.
     */
    @PostMapping("/recipe-dishes")
    public List<RecipeDishResult> recipeDishes(@RequestBody(required = false) RecipeDishesRequest request) {
        if (request == null || request.dish() == null) {
            throw badRequest("dish is required");
        }
        if (request.persons() == null || request.persons().isEmpty()) {
            throw badRequest("persons is required");
        }
        if (request.persons().size() > MAX_PERSONS) {
            throw badRequest("at most " + MAX_PERSONS + " persons");
        }
        Set<String> persons = new LinkedHashSet<>();
        for (String person : request.persons()) {
            if (person == null || person.isBlank()) {
                throw badRequest("persons must not contain empty names");
            }
            persons.add(person.trim());
        }
        Set<String> known = Set.copyOf(users.names());
        List<RecipeDishResult> results = new ArrayList<>();
        for (String person : persons) {
            if (!known.contains(person)) {
                results.add(RecipeDishResult.of(person, RecipeDishResult.NOT_LINKED));
                continue;
            }
            try {
                results.add(RecipeDishResult.of(person, service.putRecipeDish(person, request.dish())));
            } catch (ResponseStatusException e) {
                results.add(RecipeDishResult.rejected(person, e.getReason()));
            } catch (RuntimeException e) {
                // Eine kaputte oder nicht schreibbare Datei bei einer Person soll die
                // anderen nicht mitreissen.
                log.warn("Rezept-Gericht liess sich bei {} nicht ablegen", person, e);
                results.add(RecipeDishResult.rejected(person, "Das Tagebuch ließ sich nicht schreiben."));
            }
        }
        return results;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}

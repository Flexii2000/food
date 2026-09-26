package com.fherrmann.food.service;

import com.fherrmann.food.dto.DaySummary;
import com.fherrmann.food.dto.DayAverage;
import com.fherrmann.food.dto.DayTotal;
import com.fherrmann.food.dto.DishRequest;
import com.fherrmann.food.dto.NewEntryRequest;
import com.fherrmann.food.dto.QuickCaptureRequest;
import com.fherrmann.food.dto.QuickCapturePreview;
import com.fherrmann.food.dto.StatusInfo;
import com.fherrmann.food.dto.TargetsRequest;
import com.fherrmann.food.dto.UpdateEntryRequest;
import com.fherrmann.food.model.Dish;
import com.fherrmann.food.model.FoodData;
import com.fherrmann.food.model.FoodEntry;
import com.fherrmann.food.model.Meal;
import com.fherrmann.food.model.Nutrients;
import com.fherrmann.food.repository.FoodRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.io.IOException;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.Optional;
import java.util.UUID;

/**
 * The rules around dishes, entries and daily totals.
 *
 * <p>Every method takes the person whose diary it works on - the name the auth filter
 * put into the principal. There is no shared data between people: each has their own
 * targets, dish library and entries.
 *
 * <p>Every mutating method is {@code synchronized}: each one is a read-modify-write
 * cycle over the whole JSON file, and two of them interleaving would lose the earlier
 * change. The repository's own locking only covers a single read or write, not the
 * pair. One lock for everyone is plenty - a handful of people typing into food diaries.
 */
@Service
public class FoodService {

    private static final Logger log = LoggerFactory.getLogger(FoodService.class);

    /** Guards against a typo turning into a nonsense day total. Nothing edible comes close. */
    private static final double MAX_GRAMS = 20_000;
    private static final double MAX_KCAL_PER_100G = 1_000;
    private static final double MAX_MACRO_PER_100G = 100;
    private static final int MAX_NAME_LENGTH = 80;

    /** Genug fuer eine Mahlzeitbeschreibung; alles darueber ist kein Tagebucheintrag mehr. */
    private static final int MAX_QUICK_CAPTURE_LENGTH = 1000;

    private final FoodRepository repository;
    private final NutritionExtractor extractor;
    private final Clock clock;

    /** Hoechstens so gross darf ein Foto sein (dekodiert) - die App schickt ~200 kB. */
    static final int MAX_IMAGE_BYTES = 6 * 1024 * 1024;

    /**
     * Wohin ein Foto fuer die Dauer der Auswertung gelegt wird. Unter
     * {@code data/}, weil der Dienst nur dort schreiben darf
     * (systemd: ProtectSystem=strict, ReadWritePaths=/opt/food/data); der Agent
     * hat genau fuer diesen Ordner Leserecht (deploy/agent/.claude/settings.json).
     */
    private final Path inbox;

    public FoodService(FoodRepository repository, NutritionExtractor extractor, Clock clock) {
        this(repository, extractor, clock, Path.of(System.getProperty("java.io.tmpdir"), "food-inbox"));
    }

    @Autowired
    public FoodService(FoodRepository repository, NutritionExtractor extractor, Clock clock,
                       @Value("${food.agent.inbox:data/inbox}") Path inbox) {
        this.inbox = inbox;
        this.repository = repository;
        this.extractor = extractor;
        this.clock = clock;
    }

    /** Ob die Schnellerfassung eingerichtet ist - siehe {@link NutritionExtractor}. */
    public boolean quickCaptureAvailable() {
        return extractor.isAvailable();
    }

    // --- reading ------------------------------------------------------------

    /** Targets, totals and entries for one day. */
    public DaySummary day(String user, LocalDate date) {
        FoodData data = repository.load(user);
        LocalDate day = date == null ? today() : date;
        List<FoodEntry> entries = entriesOn(data, day);
        Nutrients consumed = sum(entries);
        return new DaySummary(
                day,
                data.targets().rounded(),
                consumed.rounded(),
                data.targets().minus(consumed).rounded(),
                entries,
                mealTargets(data));
    }

    /**
     * The dish library, most recently used first and never-used ones after them, each
     * group alphabetical. The picker is a flat list, so the sort is the only thing
     * keeping the handful of things eaten every week within reach.
     */
    public List<Dish> dishes(String user) {
        List<Dish> dishes = new ArrayList<>(repository.load(user).dishes());
        dishes.sort(Comparator
                .comparing(Dish::lastUsedOn, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(d -> d.name().toLowerCase()));
        return dishes;
    }

    public Nutrients targets(String user) {
        return repository.load(user).targets().rounded();
    }

    /**
     * Per-day totals across a date range, for the weight tracker's kcal overlay. Days
     * without any entry are left out rather than returned as zero: nothing logged means
     * "unknown", and drawing that as a 0 kcal day would be a lie in the chart.
     */
    public List<DayTotal> dailyTotals(String user, LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from and to are required, and to must not precede from");
        }
        Map<LocalDate, Nutrients> byDate = new LinkedHashMap<>();
        for (FoodEntry entry : repository.load(user).entries()) {
            LocalDate date = entry.date();
            if (date == null || date.isBefore(from) || date.isAfter(to)) {
                continue;
            }
            byDate.merge(date, entry.total(), Nutrients::plus);
        }
        return byDate.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new DayTotal(e.getKey(), e.getValue().rounded()))
                .toList();
    }

    /**
     * Fenster des gleitenden kcal-Mittels in Tagen, zentriert: drei Tage davor,
     * der Tag, drei danach. Dasselbe Fenster wie das 7-Tage-Mittel des Weight
     * Trackers, damit beide Kurven im selben Diagramm dieselben Tage abdecken.
     */
    public static final int AVERAGE_WINDOW_DAYS = 7;

    /**
     * Das gleitende kcal-Mittel je Tag im Zeitraum - was die Verlaufsdiagramme
     * statt der springenden Tageswerte zeigen. Gerechnet ueber den ganzen
     * Bestand, nicht nur ueber den angefragten Zeitraum: das Fenster des ersten
     * Tages reicht vor {@code from}.
     *
     * <p>Mitgezaehlt werden nur <em>abgeschlossene</em> Tage, also Tage vor
     * heute: der laufende Tag ist erst am Abend vollstaendig und wuerde das
     * Mittel bis dahin nach unten ziehen, und ein vorerfasster kuenftiger Tag
     * ist nur ein Plan. Tage, deren Fenster keinen einzigen solchen Eintrag
     * enthaelt, fehlen; Tage nach heute ebenfalls - ein Mittel fuer morgen aus
     * den Werten von gestern waere eine Prognose, die niemand bestellt hat.
     * {@code complete} ist erst gesetzt, wenn auch der letzte Tag des Fensters
     * abgeschlossen ist - die letzten vier Tage sind also vorlaeufig.
     */
    public List<DayAverage> dailyAverages(String user, LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from and to are required, and to must not precede from");
        }
        LocalDate today = today();
        Map<LocalDate, Double> kcalByDate = new HashMap<>();
        for (FoodEntry entry : repository.load(user).entries()) {
            // Nur abgeschlossene Tage: heute und spaeter bleiben draussen.
            if (entry.date() != null && entry.date().isBefore(today)) {
                kcalByDate.merge(entry.date(), entry.total().kcal(), Double::sum);
            }
        }
        int before = (AVERAGE_WINDOW_DAYS - 1) / 2;
        int after = AVERAGE_WINDOW_DAYS - 1 - before;
        List<DayAverage> result = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(to) && !day.isAfter(today); day = day.plusDays(1)) {
            double sum = 0;
            int days = 0;
            for (LocalDate d = day.minusDays(before); !d.isAfter(day.plusDays(after)); d = d.plusDays(1)) {
                Double kcal = kcalByDate.get(d);
                if (kcal != null) {
                    sum += kcal;
                    days++;
                }
            }
            if (days == 0) {
                continue;
            }
            boolean complete = day.plusDays(after).isBefore(today);
            result.add(new DayAverage(day, Math.round(sum / days), days, complete));
        }
        return result;
    }

    /** Snapshot for the statusboard card. */
    public StatusInfo status(String user) {
        LocalDate today = today();
        try {
            FoodData data = repository.load(user);
            List<FoodEntry> todays = entriesOn(data, today);
            LocalDate lastEntry = data.entries().stream()
                    .map(FoodEntry::date)
                    .filter(java.util.Objects::nonNull)
                    .max(Comparator.naturalOrder())
                    .orElse(null);
            return new StatusInfo(
                    "ok",
                    today,
                    sum(todays).rounded().kcal(),
                    data.targets().rounded().kcal(),
                    todays.size(),
                    data.dishes().size(),
                    lastEntry,
                    null);
        } catch (RuntimeException e) {
            return new StatusInfo("error", today, null, null, null, null, null, String.valueOf(e.getMessage()));
        }
    }

    // --- writing ------------------------------------------------------------

    /**
     * Logs an amount of a dish. Either an existing {@code dishId} or an inline
     * {@code dish} must be given; an inline one is stored in the library on the way
     * through, which is the whole "the server remembers it" behaviour - there is no
     * separate step for adding a dish before it can be eaten.
     */
    public synchronized DaySummary addEntry(String user, NewEntryRequest request) {
        if (request == null) {
            throw badRequest("request body is required");
        }
        double grams = requirePositive(request.grams(), "grams", MAX_GRAMS);
        LocalDate date = request.date() == null ? today() : request.date();

        FoodData data = repository.load(user);
        List<Dish> dishes = new ArrayList<>(data.dishes());

        Dish dish;
        if (request.dishId() != null && !request.dishId().isBlank()) {
            if (request.dish() != null) {
                throw badRequest("give either dishId or dish, not both");
            }
            dish = dishes.stream()
                    .filter(d -> d.id().equals(request.dishId()))
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown dishId"));
        } else if (request.dish() != null) {
            dish = upsertDish(dishes, request.dish(), null);
        } else {
            throw badRequest("either dishId or dish is required");
        }

        // Remember when it was last eaten so the picker can sort by it. Only moves
        // forward: back-filling a forgotten day must not push a dish to the top.
        LocalDate lastUsed = dish.lastUsedOn() == null || date.isAfter(dish.lastUsedOn())
                ? date
                : dish.lastUsedOn();
        replaceDish(dishes, dish.id(), new Dish(dish.id(), dish.name(), dish.per100g(), dish.portionG(), lastUsed));

        List<FoodEntry> entries = new ArrayList<>(data.entries());
        entries.add(new FoodEntry(
                UUID.randomUUID().toString(),
                date,
                dish.id(),
                dish.name(),
                grams,
                dish.per100g(),
                // Ohne Angabe unter Snacks: die Tagesliste zeigt vier feste
                // Abschnitte, und ein Eintrag ohne Zuordnung waere sonst nur im
                // Restfach fuer Altbestand sichtbar - das waere eine Aussage
                // ueber das Alter des Eintrags, nicht ueber die Mahlzeit.
                request.meal() == null ? Meal.SNACK : request.meal(),
                Instant.now(clock)));

        repository.save(user, new FoodData(data.targets(), data.mealShares(), dishes, entries));
        return day(user, date);
    }

    /**
     * Wertet eine Freitext-Beschreibung aus und macht daraus einen <b>Vorschlag</b>.
     *
     * <p>Schreibt bewusst nichts: eine geschaetzte Zahl, die ungefragt im Tagebuch
     * landet, sieht dort hinterher genauso aus wie eine abgelesene. Der Nutzer
     * bekommt den Vorschlag samt Herkunft je Wert zu sehen und bestaetigt ihn ueber
     * den normalen Eintrags-Endpunkt - der damit weiterhin der einzige Weg ist, auf
     * dem etwas ins Tagebuch kommt, mit denselben Grenzen wie bei Eingabe von Hand.
     *
     * <p>Nicht {@code synchronized}: der Aufruf beim Sprachmodell dauert Sekunden,
     * und solange muss niemand auf das Tagebuch warten. Zu warten gaebe es hier
     * ohnehin nichts - es wird ja nicht geschrieben.
     */
    public QuickCapturePreview quickCapture(String user, QuickCaptureRequest request) {
        return quickCapture(user, request, UUID.randomUUID().toString());
    }

    /**
     * Wertet aus - mit Foto, wenn eins mitkam: das liegt fuer die Dauer der
     * Auswertung als {@code <auftrag>.jpg} im Postfach und wird danach
     * geloescht, ob die Auswertung nun gelang oder nicht.
     */
    public QuickCapturePreview quickCapture(String user, QuickCaptureRequest request, String jobId) {
        String text = validateQuickCapture(request);
        byte[] image = decodeImage(request);

        FoodData data = repository.load(user);
        Path photo = null;
        ExtractedDish extracted;
        try {
            if (image != null) {
                photo = storePhoto(jobId, image);
            }
            extracted = extractor.extract(text, photo, data.targets(), data.dishes());
        } finally {
            if (photo != null) {
                try {
                    Files.deleteIfExists(photo);
                } catch (IOException e) {
                    log.warn("Foto {} liess sich nicht loeschen", photo, e);
                }
            }
        }

        // Der Abschnitt, aus dem die Eingabe kam, schlaegt die Vermutung des
        // Agents: wer auf "+" beim Mittagessen tippt, hat schon gesagt, was er
        // meint. Nur ohne Abschnitt zaehlt, was der Text hergibt.
        Meal meal = request.meal() != null ? request.meal() : extracted.meal();

        // Kennt die Liste das Gericht schon, gewinnt die gespeicherte Fassung.
        // Der Agent bekommt sie zwar als Kontext mit und soll sie uebernehmen,
        // aber "soll" ist keine Garantie: raet er beim Bananen-Naehrwert um ein
        // paar Kalorien daneben, wuerde ein Upsert ueber den Namen die von Hand
        // gepflegten Werte ueberschreiben.
        Optional<Dish> known = data.dishes().stream()
                .filter(d -> d.name().equalsIgnoreCase(extracted.name().trim()))
                .findFirst();

        Nutrients per100g = known.map(Dish::per100g).orElseGet(
                () -> new Nutrients(extracted.kcal(), extracted.proteinG(),
                        extracted.carbsG(), extracted.fatG()));

        return new QuickCapturePreview(
                known.isPresent(),
                known.map(Dish::id).orElse(null),
                known.map(Dish::name).orElseGet(() -> extracted.name().trim()),
                per100g.rounded(),
                known.map(Dish::portionG).orElseGet(extracted::portionG),
                extracted.grams(),
                meal == null ? Meal.SNACK : meal,
                valueSources(extracted, known.isPresent()),
                known.isPresent()
                        ? "Bekanntes Gericht erkannt - die gespeicherten Nährwerte werden übernommen."
                        : extracted.note());
    }

    /**
     * Das kcal-Ziel je Mahlzeit: Tagesziel mal Anteil. Weil die Anteile in Summe
     * 1 ergeben, summieren sich auch die Mahlzeitenziele genau auf den Tag - das
     * ist der Grund, ueberhaupt Anteile zu speichern und keine absoluten Werte.
     */
    private static Map<Meal, Double> mealTargets(FoodData data) {
        double kcal = data.targets().kcal();
        Map<Meal, Double> targets = new LinkedHashMap<>();
        for (Meal meal : Meal.values()) {
            double share = data.mealShares().getOrDefault(meal, 0.0);
            targets.put(meal, Math.round(kcal * share * 10) / 10.0);
        }
        return targets;
    }

    /**
     * Prueft eine eingereichte Aufteilung. Die Summe muss 100 % ergeben: sonst
     * stimmen die Mahlzeitenziele nicht mehr mit dem Tagesziel ueberein, und die
     * Anzeige behauptete etwas, das sich nicht ausgeht.
     */
    private static Map<Meal, Double> validShares(Map<Meal, Double> shares) {
        Map<Meal, Double> checked = new LinkedHashMap<>();
        double sum = 0;
        for (Meal meal : Meal.values()) {
            Double share = shares.get(meal);
            if (share == null || !Double.isFinite(share) || share < 0) {
                throw badRequest("mealShares braucht fuer jede Mahlzeit einen Anteil >= 0");
            }
            checked.put(meal, share);
            sum += share;
        }
        if (Math.abs(sum - 1.0) > 0.011) {
            throw badRequest(String.format(
                    "Die Anteile muessen zusammen 100 %% ergeben, sind aber %.1f %%", sum * 100));
        }
        return checked;
    }

    /**
     * Prueft die Eingabe und gibt den bereinigten Text zurueck.
     *
     * <p>Eigene Methode, weil der Auftragsstart sie vor dem Abschicken braucht:
     * ein zu langer oder leerer Text soll sofort als 400 ankommen und nicht erst
     * eine Minute spaeter als fehlgeschlagener Auftrag.
     */
    public String validateQuickCapture(QuickCaptureRequest request) {
        if (request == null) {
            throw badRequest("text is required");
        }
        String text = request.text() == null ? "" : request.text().trim();
        // Ohne Foto braucht es einen Text; mit Foto ist der Text Kontext und
        // darf fehlen.
        if (text.isEmpty() && !request.hasImage()) {
            throw badRequest("text is required");
        }
        if (text.length() > MAX_QUICK_CAPTURE_LENGTH) {
            throw badRequest("text must be at most " + MAX_QUICK_CAPTURE_LENGTH + " characters");
        }
        if (request.hasImage()) {
            decodeImage(request);
        }
        return text;
    }

    /**
     * Das Foto aus dem Base64 - ein Data-URL-Praefix ("data:image/jpeg;base64,")
     * darf davorstehen. Kaputtes Base64 und Riesenbilder sind ein 400, keine
     * eine Minute spaeter fehlgeschlagene Auswertung.
     */
    private static byte[] decodeImage(QuickCaptureRequest request) {
        if (!request.hasImage()) {
            return null;
        }
        String raw = request.imageJpegBase64().trim();
        int comma = raw.indexOf(',');
        if (raw.startsWith("data:") && comma > 0) {
            raw = raw.substring(comma + 1);
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(raw.replaceAll("\\s", ""));
        } catch (IllegalArgumentException e) {
            throw badRequest("imageJpegBase64 is not valid base64");
        }
        if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) {
            throw badRequest("image must be between 1 byte and " + MAX_IMAGE_BYTES / (1024 * 1024) + " MB");
        }
        return bytes;
    }

    /**
     * Legt das Foto ins Postfach, fuer alle lesbar: der Agent laeuft als anderer
     * Nutzer. Der Dateiname ist die Auftragsnummer, nichts aus der Anfrage.
     */
    private Path storePhoto(String jobId, byte[] image) {
        try {
            Files.createDirectories(inbox);
            trySetPermissions(inbox, "rwxr-xr-x");
            Path file = inbox.resolve(jobId + ".jpg");
            Files.write(file, image);
            trySetPermissions(file, "rw-r--r--");
            return file;
        } catch (IOException e) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Das Foto liess sich nicht ablegen.", e);
        }
    }

    /** POSIX-Rechte setzen, wo es sie gibt; anderswo (Tests auf macOS ohne Bedarf) egal. */
    private static void trySetPermissions(Path path, String mode) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode));
        } catch (UnsupportedOperationException | IOException e) {
            // Kein POSIX oder keine Rechte - dann bleibt es bei der umask.
        }
    }

    /**
     * Woher jeder einzelne Wert stammt. Genau das ist es, was der Nutzer vor dem
     * Bestaetigen sehen muss: eine geschaetzte Zahl von einer abgelesenen zu
     * unterscheiden, ist die eine Pruefung, die er selbst nicht nachholen kann.
     */
    private static Map<String, String> valueSources(ExtractedDish extracted, boolean known) {
        Map<String, String> sources = new LinkedHashMap<>();
        // Reihenfolge ist Absicht: nachgeschlagen schlaegt geschaetzt, und was in
        // keiner Liste steht, stand als Zahl im Text. Meldet der Agent ein Feld in
        // beiden Listen, gilt die schwaechere Aussage - lieber eine
        // nachgeschlagene Zahl faelschlich als Schaetzung markieren als umgekehrt.
        BiConsumer<String, String> put = (field, agentField) -> sources.put(field,
                extracted.estimatedFields().contains(agentField) ? "estimated"
                        : extracted.lookedUpFields().contains(agentField) ? "lookedUp"
                        : "read");

        if (known) {
            // Aus der Gerichteliste, also weder geraten noch aus dem Text.
            List.of("kcal", "proteinG", "carbsG", "fatG", "portionG")
                    .forEach(field -> sources.put(field, "stored"));
        } else {
            put.accept("kcal", "kcalPer100g");
            put.accept("proteinG", "proteinPer100g");
            put.accept("carbsG", "carbsPer100g");
            put.accept("fatG", "fatPer100g");
            put.accept("portionG", "portionG");
        }
        // Die Menge steht im Text oder eben nicht - unabhaengig davon, ob das
        // Gericht bekannt ist.
        put.accept("grams", "grams");
        return sources;
    }

    /**
     * Berichtigt Menge, Mahlzeit oder Tag eines Eintrags. Name und Naehrwerte
     * je 100 g bleiben, wie sie beim Eintragen waren.
     *
     * @return der Tag, auf dem der Eintrag danach liegt
     */
    public synchronized DaySummary updateEntry(String user, String id, UpdateEntryRequest request) {
        if (request == null) {
            throw badRequest("request body is required");
        }
        double grams = requirePositive(request.grams(), "grams", MAX_GRAMS);
        FoodData data = repository.load(user);
        FoodEntry existing = data.entries().stream()
                .filter(e -> e.id() != null && e.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown entry"));
        LocalDate date = request.date() == null ? existing.date() : request.date();
        Meal meal = request.meal() == null ? existing.meal() : request.meal();
        FoodEntry updated = new FoodEntry(existing.id(), date, existing.dishId(), existing.name(),
                grams, existing.per100g(), meal, existing.createdAt());
        List<FoodEntry> entries = data.entries().stream()
                .map(e -> e.id() != null && e.id().equals(id) ? updated : e)
                .toList();
        repository.save(user, new FoodData(data.targets(), data.mealShares(), data.dishes(), entries));
        return day(user, date);
    }

    /** Removes one entry. Returns the refreshed day it belonged to. */
    public synchronized DaySummary deleteEntry(String user, String id) {
        FoodData data = repository.load(user);
        FoodEntry existing = data.entries().stream()
                .filter(e -> e.id() != null && e.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown entry"));
        List<FoodEntry> entries = new ArrayList<>(data.entries());
        entries.removeIf(e -> e.id() != null && e.id().equals(id));
        repository.save(user, new FoodData(data.targets(), data.mealShares(), data.dishes(), entries));
        return day(user, existing.date());
    }

    /** Adds a dish to the library without logging it. */
    public synchronized Dish createDish(String user, DishRequest request) {
        FoodData data = repository.load(user);
        List<Dish> dishes = new ArrayList<>(data.dishes());
        Dish dish = upsertDish(dishes, request, null);
        repository.save(user, new FoodData(data.targets(), data.mealShares(), dishes, data.entries()));
        return dish;
    }

    /**
     * Corrects a dish's name, nutrition or portion size. Existing entries keep the
     * values they were logged with (see {@link FoodEntry}), so this changes what future
     * entries will use, not the past.
     */
    public synchronized Dish updateDish(String user, String id, DishRequest request) {
        FoodData data = repository.load(user);
        List<Dish> dishes = new ArrayList<>(data.dishes());
        if (dishes.stream().noneMatch(d -> d.id().equals(id))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown dish");
        }
        Dish updated = upsertDish(dishes, request, id);
        repository.save(user, new FoodData(data.targets(), data.mealShares(), dishes, data.entries()));
        return updated;
    }

    /** Forgets a dish. Entries that used it stay untouched, name and values included. */
    public synchronized void deleteDish(String user, String id) {
        FoodData data = repository.load(user);
        List<Dish> dishes = new ArrayList<>(data.dishes());
        if (!dishes.removeIf(d -> d.id().equals(id))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown dish");
        }
        repository.save(user, new FoodData(data.targets(), data.mealShares(), dishes, data.entries()));
    }

    /** Replaces the daily goals. */
    public synchronized Nutrients updateTargets(String user, TargetsRequest request) {
        if (request == null) {
            throw badRequest("request body is required");
        }
        Nutrients targets = new Nutrients(
                requirePositive(request.kcal(), "kcal", 20_000),
                requireNonNegative(request.proteinG(), "proteinG", 2_000),
                requireNonNegative(request.carbsG(), "carbsG", 2_000),
                requireNonNegative(request.fatG(), "fatG", 2_000));
        FoodData data = repository.load(user);
        Map<Meal, Double> shares = request.mealShares() == null
                ? data.mealShares()
                : validShares(request.mealShares());
        repository.save(user, new FoodData(targets, shares, data.dishes(), data.entries()));
        return targets.rounded();
    }

    // --- helpers ------------------------------------------------------------

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private static List<FoodEntry> entriesOn(FoodData data, LocalDate date) {
        return data.entries().stream()
                .filter(e -> date.equals(e.date()))
                .sorted(Comparator.comparing(
                        FoodEntry::createdAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
    }

    private static Nutrients sum(List<FoodEntry> entries) {
        return entries.stream().map(FoodEntry::total).reduce(Nutrients.ZERO, Nutrients::plus);
    }

    /**
     * Writes a dish into {@code dishes}, either under the given id (edit) or under a
     * name that already exists (re-entering a known dish updates it instead of creating
     * a near-duplicate), or as a new one. Returns the stored dish.
     */
    private Dish upsertDish(List<Dish> dishes, DishRequest request, String id) {
        if (request == null) {
            throw badRequest("dish is required");
        }
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty()) {
            throw badRequest("dish name is required");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw badRequest("dish name must be at most " + MAX_NAME_LENGTH + " characters");
        }
        Nutrients per100g = new Nutrients(
                requireNonNegative(request.kcal(), "kcal", MAX_KCAL_PER_100G),
                requireNonNegative(request.proteinG(), "proteinG", MAX_MACRO_PER_100G),
                requireNonNegative(request.carbsG(), "carbsG", MAX_MACRO_PER_100G),
                requireNonNegative(request.fatG(), "fatG", MAX_MACRO_PER_100G));
        Double portionG = request.portionG() == null
                ? null
                : requirePositive(request.portionG(), "portionG", MAX_GRAMS);

        Optional<Dish> byName = dishes.stream()
                .filter(d -> d.name().equalsIgnoreCase(name))
                .findFirst();
        if (id != null) {
            // Renaming onto another dish's name would leave two indistinguishable rows
            // in the picker, so it is rejected rather than silently merged.
            if (byName.isPresent() && !byName.get().id().equals(id)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "another dish already uses that name");
            }
        }
        String targetId = id != null
                ? id
                : byName.map(Dish::id).orElseGet(() -> UUID.randomUUID().toString());
        // Erst findFirst, dann map - nicht umgekehrt: findFirst() auf einem
        // Stream, dessen erstes Element null ist, wirft eine NPE. Genau das
        // passierte bei einem Gericht, das ueber die Verwaltung angelegt, aber
        // noch nie eingetragen wurde: dessen lastUsedOn ist null, und jeder
        // Versuch, es zu buchen, endete in einem 500er.
        LocalDate lastUsed = dishes.stream()
                .filter(d -> d.id().equals(targetId))
                .findFirst()
                .map(Dish::lastUsedOn)
                .orElse(null);

        Dish dish = new Dish(targetId, name, per100g, portionG, lastUsed);
        replaceDish(dishes, targetId, dish);
        return dish;
    }

    /** Replaces the dish with this id in place, or appends it if it is not there yet. */
    private static void replaceDish(List<Dish> dishes, String id, Dish dish) {
        for (int i = 0; i < dishes.size(); i++) {
            if (dishes.get(i).id().equals(id)) {
                dishes.set(i, dish);
                return;
            }
        }
        dishes.add(dish);
    }

    private static double requirePositive(Double value, String field, double max) {
        double result = requireNonNegative(value, field, max);
        if (result <= 0) {
            throw badRequest(field + " must be greater than 0");
        }
        return result;
    }

    private static double requireNonNegative(Double value, String field, double max) {
        if (value == null) {
            throw badRequest(field + " is required");
        }
        if (!Double.isFinite(value) || value < 0) {
            throw badRequest(field + " must be a number of at least 0");
        }
        if (value > max) {
            throw badRequest(field + " must be at most " + max);
        }
        return value;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}

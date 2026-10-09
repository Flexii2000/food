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
import com.fherrmann.food.model.Micronutrient;
import com.fherrmann.food.model.Nutrients;
import com.fherrmann.food.repository.FoodRepository;
import com.fherrmann.food.security.HealthUsers;
import com.fherrmann.food.security.UserFiles;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.nio.file.Files;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FoodServiceTest {

    /** Die Eigentuemerin - ihre Datei liegt, wo sie immer lag. */
    private static final String ME = "felix";
    private static final UserFiles FILES = new UserFiles(
            new HealthUsers(ME, "torben:0123456789abcdef0123456789abcdef"));

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 31);

    @TempDir
    Path tempDir;

    private FoodService service;
    /** Dasselbe Tagebuch, aber Torben erfasst Mikronaehrstoffe. */
    private FoodService micro;
    private FakeExtractor extractor;

    /**
     * Steht anstelle des Claude-Aufrufs. Die Tests pruefen, was die App aus einer
     * Antwort macht - nicht, ob das Modell gut raet; ein Testlauf, der echte
     * API-Aufrufe braucht, waere weder schnell noch verlaesslich noch umsonst.
     */
    private static final class FakeExtractor implements NutritionExtractor {
        private ExtractedDish next = new ExtractedDish(
                "Spaghetti Bolognese", 130, 7, 16, 4, 450, 400.0, List.of(),
                List.of("kcalPer100g", "proteinPer100g", "carbsPer100g", "fatPer100g", "grams"),
                "Portion und Naehrwerte fuer einen grossen Teller geschaetzt.", Meal.LUNCH);
        private boolean available = true;
        private String seenText;
        private List<Dish> seenKnown;
        private boolean seenDetailed;
        private boolean seenMicronutrients;
        private Path seenPhoto;
        private byte[] seenPhotoBytes;

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public ExtractedDish extract(String text, Path photo, Nutrients targets, List<Dish> known,
                                     boolean detailed, boolean micronutrients) {
            seenText = text;
            seenKnown = known;
            seenDetailed = detailed;
            seenMicronutrients = micronutrients;
            seenPhoto = photo;
            // Waehrend der Auswertung muss die Datei da sein - danach nicht mehr.
            try {
                seenPhotoBytes = photo == null ? null : Files.readAllBytes(photo);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return next;
        }
    }

    @BeforeEach
    void setUp() {
        FoodRepository repository = new FoodRepository(
                tempDir.resolve("food.json").toString(), new ObjectMapper(), FILES);
        Clock clock = Clock.fixed(TODAY.atStartOfDay(ZoneId.of("UTC")).toInstant(), ZoneId.of("UTC"));
        extractor = new FakeExtractor();
        service = new FoodService(repository, extractor, clock);
        micro = new FoodService(repository, extractor, clock, DetailedNutrition.none(),
                new MicronutrientTracking("torben"));
    }

    private static DishRequest skyr() {
        // 80 kcal, 8 g Eiweiß, 6 g KH, 1.3 g Fett je 100 g; Portion 300 g.
        return new DishRequest("Skyr mit Beeren", 80.0, 8.0, 6.0, 1.3, 300.0);
    }

    @Test
    void defaultTargetsAddUpToTheConfiguredCalories() {
        // 200 g Eiweiß und 62 g Fett, Kohlenhydrate fuellen den Rest exakt auf.
        var targets = service.targets(ME);
        assertThat(targets.proteinG() * 4 + targets.carbsG() * 4 + targets.fatG() * 9)
                .isEqualTo(targets.kcal());
    }

    @Test
    void loggingAnUnknownDishRemembersItAndCountsTheAmount() {
        DaySummary day = service.addEntry(ME, new NewEntryRequest(TODAY, null, skyr(), 300.0, null));

        assertThat(day.date()).isEqualTo(TODAY);
        assertThat(day.entries()).hasSize(1);
        // 300 g sind dreimal die 100-g-Werte.
        assertThat(day.consumed().kcal()).isEqualTo(240.0);
        assertThat(day.consumed().proteinG()).isEqualTo(24.0);
        assertThat(day.consumed().fatG()).isEqualTo(3.9);
        assertThat(day.remaining().kcal()).isEqualTo(2060.0);

        assertThat(service.dishes(ME)).singleElement()
                .extracting(Dish::name, Dish::portionG, Dish::lastUsedOn)
                .containsExactly("Skyr mit Beeren", 300.0, TODAY);
    }

    @Test
    void aRememberedDishCanBeLoggedAgainById() {
        service.addEntry(ME, new NewEntryRequest(TODAY, null, skyr(), 300.0, null));
        String id = service.dishes(ME).get(0).id();

        DaySummary day = service.addEntry(ME, new NewEntryRequest(TODAY, id, null, 150.0, null));

        assertThat(day.entries()).hasSize(2);
        assertThat(day.consumed().kcal()).isEqualTo(360.0);
        assertThat(service.dishes(ME)).hasSize(1);
    }

    @Test
    void editingADishLeavesAlreadyLoggedEntriesUntouched() {
        service.addEntry(ME, new NewEntryRequest(TODAY, null, skyr(), 300.0, null));
        String id = service.dishes(ME).get(0).id();

        service.updateDish(ME, id, new DishRequest("Skyr mit Beeren", 200.0, 8.0, 6.0, 1.3, 300.0));

        // Der Eintrag von heute rechnet weiter mit den 80 kcal/100 g von damals.
        assertThat(service.day(ME, TODAY).consumed().kcal()).isEqualTo(240.0);
        assertThat(service.dishes(ME).get(0).per100g().kcal()).isEqualTo(200.0);
    }

    @Test
    void deletingADishKeepsTheHistoryThatUsedIt() {
        service.addEntry(ME, new NewEntryRequest(TODAY, null, skyr(), 300.0, null));
        service.deleteDish(ME, service.dishes(ME).get(0).id());

        assertThat(service.dishes(ME)).isEmpty();
        assertThat(service.day(ME, TODAY).entries()).singleElement()
                .extracting(e -> e.name()).isEqualTo("Skyr mit Beeren");
        assertThat(service.day(ME, TODAY).consumed().kcal()).isEqualTo(240.0);
    }

    @Test
    void enteringAKnownNameAgainUpdatesTheDishInsteadOfDuplicatingIt() {
        service.addEntry(ME, new NewEntryRequest(TODAY, null, skyr(), 300.0, null));
        service.addEntry(ME, new NewEntryRequest(TODAY, null,
                new DishRequest("skyr MIT beeren", 90.0, 9.0, 6.0, 1.0, 250.0), 100.0, null));

        assertThat(service.dishes(ME)).hasSize(1);
        assertThat(service.dishes(ME).get(0).per100g().kcal()).isEqualTo(90.0);
        assertThat(service.dishes(ME).get(0).portionG()).isEqualTo(250.0);
    }

    @Test
    void aDishCreatedButNeverLoggedCanBeLogged() {
        // Ueber die Verwaltung angelegte Gerichte haben kein lastUsedOn. Sie
        // danach unter demselben Namen einzutragen lief in eine NPE, weil
        // findFirst() ueber einem null-Element ausloest.
        service.createDish(ME, skyr());

        DaySummary day = service.addEntry(ME, new NewEntryRequest(TODAY, null, skyr(), 300.0, Meal.BREAKFAST));

        assertThat(day.consumed().kcal()).isEqualTo(240.0);
        assertThat(service.dishes(ME)).singleElement()
                .extracting(Dish::lastUsedOn).isEqualTo(TODAY);
    }

    @Test
    void renamingOntoAnExistingNameIsRejected() {
        service.createDish(ME, skyr());
        service.createDish(ME, new DishRequest("Haferflocken", 370.0, 13.0, 59.0, 7.0, null));
        String id = service.dishes(ME).stream()
                .filter(d -> d.name().equals("Haferflocken")).findFirst().orElseThrow().id();

        assertThatThrownBy(() -> service.updateDish(ME, id, skyr()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
    }

    @Test
    void backfillingAnOlderDayDoesNotMoveTheDishToTheTopOfThePicker() {
        service.addEntry(ME, new NewEntryRequest(TODAY, null, skyr(), 300.0, null));
        String id = service.dishes(ME).get(0).id();
        service.addEntry(ME, new NewEntryRequest(TODAY.minusDays(5), id, null, 300.0, null));

        assertThat(service.dishes(ME).get(0).lastUsedOn()).isEqualTo(TODAY);
    }

    @Test
    void anEntryCanBeCorrectedWithoutChangingTheDish() {
        DaySummary day = service.addEntry(ME, new NewEntryRequest(TODAY, null, skyr(), 300.0, Meal.LUNCH));
        String entryId = day.entries().get(0).id();
        double kcalPer100 = day.entries().get(0).per100g().kcal();

        DaySummary after = service.updateEntry(ME, entryId,
                new UpdateEntryRequest(150.0, Meal.DINNER, TODAY.minusDays(1)));

        // Der Tag von gestern kommt zurueck - dorthin ist der Eintrag gewandert.
        assertThat(after.date()).isEqualTo(TODAY.minusDays(1));
        assertThat(after.entries()).hasSize(1);
        assertThat(after.entries().get(0).grams()).isEqualTo(150.0);
        assertThat(after.entries().get(0).meal()).isEqualTo(Meal.DINNER);
        assertThat(after.entries().get(0).per100g().kcal()).isEqualTo(kcalPer100);
        assertThat(service.day(ME, TODAY).entries()).isEmpty();
        // Ohne Mahlzeit und Tag bleiben beide, wie sie waren.
        DaySummary again = service.updateEntry(ME, entryId, new UpdateEntryRequest(200.0, null, null));
        assertThat(again.date()).isEqualTo(TODAY.minusDays(1));
        assertThat(again.entries().get(0).meal()).isEqualTo(Meal.DINNER);
    }

    @Test
    void deletingAnEntryRemovesItFromItsDay() {
        DaySummary day = service.addEntry(ME, new NewEntryRequest(TODAY, null, skyr(), 300.0, null));
        String entryId = day.entries().get(0).id();

        DaySummary after = service.deleteEntry(ME, entryId);

        assertThat(after.entries()).isEmpty();
        assertThat(after.consumed().kcal()).isZero();
    }

    @Test
    void dailyTotalsCoverOnlyDaysWithEntriesInsideTheRange() {
        service.addEntry(ME, new NewEntryRequest(TODAY.minusDays(2), null, skyr(), 300.0, null));
        service.addEntry(ME, new NewEntryRequest(TODAY, null,
                new DishRequest("Haferflocken", 370.0, 13.0, 59.0, 7.0, null), 100.0, null));

        List<DayTotal> totals = service.dailyTotals(ME, TODAY.minusDays(3), TODAY);

        // Der Tag dazwischen taucht nicht als 0 kcal auf - nichts eingetragen heisst
        // "unbekannt", nicht "nichts gegessen".
        assertThat(totals).hasSize(2);
        assertThat(totals.get(0).date()).isEqualTo(TODAY.minusDays(2));
        assertThat(totals.get(0).consumed().kcal()).isEqualTo(240.0);
        assertThat(totals.get(1).consumed().kcal()).isEqualTo(370.0);
    }

    @Test
    void dailyTotalsNameTheMealsThatHaveEntries() {
        service.addEntry(ME, new NewEntryRequest(TODAY.minusDays(1), null, skyr(), 300.0, Meal.DINNER));
        service.addEntry(ME, new NewEntryRequest(TODAY.minusDays(1), null, skyr(), 100.0, Meal.BREAKFAST));
        service.addEntry(ME, new NewEntryRequest(TODAY.minusDays(1), null, skyr(), 100.0, Meal.BREAKFAST));
        // Altbestand aus der Zeit vor der Aufteilung, direkt in die Datei: zaehlt in
        // die kcal, macht aber keine Mahlzeit voll (neue Eintraege ohne Angabe
        // landen dagegen unter Snacks).
        FoodRepository repository = new FoodRepository(
                tempDir.resolve("food.json").toString(), new ObjectMapper(), FILES);
        FoodData data = repository.load(ME);
        List<FoodEntry> entries = new ArrayList<>(data.entries());
        entries.add(new FoodEntry("alt-1", TODAY.minusDays(1), null, "Altbestand", 100.0,
                new Nutrients(80, 8, 6, 1.3), null, Instant.EPOCH));
        entries.add(new FoodEntry("alt-2", TODAY, null, "Altbestand", 100.0,
                new Nutrients(80, 8, 6, 1.3), null, Instant.EPOCH));
        repository.save(ME, data.with(data.dishes(), entries));

        List<DayTotal> totals = service.dailyTotals(ME, TODAY.minusDays(1), TODAY);

        // Reihenfolge des Enums, jede Mahlzeit einmal - der Weight Tracker prueft
        // daran "Fruehstueck, Mittag und Abend" wie coHabit bei "Track food".
        assertThat(totals.get(0).meals()).containsExactly(Meal.BREAKFAST, Meal.DINNER);
        assertThat(totals.get(0).consumed().kcal()).isEqualTo(480.0);
        assertThat(totals.get(1).meals()).isEmpty();
        assertThat(totals.get(1).consumed().kcal()).isEqualTo(80.0);
    }

    /** Ein Eintrag mit genau {@code kcal} an einem Tag: 1000 g von einem Gericht mit {@code kcal/10} je 100 g. */
    private void kcalOn(LocalDate date, double kcal) {
        service.addEntry(ME, new NewEntryRequest(date, null,
                new DishRequest("Testgericht " + kcal, kcal / 10.0, 0.0, 0.0, 0.0, null), 1000.0, null));
    }

    @Test
    void dailyAveragesMeanOnlyTheFinishedDaysThatHaveEntries() {
        kcalOn(TODAY.minusDays(6), 2000);
        kcalOn(TODAY.minusDays(4), 2400);
        kcalOn(TODAY, 1600);   // laeuft noch - zaehlt nicht

        List<DayAverage> averages = service.dailyAverages(ME, TODAY.minusDays(8), TODAY);

        // Jeder Tag, dessen Fenster (3 davor, 3 danach) einen abgeschlossenen
        // Eintrag enthaelt, bekommt ein Mittel - nur ueber diese Tage: die
        // Luecken dazwischen sind unbekannt, nicht null, und der laufende Tag
        // ist noch nicht vorbei.
        Map<LocalDate, DayAverage> byDate = averages.stream()
                .collect(java.util.stream.Collectors.toMap(DayAverage::date, a -> a));
        assertThat(byDate.get(TODAY.minusDays(4)).kcal()).isEqualTo(2200.0);   // (2000 + 2400) / 2
        assertThat(byDate.get(TODAY.minusDays(4)).days()).isEqualTo(2);
        assertThat(byDate.get(TODAY.minusDays(4)).complete()).isTrue();          // Fenster endet gestern
        assertThat(byDate.get(TODAY.minusDays(3)).kcal()).isEqualTo(2200.0);   // heute zaehlt nicht mit
        assertThat(byDate.get(TODAY.minusDays(3)).complete()).isFalse();         // Fenster reicht bis heute
        assertThat(byDate.get(TODAY.minusDays(2)).kcal()).isEqualTo(2400.0);   // nur noch der Tag -4
        assertThat(byDate.get(TODAY.minusDays(2)).days()).isEqualTo(1);
        assertThat(byDate.get(TODAY.minusDays(8)).kcal()).isEqualTo(2000.0);   // nur der Tag -6 im Fenster
        assertThat(byDate.get(TODAY.minusDays(8)).days()).isEqualTo(1);
        // Heute selbst: im Fenster liegt kein abgeschlossener Eintrag mehr.
        assertThat(byDate).doesNotContainKey(TODAY);
        assertThat(averages).hasSize(8);
    }

    @Test
    void dailyAveragesIgnoreTodayAndPreloggedFutureDays() {
        kcalOn(TODAY.minusDays(1), 2000);
        kcalOn(TODAY, 5000);                // halber Tag, waechst noch
        kcalOn(TODAY.plusDays(1), 3000);    // vorerfasst - ein Plan, kein Tag

        List<DayAverage> averages = service.dailyAverages(ME, TODAY.minusDays(10), TODAY.plusDays(5));

        // Gestern ist der einzige abgeschlossene Tag: jedes Fenster, das ihn
        // enthaelt, mittelt genau ihn - und nach heute wird nichts prognostiziert.
        assertThat(averages).extracting(DayAverage::date).containsExactly(
                TODAY.minusDays(4), TODAY.minusDays(3), TODAY.minusDays(2), TODAY.minusDays(1), TODAY);
        assertThat(averages).extracting(DayAverage::kcal).containsOnly(2000.0);
        assertThat(averages).extracting(DayAverage::days).containsOnly(1);
        assertThat(service.dailyAverages(ME, TODAY.minusDays(60), TODAY.minusDays(30))).isEmpty();
    }

    @Test
    void dailyAveragesRejectAnInvertedRange() {
        assertThatThrownBy(() -> service.dailyAverages(ME, TODAY, TODAY.minusDays(1)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void dailyTotalsRejectAnInvertedRange() {
        assertThatThrownBy(() -> service.dailyTotals(ME, TODAY, TODAY.minusDays(1)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void mealTargetsAddUpToTheDailyTarget() {
        DaySummary day = service.day(ME, TODAY);
        // 25/35/30/10 von 2300 kcal.
        assertThat(day.mealTargets())
                .containsEntry(Meal.BREAKFAST, 575.0)
                .containsEntry(Meal.LUNCH, 805.0)
                .containsEntry(Meal.DINNER, 690.0)
                .containsEntry(Meal.SNACK, 230.0);
        assertThat(day.mealTargets().values().stream().mapToDouble(Double::doubleValue).sum())
                .isEqualTo(day.targets().kcal());
    }

    @Test
    void mealTargetsFollowAChangedDailyTarget() {
        // Der ganze Grund, Anteile statt absoluter Werte zu speichern.
        service.updateTargets(ME, new TargetsRequest(2000.0, 150.0, 200.0, 65.0, null));
        assertThat(service.day(ME, TODAY).mealTargets()).containsEntry(Meal.BREAKFAST, 500.0);
    }

    @Test
    void aSplitThatDoesNotAddUpIsRejected() {
        assertThatThrownBy(() -> service.updateTargets(ME, new TargetsRequest(
                2300.0, 200.0, 235.5, 62.0,
                Map.of(Meal.BREAKFAST, 0.5, Meal.LUNCH, 0.5, Meal.DINNER, 0.5, Meal.SNACK, 0.5))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("100");
    }

    @Test
    void aChangedSplitIsStored() {
        service.updateTargets(ME, new TargetsRequest(2300.0, 200.0, 235.5, 62.0,
                Map.of(Meal.BREAKFAST, 0.2, Meal.LUNCH, 0.4, Meal.DINNER, 0.35, Meal.SNACK, 0.05)));
        assertThat(service.day(ME, TODAY).mealTargets())
                .containsEntry(Meal.BREAKFAST, 460.0)
                .containsEntry(Meal.LUNCH, 920.0);
    }

    @Test
    void targetsCanBeChanged() {
        var targets = service.updateTargets(ME, new TargetsRequest(2000.0, 150.0, 200.0, 65.0, null));
        assertThat(targets.kcal()).isEqualTo(2000.0);
        assertThat(service.day(ME, TODAY).targets().proteinG()).isEqualTo(150.0);
    }

    @Test
    void statusReportsTodaysFigures() {
        service.addEntry(ME, new NewEntryRequest(TODAY, null, skyr(), 300.0, null));

        StatusInfo status = service.status(ME);

        assertThat(status.lastResult()).isEqualTo("ok");
        assertThat(status.today()).isEqualTo(TODAY);
        assertThat(status.kcalConsumed()).isEqualTo(240.0);
        assertThat(status.kcalTarget()).isEqualTo(2300.0);
        assertThat(status.entriesToday()).isEqualTo(1);
        assertThat(status.dishCount()).isEqualTo(1);
        assertThat(status.lastEntryOn()).isEqualTo(TODAY);
    }

    @Test
    void quickCaptureOnlyProposesAndWritesNothing() {
        QuickCapturePreview preview = service.quickCapture(ME, 
                new QuickCaptureRequest(TODAY, "  mittags einen grossen Teller Spaghetti Bolognese  ", null));

        // Der Text geht getrimmt rein, so wie er getippt wurde.
        assertThat(extractor.seenText).isEqualTo("mittags einen grossen Teller Spaghetti Bolognese");

        assertThat(preview.name()).isEqualTo("Spaghetti Bolognese");
        assertThat(preview.grams()).isEqualTo(450.0);
        assertThat(preview.known()).isFalse();
        assertThat(preview.portionG()).isEqualTo(400.0);
        assertThat(preview.meal()).isEqualTo(Meal.LUNCH);

        // Und zwar wirklich nichts geschrieben - weder Eintrag noch Gericht.
        assertThat(service.day(ME, TODAY).entries()).isEmpty();
        assertThat(service.dishes(ME)).isEmpty();
    }

    @Test
    void thePreviewSaysWhereEveryValueCameFrom() {
        QuickCapturePreview preview = service.quickCapture(ME, 
                new QuickCaptureRequest(TODAY, "ein Teller Bolognese", null));

        // Der Fake-Extractor meldet alles ausser der Portionsgroesse als geschaetzt.
        assertThat(preview.valueSources())
                .containsEntry("kcal", "estimated")
                .containsEntry("grams", "estimated")
                .containsEntry("portionG", "read");
    }

    @Test
    void anEntryWithoutAMealLandsUnderSnacks() {
        // Sonst taeuchte es in keinem der vier Abschnitte auf.
        DaySummary day = service.addEntry(ME, new NewEntryRequest(TODAY, null, skyr(), 300.0, null));
        assertThat(day.entries()).singleElement()
                .extracting(e -> e.meal()).isEqualTo(Meal.SNACK);
    }

    @Test
    void theChosenSectionBeatsTheAgentsGuess() {
        // Der Fake-Extractor tippt auf LUNCH; wer aus dem Fruehstuecks-Abschnitt
        // kommt, hat aber schon gesagt, was er meint.
        assertThat(service.quickCapture(ME, 
                new QuickCaptureRequest(TODAY, "ein Teller Bolognese", Meal.BREAKFAST)).meal())
                .isEqualTo(Meal.BREAKFAST);
    }

    @Test
    void withoutASectionTheAgentsGuessIsUsed() {
        assertThat(service.quickCapture(ME, 
                new QuickCaptureRequest(TODAY, "mittags ein Teller Bolognese", null)).meal())
                .isEqualTo(Meal.LUNCH);
    }

    @Test
    void aKnownDishKeepsItsStoredValues() {
        // "Banane" liegt mit gepflegten Werten in der Liste; der Agent liefert
        // denselben Namen, aber leicht andere Zahlen.
        service.createDish(ME, new DishRequest("Banane", 89.0, 1.1, 23.0, 0.3, 120.0));
        extractor.next = new ExtractedDish(
                "banane", 105.0, 2.0, 27.0, 0.5, 120, 150.0, List.of(), List.of("kcalPer100g"), "geraten", Meal.SNACK);

        QuickCapturePreview preview = service.quickCapture(ME, 
                new QuickCaptureRequest(TODAY, "eine Banane", null));

        // Vorgeschlagen werden die gespeicherten 89 kcal je 100 g, nicht die
        // geratenen 105 - und die Herkunft sagt das auch.
        assertThat(preview.known()).isTrue();
        assertThat(preview.name()).isEqualTo("Banane");
        assertThat(preview.dishId()).isNotNull();
        assertThat(preview.per100g().kcal()).isEqualTo(89.0);
        assertThat(preview.portionG()).isEqualTo(120.0);
        assertThat(preview.valueSources()).containsEntry("kcal", "stored");
    }

    @Test
    void nachgeschlageneWerteGeltenNichtAlsGeschaetzt() {
        // Der Agent darf Naehrwerte im Netz nachschlagen. Das ist belastbarer als
        // eine Schaetzung und muss im Vorschlag auch so dastehen.
        extractor.next = new ExtractedDish(
                "Wagner Piccolinis", 229, 10.9, 28.4, 7.5, 180, 270.0,
                List.of("kcalPer100g", "proteinPer100g", "carbsPer100g", "fatPer100g", "portionG"),
                List.of("grams"),
                "Naehrwerte laut original-wagner.de.", Meal.SNACK);

        QuickCapturePreview preview = service.quickCapture(ME, 
                new QuickCaptureRequest(TODAY, "6 Wagner Piccolinis", null));

        assertThat(preview.valueSources())
                .containsEntry("kcal", "lookedUp")
                .containsEntry("portionG", "lookedUp")
                .containsEntry("grams", "estimated");
    }

    @Test
    void quickCaptureGetsTheAlreadyKnownDishesAsContext() {
        service.createDish(ME, skyr());
        service.quickCapture(ME, new QuickCaptureRequest(TODAY, "ein Becher Skyr", null));

        assertThat(extractor.seenKnown).extracting(Dish::name).containsExactly("Skyr mit Beeren");
    }

    @Test
    void aNonsensicalProposalIsStoppedWhenItIsConfirmed() {
        // Der Vorschlag selbst schreibt nichts und darf deshalb auch Unfug
        // anzeigen - spaetestens beim Bestaetigen greift dieselbe Pruefung wie
        // bei einer Eingabe von Hand. Ein Modell, das sich um eine Zehnerpotenz
        // vertut, kommt da nicht vorbei.
        extractor.next = new ExtractedDish("Unfug", 99_000, 7, 16, 4, 450, null, List.of(), List.of(), "", null);
        QuickCapturePreview preview = service.quickCapture(ME, 
                new QuickCaptureRequest(TODAY, "irgendwas", null));

        assertThatThrownBy(() -> service.addEntry(ME, new NewEntryRequest(
                TODAY, null,
                new DishRequest(preview.name(), preview.per100g().kcal(),
                        preview.per100g().proteinG(), preview.per100g().carbsG(),
                        preview.per100g().fatG(), preview.portionG()),
                preview.grams(), preview.meal())))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("kcal");
    }

    @Test
    void quickCaptureWithPhotoNeedsNoTextAndCleansUp() {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F'};
        String base64 = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg);
        QuickCapturePreview preview = service.quickCapture(ME, 
                new QuickCaptureRequest(TODAY, "", null, base64), "job-1");
        assertThat(preview.name()).isEqualTo("Spaghetti Bolognese");
        assertThat(extractor.seenText).isEmpty();
        assertThat(extractor.seenPhoto).isNotNull();
        assertThat(extractor.seenPhoto.getFileName().toString()).isEqualTo("job-1.jpg");
        assertThat(extractor.seenPhotoBytes).isEqualTo(jpeg);
        // Nach der Auswertung ist das Foto weg - es diente nur dem Agent.
        assertThat(Files.exists(extractor.seenPhoto)).isFalse();
    }

    @Test
    void quickCaptureRejectsBrokenOrOversizedPhotos() {
        assertThatThrownBy(() -> service.validateQuickCapture(
                new QuickCaptureRequest(TODAY, "", null, "kein base64 !!!")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("base64");
        String huge = Base64.getEncoder().encodeToString(new byte[FoodService.MAX_IMAGE_BYTES + 1]);
        assertThatThrownBy(() -> service.validateQuickCapture(
                new QuickCaptureRequest(TODAY, "", null, huge)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("MB");
        // Ohne Foto bleibt der Text Pflicht.
        assertThatThrownBy(() -> service.validateQuickCapture(
                new QuickCaptureRequest(TODAY, "   ", null, null)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void quickCaptureRejectsEmptyAndOverlongText() {
        assertThatThrownBy(() -> service.quickCapture(ME, new QuickCaptureRequest(TODAY, "   ", null)))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.quickCapture(ME, 
                new QuickCaptureRequest(TODAY, "x".repeat(1001), null)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void quickCaptureAvailabilityFollowsTheExtractor() {
        assertThat(service.quickCaptureAvailable()).isTrue();
        extractor.available = false;
        assertThat(service.quickCaptureAvailable()).isFalse();
    }

    @Test
    void invalidInputIsRejected() {
        assertThatThrownBy(() -> service.addEntry(ME, new NewEntryRequest(TODAY, null, skyr(), 0.0, null)))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.addEntry(ME, new NewEntryRequest(TODAY, null, skyr(), 25_000.0, null)))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.addEntry(ME, new NewEntryRequest(TODAY, null, null, 100.0, null)))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.addEntry(ME, new NewEntryRequest(TODAY, "nope", null, 100.0, null)))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.createDish(ME, new DishRequest("  ", 80.0, 8.0, 6.0, 1.3, null)))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.createDish(ME, new DishRequest("X", -1.0, 8.0, 6.0, 1.3, null)))
                .isInstanceOf(ResponseStatusException.class);
    }

    // --- zwei Personen --------------------------------------------------------

    /** Jede Person hat ihr eigenes Tagebuch - nichts sickert hinueber. */
    @Test
    void twoPeopleNeverSeeEachOthersDiary() {
        service.addEntry(ME, new NewEntryRequest(TODAY, null, skyr(), 300.0, Meal.BREAKFAST));
        service.updateTargets("torben", new TargetsRequest(2800.0, 180.0, 300.0, 90.0, null));
        service.addEntry("torben", new NewEntryRequest(TODAY, null,
                new DishRequest("Döner", 215.0, 12.0, 20.0, 9.0, 400.0), 400.0, Meal.LUNCH));

        assertThat(service.day(ME, TODAY).entries()).singleElement()
                .satisfies(e -> assertThat(e.name()).isEqualTo("Skyr mit Beeren"));
        assertThat(service.day("torben", TODAY).entries()).singleElement()
                .satisfies(e -> assertThat(e.name()).isEqualTo("Döner"));
        assertThat(service.dishes(ME)).extracting(Dish::name).containsExactly("Skyr mit Beeren");
        assertThat(service.dishes("torben")).extracting(Dish::name).containsExactly("Döner");
        assertThat(service.targets(ME).kcal()).isEqualTo(2300.0);
        assertThat(service.targets("torben").kcal()).isEqualTo(2800.0);
        assertThat(service.dailyTotals("torben", TODAY, TODAY)).singleElement()
                .satisfies(t -> assertThat(t.consumed().kcal()).isEqualTo(860.0));
    }

    /**
     * Die Schnellerfassung arbeitet mit den Gerichten und Zielen der Person, die
     * fragt - sonst erkennte der Agent bei Torben Felix' Merkliste wieder.
     */
    @Test
    void quickCaptureUsesTheAskingPersonsDishes() {
        service.createDish(ME, new DishRequest("Banane", 89.0, 1.1, 23.0, 0.3, 120.0));
        service.quickCapture("torben", new QuickCaptureRequest(TODAY, "eine Banane", null));
        assertThat(extractor.seenKnown).isEmpty();
    }

    // --- Detailwerte (gesaettigte Fettsaeuren, Zucker, Ballaststoffe, Salz) -----

    /** Skyr laut Packung, mit der ganzen Naehrwerttabelle. */
    private static DishRequest skyrDetailed() {
        return new DishRequest("Skyr natur", 63.0, 11.0, 4.0, 0.2, 150.0, 0.1, 4.0, 0.0, 0.13);
    }

    @Test
    void detailsAreStoredScaledAndSummed() {
        service.createDish("torben", skyrDetailed());
        String id = service.dishes("torben").get(0).id();
        DaySummary day = service.addEntry("torben", new NewEntryRequest(TODAY, id, null, 200.0, Meal.BREAKFAST));

        assertThat(service.dishes("torben").get(0).per100g().sugarG()).isEqualTo(4.0);
        assertThat(day.consumed().sugarG()).isEqualTo(8.0);
        assertThat(day.consumed().saltG()).isEqualTo(0.26);
        assertThat(day.consumed().fiberG()).isEqualTo(0.0);
        // Alle Eintraege haben alle Werte - keine Luecke.
        assertThat(day.detailGaps()).isEmpty();
        // Fuer Detailwerte gibt es kein Ziel, also auch keinen Rest.
        assertThat(day.remaining().sugarG()).isNull();
    }

    /**
     * Ein Eintrag ohne Angabe macht die Summe zur Untergrenze - und das sagt der Tag,
     * statt einen Zucker-Wert vorzutaeuschen, der vollstaendig aussieht.
     */
    @Test
    void aDayWithAnEntryWithoutDetailsReportsTheGaps() {
        service.createDish("torben", skyrDetailed());
        String id = service.dishes("torben").get(0).id();
        service.addEntry("torben", new NewEntryRequest(TODAY, id, null, 100.0, Meal.BREAKFAST));
        DaySummary day = service.addEntry("torben", new NewEntryRequest(TODAY, null, skyr(), 300.0, Meal.SNACK));

        assertThat(day.detailGaps()).containsExactly("saturatedFatG", "sugarG", "fiberG", "saltG");
        assertThat(day.consumed().sugarG()).isEqualTo(4.0);
    }

    /** Felix' Tagebuch: nie ein Detailwert, also auch keine Luecke und kein neues Feld. */
    @Test
    void aDayWithoutAnyDetailsLooksExactlyAsBefore() throws Exception {
        DaySummary day = service.addEntry(ME, new NewEntryRequest(TODAY, null, skyr(), 300.0, null));
        assertThat(day.detailGaps()).isEmpty();
        assertThat(day.consumed().hasDetails()).isFalse();

        String json = new ObjectMapper().writeValueAsString(day);
        assertThat(json).doesNotContain("saturatedFatG", "sugarG", "fiberG", "saltG", "detailGaps");
        assertThat(new ObjectMapper().writeValueAsString(service.dishes(ME))).doesNotContain("sugarG");
    }

    @Test
    void detailsAreOptionalButChecked() {
        assertThatThrownBy(() -> service.createDish("torben",
                new DishRequest("Kaputt", 100.0, 1.0, 1.0, 1.0, null, null, -1.0, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("sugarG");
        assertThatThrownBy(() -> service.createDish("torben",
                new DishRequest("Kaputt", 100.0, 1.0, 1.0, 1.0, null, null, null, null, 101.0)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("saltG");
        // Nur einer von vier gesetzt ist in Ordnung - leer bleibt leer.
        service.createDish("torben", new DishRequest("Apfel", 52.0, 0.3, 14.0, 0.2, 150.0, null, 10.0, null, null));
        Dish apple = service.dishes("torben").get(0);
        assertThat(apple.per100g().sugarG()).isEqualTo(10.0);
        assertThat(apple.per100g().fiberG()).isNull();
    }

    @Test
    void quickCaptureAsksForDetailsOnlyForPeopleWhoTrackThem() {
        FoodRepository repository = new FoodRepository(tempDir.resolve("food2.json").toString(), new ObjectMapper(), FILES);
        Clock clock = Clock.fixed(TODAY.atStartOfDay(ZoneId.of("UTC")).toInstant(), ZoneId.of("UTC"));
        FoodService detailedService = new FoodService(repository, extractor, clock, new DetailedNutrition("torben"));

        detailedService.quickCapture("torben", new QuickCaptureRequest(TODAY, "ein Teller Pasta", null));
        assertThat(extractor.seenDetailed).isTrue();
        detailedService.quickCapture(ME, new QuickCaptureRequest(TODAY, "ein Teller Pasta", null));
        assertThat(extractor.seenDetailed).isFalse();
    }

    @Test
    void theProposalCarriesDetailsWithTheirSources() {
        extractor.next = new ExtractedDish("Pasta", 150, 5, 30, 1, 400, 350.0, List.of("sugarPer100g"),
                List.of("kcalPer100g", "proteinPer100g", "carbsPer100g", "fatPer100g", "grams", "saltPer100g"),
                "geschaetzt", Meal.DINNER, null, 2.0, null, 0.4);
        QuickCapturePreview preview = service.quickCapture("torben", new QuickCaptureRequest(TODAY, "Pasta", null));
        assertThat(preview.per100g().sugarG()).isEqualTo(2.0);
        assertThat(preview.per100g().saltG()).isEqualTo(0.4);
        assertThat(preview.per100g().saturatedFatG()).isNull();
        assertThat(preview.valueSources()).containsEntry("sugarG", "lookedUp").containsEntry("saltG", "estimated")
                .doesNotContainKey("fiberG");
    }

    /** Ein bekanntes Gericht bringt seine gespeicherten Detailwerte mit. */
    @Test
    void aKnownDishKeepsItsStoredDetails() {
        service.createDish("torben", skyrDetailed());
        extractor.next = new ExtractedDish("Skyr natur", 70, 10, 5, 1, 150, null, List.of(), List.of(), "", Meal.SNACK);
        QuickCapturePreview preview = service.quickCapture("torben", new QuickCaptureRequest(TODAY, "Skyr", null));
        assertThat(preview.known()).isTrue();
        assertThat(preview.per100g().sugarG()).isEqualTo(4.0);
        assertThat(preview.valueSources()).containsEntry("sugarG", "stored");
    }

    // --- Mikronaehrstoffe --------------------------------------------------------

    /** Haferflocken laut Packung und Tabelle, mit allen vierzehn Mikronaehrstoffen. */
    private static Map<String, Double> oatMicros() {
        Map<String, Double> micros = new java.util.LinkedHashMap<>();
        micros.put("vitaminAUg", 0.0);
        micros.put("vitaminDUg", 0.0);
        micros.put("vitaminEMg", 0.8);
        micros.put("vitaminCMg", 0.0);
        micros.put("vitaminB2Mg", 0.155);
        micros.put("vitaminB12Ug", 0.0);
        micros.put("folateUg", 33.0);
        micros.put("calciumMg", 54.0);
        micros.put("magnesiumMg", 130.0);
        micros.put("potassiumMg", 380.0);
        micros.put("ironMg", 4.2);
        micros.put("zincMg", 3.6);
        micros.put("iodineUg", 1.5);
        micros.put("seleniumUg", 6.0);
        return micros;
    }

    private static DishRequest oats(Map<String, Double> micros) {
        return new DishRequest("Haferflocken", 372.0, 13.5, 58.7, 7.0, 60.0, null, null, null, null, micros);
    }

    @Test
    void microsAreStoredScaledAndSummed() {
        micro.createDish("torben", oats(oatMicros()));
        String id = micro.dishes("torben").get(0).id();
        micro.addEntry("torben", new NewEntryRequest(TODAY, id, null, 60.0, Meal.BREAKFAST));
        DaySummary day = micro.addEntry("torben", new NewEntryRequest(TODAY, id, null, 40.0, Meal.SNACK));

        assertThat(micro.dishes("torben").get(0).per100g().micros()).isEqualTo(oatMicros());
        // 60 g + 40 g = 100 g, also genau die Werte je 100 g - auf zwei Stellen gerundet.
        assertThat(day.consumed().micro("magnesiumMg")).isEqualTo(130.0);
        assertThat(day.consumed().micro("vitaminB2Mg")).isEqualTo(0.16);
        assertThat(day.consumed().micro("vitaminCMg")).isEqualTo(0.0);
        assertThat(day.entries().get(0).total().micro("ironMg")).isCloseTo(2.52, org.assertj.core.data.Offset.offset(1e-9));
        // Jeder Eintrag hat jeden Wert - keine Luecke. Und ein Rest wird nicht gerechnet.
        assertThat(day.microGaps()).isEmpty();
        assertThat(day.remaining().micros()).isNull();
    }

    /** Ein Eintrag ohne Wert macht die Summe zur Untergrenze - und das sagt der Tag. */
    @Test
    void aDayWithAnEntryWithoutMicrosReportsTheGaps() {
        micro.createDish("torben", oats(oatMicros()));
        String id = micro.dishes("torben").get(0).id();
        micro.addEntry("torben", new NewEntryRequest(TODAY, id, null, 100.0, Meal.BREAKFAST));
        DaySummary day = micro.addEntry("torben", new NewEntryRequest(TODAY, null, skyr(), 300.0, Meal.SNACK));

        assertThat(day.microGaps()).containsExactlyElementsOf(Micronutrient.KEYS);
        assertThat(day.consumed().micro("ironMg")).isEqualTo(4.2);
    }

    /** Nur die Schluessel, zu denen wirklich ein Wert fehlt - nicht alle, sobald einer fehlt. */
    @Test
    void theGapsNameOnlyTheMissingKeys() {
        micro.addEntry("torben", new NewEntryRequest(TODAY, null, new DishRequest("Orange", 47.0, 0.9, 9.0, 0.1,
                150.0, null, null, null, null, Map.of("vitaminCMg", 53.0, "potassiumMg", 180.0)), 150.0, Meal.SNACK));
        DaySummary day = micro.addEntry("torben", new NewEntryRequest(TODAY, null, new DishRequest("Kiwi", 61.0,
                1.1, 10.0, 0.5, 75.0, null, null, null, null, Map.of("vitaminCMg", 90.0)), 75.0, Meal.SNACK));

        assertThat(day.microGaps()).doesNotContain("vitaminCMg").contains("potassiumMg", "ironMg").hasSize(13);
        assertThat(day.consumed().micro("vitaminCMg")).isEqualTo(147.0);   // 79,5 + 67,5
        assertThat(day.consumed().micro("potassiumMg")).isEqualTo(270.0);
        assertThat(day.consumed().micro("ironMg")).isNull();
    }

    @Test
    void microsAreOptionalButChecked() {
        assertThatThrownBy(() -> micro.createDish("torben", oats(Map.of("vitaminXMg", 1.0))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400").hasMessageContaining("micros.vitaminXMg is not a known micronutrient");
        assertThatThrownBy(() -> micro.createDish("torben", oats(Map.of("ironMg", -0.1))))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("micros.ironMg");
        assertThatThrownBy(() -> micro.createDish("torben", oats(Map.of("ironMg", Double.NaN))))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("micros.ironMg");
        // Hoechstens das Gegenstueck von 10 g je 100 g: 10.000 mg oder 10.000.000 µg.
        assertThatThrownBy(() -> micro.createDish("torben", oats(Map.of("calciumMg", 10_000.5))))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("micros.calciumMg must be at most");
        assertThatThrownBy(() -> micro.createDish("torben", oats(Map.of("iodineUg", 10_000_001.0))))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("micros.iodineUg");
        assertThat(micro.dishes("torben")).isEmpty();

        // Eine 0 ist eine Angabe, ein Schluessel ohne Wert keine.
        Map<String, Double> sparse = new HashMap<>();
        sparse.put("vitaminB12Ug", 0.0);
        sparse.put("iodineUg", 10_000_000.0);
        sparse.put("zincMg", null);
        Dish stored = micro.createDish("torben", oats(sparse));
        assertThat(stored.per100g().micros()).containsExactly(
                Map.entry("vitaminB12Ug", 0.0), Map.entry("iodineUg", 10_000_000.0));
    }

    /** PUT ersetzt das Gericht ganz: wer die Mikronaehrstoffe behalten will, schickt sie mit. */
    @Test
    void savingADishWithoutMicrosRemovesThem() {
        Dish created = micro.createDish("torben", oats(oatMicros()));
        Dish updated = micro.updateDish("torben", created.id(), oats(null));
        assertThat(updated.per100g().micros()).isNull();
        assertThat(micro.dishes("torben").get(0).per100g().hasMicros()).isFalse();
    }

    /** Fuer alle anderen bleibt micros unbeachtet - nicht gespeichert, nicht einmal geprueft. */
    @Test
    void microsAreIgnoredForEveryoneElse() throws Exception {
        Dish dish = micro.createDish(ME, oats(Map.of("ironMg", 4.2, "vitaminXMg", -5.0)));
        DaySummary day = micro.addEntry(ME, new NewEntryRequest(TODAY, null, new DishRequest("Skyr mit Beeren",
                80.0, 8.0, 6.0, 1.3, 300.0, null, null, null, null, Map.of("calciumMg", 120.0)), 300.0, null));
        micro.updateTargets(ME, new TargetsRequest(2300.0, 200.0, 235.5, 62.0, null, Map.of("vitaminCMg", 0.0)));

        assertThat(dish.per100g().micros()).isNull();
        assertThat(day.entries()).allSatisfy(e -> assertThat(e.per100g().micros()).isNull());
        assertThat(micro.targets(ME).micros()).isNull();
        String json = new ObjectMapper().writeValueAsString(micro.day(ME, TODAY))
                + new ObjectMapper().writeValueAsString(micro.dishes(ME));
        assertThat(json).doesNotContain("micros", "microGaps", "ironMg");
        // Auch in der Datei steht nichts Neues.
        assertThat(Files.readString(tempDir.resolve("food.json"))).doesNotContain("micro");
    }

    // --- Mikro-Ziele ------------------------------------------------------------

    /** Wer nie Mikro-Ziele gespeichert hat, bekommt die DGE-Vorgabe - im Tag und unter /targets. */
    @Test
    void neverSavedMicroTargetsAreTheDgeValues() {
        assertThat(micro.targets("torben").micros()).isEqualTo(Micronutrient.defaultTargets());
        assertThat(micro.day("torben", TODAY).targets().micros()).isEqualTo(Micronutrient.defaultTargets());
        // Die Grundwerte bleiben die gespeicherten.
        assertThat(micro.targets("torben").kcal()).isEqualTo(2300.0);
        assertThat(micro.targets(ME).micros()).isNull();
    }

    /** Was gespeichert ist, gilt - ein fehlender Schluessel heisst "kein Ziel", nicht "Vorgabe". */
    @Test
    void savedMicroTargetsReplaceTheDefaults() {
        Nutrients saved = micro.updateTargets("torben", new TargetsRequest(2800.0, 180.0, 300.0, 90.0, null,
                Map.of("vitaminDUg", 25.0, "ironMg", 10.0)));

        assertThat(saved.micros()).containsExactly(Map.entry("vitaminDUg", 25.0), Map.entry("ironMg", 10.0));
        assertThat(micro.targets("torben").micros()).isEqualTo(saved.micros());
        assertThat(micro.day("torben", TODAY).targets().micro("vitaminCMg")).isNull();
    }

    /** Eine leere Liste ist "bewusst ohne Ziel" - und bleibt es, statt zur Vorgabe zurueckzufallen. */
    @Test
    void anEmptyListMeansDeliberatelyNoMicroTargets() throws Exception {
        Nutrients saved = micro.updateTargets("torben",
                new TargetsRequest(2800.0, 180.0, 300.0, 90.0, null, Map.of()));

        assertThat(saved.micros()).isNull();
        assertThat(micro.targets("torben").micros()).isNull();
        assertThat(new ObjectMapper().writeValueAsString(micro.targets("torben"))).doesNotContain("micros");
        assertThat(Files.readString(tempDir.resolve("users/torben/food.json"))).contains("\"microTargets\" : { }");
    }

    /** Ein Client, der das Feld nicht kennt, loescht nichts - weder gespeicherte Ziele noch die Vorgabe. */
    @Test
    void targetsWithoutMicrosKeepWhatIsStored() {
        micro.updateTargets("torben", new TargetsRequest(2800.0, 180.0, 300.0, 90.0, null));
        assertThat(micro.targets("torben").micros()).isEqualTo(Micronutrient.defaultTargets());

        micro.updateTargets("torben", new TargetsRequest(2800.0, 180.0, 300.0, 90.0, null, Map.of("zincMg", 11.0)));
        micro.updateTargets("torben", new TargetsRequest(2700.0, 180.0, 300.0, 90.0, null));
        assertThat(micro.targets("torben").micros()).containsExactly(Map.entry("zincMg", 11.0));
        assertThat(micro.targets("torben").kcal()).isEqualTo(2700.0);
    }

    /** Eintragen, Loeschen und Gerichte pflegen speichern das ganze Tagebuch - die Mikro-Ziele muessen mit. */
    @Test
    void microTargetsSurviveEveryOtherChange() {
        micro.updateTargets("torben", new TargetsRequest(2800.0, 180.0, 300.0, 90.0, null, Map.of("zincMg", 11.0)));
        Dish dish = micro.createDish("torben", oats(oatMicros()));
        DaySummary day = micro.addEntry("torben", new NewEntryRequest(TODAY, dish.id(), null, 50.0, null));
        micro.updateEntry("torben", day.entries().get(0).id(), new UpdateEntryRequest(70.0, null, null));
        micro.updateDish("torben", dish.id(), oats(Map.of()));
        micro.deleteEntry("torben", day.entries().get(0).id());
        micro.deleteDish("torben", dish.id());

        assertThat(micro.targets("torben").micros()).containsExactly(Map.entry("zincMg", 11.0));
    }

    @Test
    void microTargetsAreChecked() {
        assertThatThrownBy(() -> micro.updateTargets("torben",
                new TargetsRequest(2800.0, 180.0, 300.0, 90.0, null, Map.of("vitaminCMg", 0.0))))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("micros.vitaminCMg must be greater than 0");
        assertThatThrownBy(() -> micro.updateTargets("torben",
                new TargetsRequest(2800.0, 180.0, 300.0, 90.0, null, Map.of("vitaminK", 70.0))))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("micros.vitaminK");
        assertThatThrownBy(() -> micro.updateTargets("torben",
                new TargetsRequest(2800.0, 180.0, 300.0, 90.0, null, Map.of("potassiumMg", 20_000.0))))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("micros.potassiumMg must be at most");
        // Nichts davon wurde gespeichert - auch nicht die Grundwerte.
        assertThat(micro.targets("torben").kcal()).isEqualTo(2300.0);
        assertThat(micro.targets("torben").micros()).isEqualTo(Micronutrient.defaultTargets());
    }

    // --- Mikronaehrstoffe in der Schnellerfassung -----------------------------------

    @Test
    void quickCaptureAsksForMicrosOnlyForPeopleWhoTrackThem() {
        micro.quickCapture("torben", new QuickCaptureRequest(TODAY, "ein Teller Linsensuppe", null));
        assertThat(extractor.seenMicronutrients).isTrue();
        micro.quickCapture(ME, new QuickCaptureRequest(TODAY, "ein Teller Linsensuppe", null));
        assertThat(extractor.seenMicronutrients).isFalse();
    }

    @Test
    void theProposalCarriesMicrosWithTheirSources() {
        extractor.next = new ExtractedDish("Linsensuppe", 95, 6, 12, 2.5, 400, 400.0, List.of("ironMg"),
                List.of("kcalPer100g", "proteinPer100g", "carbsPer100g", "fatPer100g", "grams", "folateUg"),
                "geschaetzt", Meal.LUNCH, null, null, null, null,
                Map.of("ironMg", 2.14567, "folateUg", 60.0, "potassiumMg", 310.0));

        QuickCapturePreview preview = micro.quickCapture("torben", new QuickCaptureRequest(TODAY, "Linsensuppe", null));

        assertThat(preview.per100g().micros()).containsExactly(
                Map.entry("folateUg", 60.0), Map.entry("potassiumMg", 310.0), Map.entry("ironMg", 2.15));
        assertThat(preview.valueSources())
                .containsEntry("ironMg", "lookedUp")
                .containsEntry("folateUg", "estimated")
                .containsEntry("potassiumMg", "read")
                .doesNotContainKey("zincMg");
    }

    /** Ein bekanntes Gericht bringt seine gespeicherten Mikronaehrstoffe mit - "gespeichert" nur, wo einer ist. */
    @Test
    void aKnownDishKeepsItsStoredMicros() {
        micro.createDish("torben", oats(Map.of("ironMg", 4.2, "magnesiumMg", 130.0)));
        extractor.next = new ExtractedDish("haferflocken", 380, 12, 60, 7, 50, null, List.of(), List.of(), "",
                Meal.BREAKFAST, null, null, null, null, Map.of("ironMg", 3.0, "zincMg", 3.0));

        QuickCapturePreview preview = micro.quickCapture("torben", new QuickCaptureRequest(TODAY, "Haferflocken", null));

        assertThat(preview.known()).isTrue();
        assertThat(preview.per100g().micros()).containsExactly(Map.entry("magnesiumMg", 130.0), Map.entry("ironMg", 4.2));
        assertThat(preview.valueSources()).containsEntry("ironMg", "stored").containsEntry("magnesiumMg", "stored")
                .doesNotContainKey("zincMg");
    }

    /** Liefert der Agent sie ungefragt, landen sie trotzdem nicht in Felix' Vorschlag. */
    @Test
    void microsFromTheAgentNeverReachSomeoneElsesProposal() throws Exception {
        extractor.next = new ExtractedDish("Linsensuppe", 95, 6, 12, 2.5, 400, 400.0, List.of("ironMg"), List.of(),
                "", Meal.LUNCH, null, null, null, null, Map.of("ironMg", 2.1));

        QuickCapturePreview preview = micro.quickCapture(ME, new QuickCaptureRequest(TODAY, "Linsensuppe", null));

        assertThat(preview.per100g().micros()).isNull();
        assertThat(preview.valueSources()).doesNotContainKey("ironMg");
        assertThat(new ObjectMapper().writeValueAsString(preview)).doesNotContain("micros", "ironMg");
    }
}

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
import com.fherrmann.food.model.Meal;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.nio.file.Files;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;
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
        private Path seenPhoto;
        private byte[] seenPhotoBytes;

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public ExtractedDish extract(String text, Path photo, Nutrients targets, List<Dish> known,
                                     boolean detailed) {
            seenText = text;
            seenKnown = known;
            seenDetailed = detailed;
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
}

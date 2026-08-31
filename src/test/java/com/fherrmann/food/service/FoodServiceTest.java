package com.fherrmann.food.service;

import com.fherrmann.food.dto.DaySummary;
import com.fherrmann.food.dto.DayTotal;
import com.fherrmann.food.dto.DishRequest;
import com.fherrmann.food.dto.NewEntryRequest;
import com.fherrmann.food.dto.QuickCaptureRequest;
import com.fherrmann.food.dto.QuickCaptureResult;
import com.fherrmann.food.dto.StatusInfo;
import com.fherrmann.food.dto.TargetsRequest;
import com.fherrmann.food.model.Dish;
import com.fherrmann.food.model.Meal;
import com.fherrmann.food.model.Nutrients;
import com.fherrmann.food.repository.FoodRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FoodServiceTest {

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
                "Spaghetti Bolognese", 130, 7, 16, 4, 450, 400.0, true,
                "Portion und Naehrwerte fuer einen grossen Teller geschaetzt.", Meal.LUNCH);
        private boolean available = true;
        private String seenText;
        private List<Dish> seenKnown;

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public ExtractedDish extract(String text, Nutrients targets, List<Dish> known) {
            seenText = text;
            seenKnown = known;
            return next;
        }
    }

    @BeforeEach
    void setUp() {
        FoodRepository repository = new FoodRepository(
                tempDir.resolve("food.json").toString(), new ObjectMapper());
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
        var targets = service.targets();
        assertThat(targets.proteinG() * 4 + targets.carbsG() * 4 + targets.fatG() * 9)
                .isEqualTo(targets.kcal());
    }

    @Test
    void loggingAnUnknownDishRemembersItAndCountsTheAmount() {
        DaySummary day = service.addEntry(new NewEntryRequest(TODAY, null, skyr(), 300.0, null));

        assertThat(day.date()).isEqualTo(TODAY);
        assertThat(day.entries()).hasSize(1);
        // 300 g sind dreimal die 100-g-Werte.
        assertThat(day.consumed().kcal()).isEqualTo(240.0);
        assertThat(day.consumed().proteinG()).isEqualTo(24.0);
        assertThat(day.consumed().fatG()).isEqualTo(3.9);
        assertThat(day.remaining().kcal()).isEqualTo(2060.0);

        assertThat(service.dishes()).singleElement()
                .extracting(Dish::name, Dish::portionG, Dish::lastUsedOn)
                .containsExactly("Skyr mit Beeren", 300.0, TODAY);
    }

    @Test
    void aRememberedDishCanBeLoggedAgainById() {
        service.addEntry(new NewEntryRequest(TODAY, null, skyr(), 300.0, null));
        String id = service.dishes().get(0).id();

        DaySummary day = service.addEntry(new NewEntryRequest(TODAY, id, null, 150.0, null));

        assertThat(day.entries()).hasSize(2);
        assertThat(day.consumed().kcal()).isEqualTo(360.0);
        assertThat(service.dishes()).hasSize(1);
    }

    @Test
    void editingADishLeavesAlreadyLoggedEntriesUntouched() {
        service.addEntry(new NewEntryRequest(TODAY, null, skyr(), 300.0, null));
        String id = service.dishes().get(0).id();

        service.updateDish(id, new DishRequest("Skyr mit Beeren", 200.0, 8.0, 6.0, 1.3, 300.0));

        // Der Eintrag von heute rechnet weiter mit den 80 kcal/100 g von damals.
        assertThat(service.day(TODAY).consumed().kcal()).isEqualTo(240.0);
        assertThat(service.dishes().get(0).per100g().kcal()).isEqualTo(200.0);
    }

    @Test
    void deletingADishKeepsTheHistoryThatUsedIt() {
        service.addEntry(new NewEntryRequest(TODAY, null, skyr(), 300.0, null));
        service.deleteDish(service.dishes().get(0).id());

        assertThat(service.dishes()).isEmpty();
        assertThat(service.day(TODAY).entries()).singleElement()
                .extracting(e -> e.name()).isEqualTo("Skyr mit Beeren");
        assertThat(service.day(TODAY).consumed().kcal()).isEqualTo(240.0);
    }

    @Test
    void enteringAKnownNameAgainUpdatesTheDishInsteadOfDuplicatingIt() {
        service.addEntry(new NewEntryRequest(TODAY, null, skyr(), 300.0, null));
        service.addEntry(new NewEntryRequest(TODAY, null,
                new DishRequest("skyr MIT beeren", 90.0, 9.0, 6.0, 1.0, 250.0), 100.0, null));

        assertThat(service.dishes()).hasSize(1);
        assertThat(service.dishes().get(0).per100g().kcal()).isEqualTo(90.0);
        assertThat(service.dishes().get(0).portionG()).isEqualTo(250.0);
    }

    @Test
    void renamingOntoAnExistingNameIsRejected() {
        service.createDish(skyr());
        service.createDish(new DishRequest("Haferflocken", 370.0, 13.0, 59.0, 7.0, null));
        String id = service.dishes().stream()
                .filter(d -> d.name().equals("Haferflocken")).findFirst().orElseThrow().id();

        assertThatThrownBy(() -> service.updateDish(id, skyr()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
    }

    @Test
    void backfillingAnOlderDayDoesNotMoveTheDishToTheTopOfThePicker() {
        service.addEntry(new NewEntryRequest(TODAY, null, skyr(), 300.0, null));
        String id = service.dishes().get(0).id();
        service.addEntry(new NewEntryRequest(TODAY.minusDays(5), id, null, 300.0, null));

        assertThat(service.dishes().get(0).lastUsedOn()).isEqualTo(TODAY);
    }

    @Test
    void deletingAnEntryRemovesItFromItsDay() {
        DaySummary day = service.addEntry(new NewEntryRequest(TODAY, null, skyr(), 300.0, null));
        String entryId = day.entries().get(0).id();

        DaySummary after = service.deleteEntry(entryId);

        assertThat(after.entries()).isEmpty();
        assertThat(after.consumed().kcal()).isZero();
    }

    @Test
    void dailyTotalsCoverOnlyDaysWithEntriesInsideTheRange() {
        service.addEntry(new NewEntryRequest(TODAY.minusDays(2), null, skyr(), 300.0, null));
        service.addEntry(new NewEntryRequest(TODAY, null,
                new DishRequest("Haferflocken", 370.0, 13.0, 59.0, 7.0, null), 100.0, null));

        List<DayTotal> totals = service.dailyTotals(TODAY.minusDays(3), TODAY);

        // Der Tag dazwischen taucht nicht als 0 kcal auf - nichts eingetragen heisst
        // "unbekannt", nicht "nichts gegessen".
        assertThat(totals).hasSize(2);
        assertThat(totals.get(0).date()).isEqualTo(TODAY.minusDays(2));
        assertThat(totals.get(0).consumed().kcal()).isEqualTo(240.0);
        assertThat(totals.get(1).consumed().kcal()).isEqualTo(370.0);
    }

    @Test
    void dailyTotalsRejectAnInvertedRange() {
        assertThatThrownBy(() -> service.dailyTotals(TODAY, TODAY.minusDays(1)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void targetsCanBeChanged() {
        var targets = service.updateTargets(new TargetsRequest(2000.0, 150.0, 200.0, 65.0));
        assertThat(targets.kcal()).isEqualTo(2000.0);
        assertThat(service.day(TODAY).targets().proteinG()).isEqualTo(150.0);
    }

    @Test
    void statusReportsTodaysFigures() {
        service.addEntry(new NewEntryRequest(TODAY, null, skyr(), 300.0, null));

        StatusInfo status = service.status();

        assertThat(status.lastResult()).isEqualTo("ok");
        assertThat(status.today()).isEqualTo(TODAY);
        assertThat(status.kcalConsumed()).isEqualTo(240.0);
        assertThat(status.kcalTarget()).isEqualTo(2300.0);
        assertThat(status.entriesToday()).isEqualTo(1);
        assertThat(status.dishCount()).isEqualTo(1);
        assertThat(status.lastEntryOn()).isEqualTo(TODAY);
    }

    @Test
    void quickCaptureCreatesBothAnEntryAndAReusableDish() {
        QuickCaptureResult result = service.quickCapture(
                new QuickCaptureRequest(TODAY, "  mittags einen grossen Teller Spaghetti Bolognese  ", null));

        // Der Text geht getrimmt rein, so wie er getippt wurde.
        assertThat(extractor.seenText).isEqualTo("mittags einen grossen Teller Spaghetti Bolognese");

        assertThat(result.dishName()).isEqualTo("Spaghetti Bolognese");
        assertThat(result.grams()).isEqualTo(450.0);
        assertThat(result.estimated()).isTrue();
        assertThat(result.note()).contains("geschaetzt");
        // 450 g mal 130 kcal je 100 g.
        assertThat(result.day().consumed().kcal()).isEqualTo(585.0);

        // Und das Gericht steht danach zur Auswahl - genau darum geht es.
        assertThat(service.dishes()).singleElement()
                .extracting(Dish::name, Dish::portionG)
                .containsExactly("Spaghetti Bolognese", 400.0);
    }

    @Test
    void anEntryWithoutAMealLandsUnderSnacks() {
        // Sonst taeuchte es in keinem der vier Abschnitte auf.
        DaySummary day = service.addEntry(new NewEntryRequest(TODAY, null, skyr(), 300.0, null));
        assertThat(day.entries()).singleElement()
                .extracting(e -> e.meal()).isEqualTo(Meal.SNACK);
    }

    @Test
    void theChosenSectionBeatsTheAgentsGuess() {
        // Der Fake-Extractor tippt auf LUNCH; wer aus dem Fruehstuecks-Abschnitt
        // kommt, hat aber schon gesagt, was er meint.
        QuickCaptureResult result = service.quickCapture(
                new QuickCaptureRequest(TODAY, "ein Teller Bolognese", Meal.BREAKFAST));
        assertThat(result.meal()).isEqualTo(Meal.BREAKFAST);
        assertThat(result.day().entries()).singleElement()
                .extracting(e -> e.meal()).isEqualTo(Meal.BREAKFAST);
    }

    @Test
    void withoutASectionTheAgentsGuessIsUsed() {
        QuickCaptureResult result = service.quickCapture(
                new QuickCaptureRequest(TODAY, "mittags ein Teller Bolognese", null));
        assertThat(result.meal()).isEqualTo(Meal.LUNCH);
    }

    @Test
    void quickCaptureGetsTheAlreadyKnownDishesAsContext() {
        service.createDish(skyr());
        service.quickCapture(new QuickCaptureRequest(TODAY, "ein Becher Skyr", null));

        assertThat(extractor.seenKnown).extracting(Dish::name).containsExactly("Skyr mit Beeren");
    }

    @Test
    void quickCaptureRunsThroughTheSameLimitsAsAManualEntry() {
        // Ein Modell, das sich um eine Zehnerpotenz vertut, kommt an der Pruefung
        // fuer Eintraege von Hand nicht vorbei.
        extractor.next = new ExtractedDish("Unfug", 99_000, 7, 16, 4, 450, null, true, "", null);
        assertThatThrownBy(() -> service.quickCapture(new QuickCaptureRequest(TODAY, "irgendwas", null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("kcal");
    }

    @Test
    void quickCaptureRejectsEmptyAndOverlongText() {
        assertThatThrownBy(() -> service.quickCapture(new QuickCaptureRequest(TODAY, "   ", null)))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.quickCapture(
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
        assertThatThrownBy(() -> service.addEntry(new NewEntryRequest(TODAY, null, skyr(), 0.0, null)))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.addEntry(new NewEntryRequest(TODAY, null, skyr(), 25_000.0, null)))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.addEntry(new NewEntryRequest(TODAY, null, null, 100.0, null)))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.addEntry(new NewEntryRequest(TODAY, "nope", null, 100.0, null)))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.createDish(new DishRequest("  ", 80.0, 8.0, 6.0, 1.3, null)))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.createDish(new DishRequest("X", -1.0, 8.0, 6.0, 1.3, null)))
                .isInstanceOf(ResponseStatusException.class);
    }
}

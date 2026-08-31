package com.fherrmann.food.service;

import com.fherrmann.food.dto.DaySummary;
import com.fherrmann.food.dto.DayTotal;
import com.fherrmann.food.dto.DishRequest;
import com.fherrmann.food.dto.NewEntryRequest;
import com.fherrmann.food.dto.StatusInfo;
import com.fherrmann.food.dto.TargetsRequest;
import com.fherrmann.food.model.Dish;
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

    @BeforeEach
    void setUp() {
        FoodRepository repository = new FoodRepository(
                tempDir.resolve("food.json").toString(), new ObjectMapper());
        Clock clock = Clock.fixed(TODAY.atStartOfDay(ZoneId.of("UTC")).toInstant(), ZoneId.of("UTC"));
        service = new FoodService(repository, clock);
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
        DaySummary day = service.addEntry(new NewEntryRequest(TODAY, null, skyr(), 300.0));

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
        service.addEntry(new NewEntryRequest(TODAY, null, skyr(), 300.0));
        String id = service.dishes().get(0).id();

        DaySummary day = service.addEntry(new NewEntryRequest(TODAY, id, null, 150.0));

        assertThat(day.entries()).hasSize(2);
        assertThat(day.consumed().kcal()).isEqualTo(360.0);
        assertThat(service.dishes()).hasSize(1);
    }

    @Test
    void editingADishLeavesAlreadyLoggedEntriesUntouched() {
        service.addEntry(new NewEntryRequest(TODAY, null, skyr(), 300.0));
        String id = service.dishes().get(0).id();

        service.updateDish(id, new DishRequest("Skyr mit Beeren", 200.0, 8.0, 6.0, 1.3, 300.0));

        // Der Eintrag von heute rechnet weiter mit den 80 kcal/100 g von damals.
        assertThat(service.day(TODAY).consumed().kcal()).isEqualTo(240.0);
        assertThat(service.dishes().get(0).per100g().kcal()).isEqualTo(200.0);
    }

    @Test
    void deletingADishKeepsTheHistoryThatUsedIt() {
        service.addEntry(new NewEntryRequest(TODAY, null, skyr(), 300.0));
        service.deleteDish(service.dishes().get(0).id());

        assertThat(service.dishes()).isEmpty();
        assertThat(service.day(TODAY).entries()).singleElement()
                .extracting(e -> e.name()).isEqualTo("Skyr mit Beeren");
        assertThat(service.day(TODAY).consumed().kcal()).isEqualTo(240.0);
    }

    @Test
    void enteringAKnownNameAgainUpdatesTheDishInsteadOfDuplicatingIt() {
        service.addEntry(new NewEntryRequest(TODAY, null, skyr(), 300.0));
        service.addEntry(new NewEntryRequest(TODAY, null,
                new DishRequest("skyr MIT beeren", 90.0, 9.0, 6.0, 1.0, 250.0), 100.0));

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
        service.addEntry(new NewEntryRequest(TODAY, null, skyr(), 300.0));
        String id = service.dishes().get(0).id();
        service.addEntry(new NewEntryRequest(TODAY.minusDays(5), id, null, 300.0));

        assertThat(service.dishes().get(0).lastUsedOn()).isEqualTo(TODAY);
    }

    @Test
    void deletingAnEntryRemovesItFromItsDay() {
        DaySummary day = service.addEntry(new NewEntryRequest(TODAY, null, skyr(), 300.0));
        String entryId = day.entries().get(0).id();

        DaySummary after = service.deleteEntry(entryId);

        assertThat(after.entries()).isEmpty();
        assertThat(after.consumed().kcal()).isZero();
    }

    @Test
    void dailyTotalsCoverOnlyDaysWithEntriesInsideTheRange() {
        service.addEntry(new NewEntryRequest(TODAY.minusDays(2), null, skyr(), 300.0));
        service.addEntry(new NewEntryRequest(TODAY, null,
                new DishRequest("Haferflocken", 370.0, 13.0, 59.0, 7.0, null), 100.0));

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
        service.addEntry(new NewEntryRequest(TODAY, null, skyr(), 300.0));

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
    void invalidInputIsRejected() {
        assertThatThrownBy(() -> service.addEntry(new NewEntryRequest(TODAY, null, skyr(), 0.0)))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.addEntry(new NewEntryRequest(TODAY, null, skyr(), 25_000.0)))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.addEntry(new NewEntryRequest(TODAY, null, null, 100.0)))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.addEntry(new NewEntryRequest(TODAY, "nope", null, 100.0)))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.createDish(new DishRequest("  ", 80.0, 8.0, 6.0, 1.3, null)))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.createDish(new DishRequest("X", -1.0, 8.0, 6.0, 1.3, null)))
                .isInstanceOf(ResponseStatusException.class);
    }
}

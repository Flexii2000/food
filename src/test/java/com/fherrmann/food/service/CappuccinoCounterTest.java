package com.fherrmann.food.service;

import com.fherrmann.food.dto.CappuccinoDay;
import com.fherrmann.food.dto.DaySummary;
import com.fherrmann.food.dto.DishRequest;
import com.fherrmann.food.dto.NewEntryRequest;
import com.fherrmann.food.dto.TargetsRequest;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Der Cappuccino-Zaehler: "+" und "−" als normale Eintraege, das eigene Budget als Anteil am
 * Tagesziel neben den Mahlzeiten (seit 23.11.) - und dass Personen ohne Zaehler davon nichts
 * merken.
 */
class CappuccinoCounterTest {

    private static final String ME = "felix";
    private static final String TORBEN = "torben";
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 21);

    @TempDir
    Path tempDir;

    private FoodService service;

    private static final class NoExtractor implements NutritionExtractor {
        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public ExtractedDish extract(String text, Path photo, Nutrients targets, List<Dish> known,
                                     boolean detailed, boolean micronutrients) {
            throw new UnsupportedOperationException();
        }
    }

    @BeforeEach
    void setUp() {
        FoodRepository repository = new FoodRepository(tempDir.resolve("food.json").toString(), new ObjectMapper(),
                new UserFiles(new HealthUsers(ME, "torben:0123456789abcdef0123456789abcdef")));
        Clock clock = Clock.fixed(TODAY.atStartOfDay(ZoneId.of("UTC")).toInstant(), ZoneId.of("UTC"));
        service = new FoodService(repository, new NoExtractor(), clock, DetailedNutrition.none(),
                MicronutrientTracking.none(), new CappuccinoCounter("", ME));
    }

    /** Felix' Gericht: 61 kcal, 1,1 g Eiweiß, 7,1 g KH, 3 g Fett je 100 g, Portion 123 g. */
    private Dish felixCappuccino() {
        return service.createDish(ME, new DishRequest("Cappuchino (zuhause)", 61.0, 1.1, 7.1, 3.0, 123.0));
    }

    @Test
    void onlyTheOwnerHasTheCounterByDefault() {
        assertThat(service.cappuccinoEnabled(ME)).isTrue();
        assertThat(service.cappuccinoEnabled(TORBEN)).isFalse();
        assertThat(service.day(TORBEN, TODAY).cappuccino()).isNull();
        assertThatThrownBy(() -> service.addCappuccino(TORBEN, TODAY, null))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void theExistingDishIsUsedAndEachPlusIsOnePortionAtBreakfast() {
        Dish dish = felixCappuccino();

        DaySummary day = service.addCappuccino(ME, null, null);

        assertThat(day.date()).isEqualTo(TODAY);
        assertThat(day.entries()).hasSize(1);
        assertThat(day.entries().get(0).dishId()).isEqualTo(dish.id());
        assertThat(day.entries().get(0).grams()).isEqualTo(123.0);
        assertThat(day.entries().get(0).meal()).isEqualTo(Meal.BREAKFAST);
        CappuccinoDay cups = day.cappuccino();
        assertThat(cups.count()).isEqualTo(1);
        assertThat(cups.perCup().kcal()).isEqualTo(75.0);
        assertThat(cups.perCup().proteinG()).isEqualTo(1.4);
        // Er zaehlt zum Tag - kcal und Eiweiß.
        assertThat(day.consumed().kcal()).isEqualTo(75.0);
        assertThat(day.consumed().proteinG()).isEqualTo(1.4);
    }

    @Test
    void untilSavedTheCupsTakeTenPercentFromBreakfast() {
        felixCappuccino();

        DaySummary day = service.day(ME, TODAY);

        // Felix' Startwert (23.11.): Fruehstueck 25 -> 15 %, Cappuccino 10 %.
        assertThat(day.cappuccino().share()).isEqualTo(0.10);
        assertThat(day.cappuccino().target()).isEqualTo(230.0);
        assertThat(day.mealTargets()).containsEntry(Meal.BREAKFAST, 345.0).containsEntry(Meal.LUNCH, 805.0)
                .containsEntry(Meal.DINNER, 690.0).containsEntry(Meal.SNACK, 230.0);
        double meals = day.mealTargets().values().stream().mapToDouble(Double::doubleValue).sum();
        assertThat(meals + day.cappuccino().target()).isEqualTo(day.targets().kcal());
    }

    @Test
    void cupsCountAgainstTheDayLikeEveryOtherEntry() {
        felixCappuccino();
        DaySummary before = service.day(ME, TODAY);

        DaySummary day = null;
        for (int i = 0; i < 4; i++) {
            day = service.addCappuccino(ME, TODAY, null);
        }

        assertThat(day.cappuccino().count()).isEqualTo(4);
        // Das Tagesziel waechst nicht mehr mit: der Rest sinkt um alle vier.
        assertThat(day.targets()).isEqualTo(before.targets());
        assertThat(day.remaining().kcal()).isCloseTo(before.remaining().kcal() - 300.1, within(0.01));
        assertThat(day.mealTargets()).isEqualTo(before.mealTargets());
        assertThat(day.cappuccino().target()).isEqualTo(before.cappuccino().target());
    }

    @Test
    void savedSharesOfMealsAndCupsMustAddUpTo100Percent() {
        felixCappuccino();
        Map<Meal, Double> meals = Map.of(Meal.BREAKFAST, 0.20, Meal.LUNCH, 0.35, Meal.DINNER, 0.30, Meal.SNACK, 0.05);

        service.updateTargets(ME, new TargetsRequest(2300.0, 200.0, 235.5, 62.0, meals, null, null, 0.10));

        DaySummary day = service.day(ME, TODAY);
        assertThat(day.cappuccino().share()).isEqualTo(0.10);
        assertThat(day.cappuccino().target()).isEqualTo(230.0);
        assertThat(day.mealTargets()).containsEntry(Meal.BREAKFAST, 460.0).containsEntry(Meal.SNACK, 115.0);
        // Vier Mahlzeiten mit 100 % und dazu der Cappuccino-Anteil von vorher: 110 %.
        Map<Meal, Double> full = Map.of(Meal.BREAKFAST, 0.25, Meal.LUNCH, 0.35, Meal.DINNER, 0.30, Meal.SNACK, 0.10);
        assertThatThrownBy(() -> service.updateTargets(ME, new TargetsRequest(2300.0, 200.0, 235.5, 62.0, full)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("110").hasMessageContaining("Cappuccino");
        // Nur der Cappuccino-Anteil geaendert: die Mahlzeiten von vorher zaehlen mit.
        assertThatThrownBy(() -> service.updateTargets(ME,
                new TargetsRequest(2300.0, 200.0, 235.5, 62.0, null, null, null, 0.15)))
                .isInstanceOf(ResponseStatusException.class);
        service.updateTargets(ME, new TargetsRequest(2300.0, 200.0, 235.5, 62.0, full, null, null, 0.0));
        assertThat(service.day(ME, TODAY).cappuccino().target()).isZero();
        // Nur die kcal geaendert: die Anteile bleiben, die Budgets wandern mit.
        service.updateTargets(ME, new TargetsRequest(2000.0, 200.0, 235.5, 62.0, null));
        assertThat(service.day(ME, TODAY).mealTargets()).containsEntry(Meal.BREAKFAST, 500.0);
    }

    @Test
    void peopleWithoutTheCounterKeepFourSharesAndIgnoreTheCupShare() {
        Map<Meal, Double> full = Map.of(Meal.BREAKFAST, 0.25, Meal.LUNCH, 0.35, Meal.DINNER, 0.30, Meal.SNACK, 0.10);

        service.updateTargets(TORBEN, new TargetsRequest(2800.0, 180.0, 300.0, 90.0, full, null, null, 0.2));

        DaySummary day = service.day(TORBEN, TODAY);
        assertThat(day.cappuccino()).isNull();
        assertThat(day.mealTargets()).containsEntry(Meal.BREAKFAST, 700.0);
    }

    @Test
    void minusRemovesTheNewestAndCountsDownOlderDoubleEntries() {
        Dish dish = felixCappuccino();
        // Frueher von Hand: zwei auf einmal, als Snack.
        service.addEntry(ME, new NewEntryRequest(TODAY, dish.id(), null, 246.0, Meal.SNACK));
        service.addCappuccino(ME, TODAY, null);
        assertThat(service.day(ME, TODAY).cappuccino().count()).isEqualTo(3);

        DaySummary afterOne = service.removeCappuccino(ME, TODAY);
        assertThat(afterOne.cappuccino().count()).isEqualTo(2);
        assertThat(afterOne.entries()).hasSize(1);

        DaySummary afterTwo = service.removeCappuccino(ME, TODAY);
        assertThat(afterTwo.cappuccino().count()).isEqualTo(1);
        assertThat(afterTwo.entries()).singleElement().satisfies(e -> assertThat(e.grams()).isEqualTo(123.0));

        DaySummary empty = service.removeCappuccino(ME, TODAY);
        assertThat(empty.cappuccino().count()).isZero();
        assertThatThrownBy(() -> service.removeCappuccino(ME, TODAY)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void withoutADishTheFirstPlusCreatesOneWithTheDefaultValues() {
        CappuccinoDay none = service.day(ME, TODAY).cappuccino();
        assertThat(none.dishId()).isNull();
        assertThat(none.count()).isZero();
        assertThat(none.perCup().kcal()).isEqualTo(105.0);
        assertThat(none.perCup().proteinG()).isEqualTo(2.0);

        DaySummary day = service.addCappuccino(ME, TODAY, null);

        assertThat(day.cappuccino().dishId()).isNotNull();
        assertThat(day.cappuccino().count()).isEqualTo(1);
        assertThat(day.consumed().kcal()).isEqualTo(105.0);
        assertThat(service.dishes(ME)).singleElement().satisfies(d -> assertThat(d.markedVegan()).isTrue());
    }

    @Test
    void aResentPlusWithTheSameIdCountsOnce() {
        felixCappuccino();
        String id = "0b6f3c1e-1f7e-4a59-9a43-0d1c2b3a4f5e";
        service.addCappuccino(ME, TODAY, id);
        DaySummary again = service.addCappuccino(ME, TODAY, id);
        assertThat(again.cappuccino().count()).isEqualTo(1);
    }

    @Test
    void theDishIsFoundByNameEvenWhenMisspelled() {
        assertThat(CappuccinoCounter.isCappuccino("Cappuchino (zuhause)")).isTrue();
        assertThat(CappuccinoCounter.isCappuccino("Capuccino")).isTrue();
        assertThat(CappuccinoCounter.isCappuccino("Cappuccino")).isTrue();
        assertThat(CappuccinoCounter.isCappuccino("Milchkaffee")).isFalse();
    }

    @Test
    void cupsOnOtherDaysDoNotCount() {
        felixCappuccino();
        service.addCappuccino(ME, TODAY.minusDays(1), null);
        assertThat(service.day(ME, TODAY).cappuccino().count()).isZero();
        assertThat(service.day(ME, TODAY.minusDays(1)).cappuccino().count()).isEqualTo(1);
    }
}

package com.fherrmann.food.service;

import com.fherrmann.food.dto.DishRequest;
import com.fherrmann.food.dto.EntryLine;
import com.fherrmann.food.dto.FrequentDish;
import com.fherrmann.food.dto.NewEntryRequest;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Was der Weight Tracker fuer den Chat liest (Vertrag §11.4): die Eintraege einer Spanne
 * als Zeilen und die haeufigsten Gerichte.
 */
class ChatReadsTest {

    private static final String ME = "felix";
    private static final String TORBEN = "torben";
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);

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
        service = new FoodService(repository, new NoExtractor(), clock);
    }

    private static DishRequest dishA() {
        return new DishRequest("Gericht A", 80.0, 8.0, 6.0, 1.3, 300.0);
    }

    private static DishRequest dishB() {
        return new DishRequest("Gericht B", 372.0, 13.5, 58.7, 7.0, 60.0);
    }

    private void eat(String user, LocalDate date, DishRequest dish, double grams, Meal meal) {
        service.addEntry(user, new NewEntryRequest(date, null, dish, grams, meal));
    }

    // --- /entries ---------------------------------------------------------------------

    /** Je Eintrag eine Zeile mit seinem Beitrag zum Tag - nach Tag, darin in der Reihenfolge des Eintragens. */
    @Test
    void entriesComeAsLinesWithTheirOwnTotals() throws Exception {
        eat(ME, TODAY, dishB(), 60.0, Meal.BREAKFAST);
        eat(ME, TODAY.minusDays(1), dishA(), 300.0, Meal.LUNCH);
        eat(ME, TODAY, dishA(), 150.0, Meal.SNACK);
        eat(ME, TODAY.minusDays(2), dishA(), 300.0, Meal.DINNER);

        List<EntryLine> lines = service.entryLines(ME, TODAY.minusDays(1), TODAY);

        assertThat(lines).containsExactly(
                new EntryLine(TODAY.minusDays(1), Meal.LUNCH, "Gericht A", 300.0, 240.0, 24.0, 18.0, 3.9),
                new EntryLine(TODAY, Meal.BREAKFAST, "Gericht B", 60.0, 223.2, 8.1, 35.2, 4.2),
                new EntryLine(TODAY, Meal.SNACK, "Gericht A", 150.0, 120.0, 12.0, 9.0, 2.0));
        assertThat(new ObjectMapper().writeValueAsString(lines.get(0))).isEqualTo(
                "{\"date\":\"2026-11-09\",\"meal\":\"LUNCH\",\"name\":\"Gericht A\",\"grams\":300.0,"
                        + "\"kcal\":240.0,\"proteinG\":24.0,\"carbsG\":18.0,\"fatG\":3.9}");
    }

    @Test
    void entriesAreReadPerPerson() {
        eat(ME, TODAY, dishA(), 300.0, Meal.LUNCH);
        eat(TORBEN, TODAY, dishB(), 60.0, Meal.BREAKFAST);

        assertThat(service.entryLines(TORBEN, TODAY, TODAY)).extracting(EntryLine::name).containsExactly("Gericht B");
        assertThat(service.entryLines(ME, TODAY.minusDays(30), TODAY)).extracting(EntryLine::name)
                .containsExactly("Gericht A");
        assertThat(service.entryLines(ME, TODAY.minusDays(30), TODAY.minusDays(1))).isEmpty();
    }

    /** Ein Eintrag aus der Zeit vor den Mahlzeiten hat keine - die Zeile sagt das, statt zu raten. */
    @Test
    void anEntryWithoutAMealKeepsItsGap() throws Exception {
        Files.writeString(tempDir.resolve("food.json"), """
                {
                  "targets" : { "kcal" : 2300.0, "proteinG" : 200.0, "carbsG" : 235.5, "fatG" : 62.0 },
                  "dishes" : [ ],
                  "entries" : [ {
                    "id" : "e-1", "date" : "2026-11-09", "dishId" : null, "name" : "Gericht A", "grams" : 100.0,
                    "per100g" : { "kcal" : 80.0, "proteinG" : 8.0, "carbsG" : 6.0, "fatG" : 1.3 },
                    "createdAt" : "2026-11-09T08:00:00Z"
                  } ]
                }
                """);

        assertThat(service.entryLines(ME, TODAY.minusDays(1), TODAY)).containsExactly(
                new EntryLine(TODAY.minusDays(1), null, "Gericht A", 100.0, 80.0, 8.0, 6.0, 1.3));
    }

    /** Hoechstens 31 Tage, beide Raender eingeschlossen. */
    @Test
    void theEntryRangeIsLimited() {
        assertThat(service.entryLines(ME, TODAY.minusDays(30), TODAY)).isEmpty();
        assertThatThrownBy(() -> service.entryLines(ME, TODAY.minusDays(31), TODAY))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("at most 31 days");
        assertThatThrownBy(() -> service.entryLines(ME, TODAY, TODAY.minusDays(1)))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.entryLines(ME, null, TODAY))
                .isInstanceOf(ResponseStatusException.class);
    }

    // --- /frequent-dishes ---------------------------------------------------------------

    /** Die haeufigsten zuerst, bei Gleichstand das zuletzt gegessene; Menge als Mittel, Werte je 100 g. */
    @Test
    void frequentDishesAreCountedFromTheEntries() throws Exception {
        eat(ME, TODAY.minusDays(3), dishA(), 300.0, Meal.LUNCH);
        eat(ME, TODAY.minusDays(1), dishA(), 250.0, Meal.LUNCH);
        eat(ME, TODAY, dishA(), 201.0, Meal.SNACK);
        eat(ME, TODAY.minusDays(5), dishB(), 60.0, Meal.BREAKFAST);
        eat(ME, TODAY.minusDays(2), new DishRequest("Gericht C", 50.0, 1.0, 10.0, 0.5, null), 120.0, Meal.SNACK);

        List<FrequentDish> frequent = service.frequentDishes(ME, 90, 30);

        assertThat(frequent).containsExactly(
                new FrequentDish("Gericht A", 3, TODAY, 250.0, 80.0, 8.0, 6.0, 1.3),
                new FrequentDish("Gericht C", 1, TODAY.minusDays(2), 120.0, 50.0, 1.0, 10.0, 0.5),
                new FrequentDish("Gericht B", 1, TODAY.minusDays(5), 60.0, 372.0, 13.5, 58.7, 7.0));
        assertThat(new ObjectMapper().writeValueAsString(frequent.get(0))).isEqualTo(
                "{\"name\":\"Gericht A\",\"count\":3,\"lastUsedOn\":\"2026-11-10\",\"avgGrams\":250.0,"
                        + "\"kcalPer100g\":80.0,\"proteinPer100g\":8.0,\"carbsPer100g\":6.0,\"fatPer100g\":1.3}");
    }

    /** Das Fenster endet heute und schliesst heute ein; "limit" schneidet hinten ab. */
    @Test
    void theWindowAndTheLimitApply() {
        eat(ME, TODAY.minusDays(6), dishB(), 60.0, Meal.BREAKFAST);
        eat(ME, TODAY.minusDays(7), dishB(), 60.0, Meal.BREAKFAST);
        eat(ME, TODAY, dishA(), 300.0, Meal.LUNCH);
        // Vorerfasst fuer morgen: das ist ein Plan, noch nichts Gegessenes.
        eat(ME, TODAY.plusDays(1), dishA(), 300.0, Meal.LUNCH);

        assertThat(service.frequentDishes(ME, 7, 30)).containsExactly(
                new FrequentDish("Gericht A", 1, TODAY, 300.0, 80.0, 8.0, 6.0, 1.3),
                new FrequentDish("Gericht B", 1, TODAY.minusDays(6), 60.0, 372.0, 13.5, 58.7, 7.0));
        assertThat(service.frequentDishes(ME, 8, 1)).extracting(FrequentDish::name).containsExactly("Gericht B");
        assertThat(service.frequentDishes(TORBEN, 90, 30)).isEmpty();
    }

    /**
     * Gezaehlt wird ueber den Namen des Eintrags ohne Gross/Klein - auch wenn das Gericht
     * inzwischen korrigiert oder aus der Liste geloescht wurde. Name und Werte stammen vom
     * juengsten Eintrag.
     */
    @Test
    void theLatestEntryNamesTheDishAndItsValues() {
        eat(ME, TODAY.minusDays(4), dishA(), 300.0, Meal.LUNCH);
        Dish stored = service.dishes(ME).get(0);
        service.updateDish(ME, stored.id(), new DishRequest("GERICHT A", 90.0, 9.0, 6.0, 1.5, 300.0));
        eat(ME, TODAY.minusDays(1), new DishRequest("GERICHT A", 90.0, 9.0, 6.0, 1.5, 300.0), 200.0, Meal.DINNER);
        service.deleteDish(ME, stored.id());

        assertThat(service.frequentDishes(ME, 90, 30)).containsExactly(
                new FrequentDish("GERICHT A", 2, TODAY.minusDays(1), 250.0, 90.0, 9.0, 6.0, 1.5));
    }

    @Test
    void daysAndLimitAreChecked() {
        assertThatThrownBy(() -> service.frequentDishes(ME, 0, 30))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("days must be between");
        assertThatThrownBy(() -> service.frequentDishes(ME, 731, 30))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.frequentDishes(ME, 90, 0))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("limit must be between");
        assertThatThrownBy(() -> service.frequentDishes(ME, 90, 101))
                .isInstanceOf(ResponseStatusException.class);
    }
}

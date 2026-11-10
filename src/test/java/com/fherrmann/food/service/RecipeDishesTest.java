package com.fherrmann.food.service;

import com.fherrmann.food.dto.DaySummary;
import com.fherrmann.food.dto.DishRequest;
import com.fherrmann.food.dto.NewEntryRequest;
import com.fherrmann.food.model.Dish;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ein Rezept der Einkaufsliste als Gericht im Tagebuch (Vertrag §11.3): anlegen, auf den
 * neuen Stand bringen, nichts tun - und dabei nichts anfassen, was der Person gehoert.
 */
class RecipeDishesTest {

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
        service = new FoodService(repository, new NoExtractor(), clock, new DetailedNutrition(TORBEN),
                new MicronutrientTracking(TORBEN));
    }

    /** Je 100 g, wie die Einkaufsliste es aus Portion und Gramm rechnet. */
    private static DishRequest recipe(String name, double kcal, Double portionG) {
        return new DishRequest(name, kcal, 5.7, 18.6, 5.7, portionG);
    }

    @Test
    void anUnknownRecipeBecomesANewDish() {
        assertThat(service.putRecipeDish(ME, recipe("Rezept: Gericht A", 152.4, 420.0))).isEqualTo("created");

        assertThat(service.dishes(ME)).singleElement().satisfies(dish -> {
            assertThat(dish.name()).isEqualTo("Rezept: Gericht A");
            assertThat(dish.per100g()).isEqualTo(new Nutrients(152.4, 5.7, 18.6, 5.7));
            assertThat(dish.portionG()).isEqualTo(420.0);
            assertThat(dish.lastUsedOn()).isNull();
            assertThat(dish.vegan()).isNull();
        });
        // Nur bei dieser Person.
        assertThat(service.dishes(TORBEN)).isEmpty();
    }

    /** Dasselbe noch einmal - auch mit anderer Schreibweise des Namens - schreibt nichts. */
    @Test
    void theSameValuesAgainChangeNothing() throws Exception {
        service.putRecipeDish(ME, recipe("Rezept: Gericht A", 152.4, 420.0));
        String before = Files.readString(tempDir.resolve("food.json"));
        long written = Files.getLastModifiedTime(tempDir.resolve("food.json")).toMillis();

        assertThat(service.putRecipeDish(ME, recipe("rezept: gericht a", 152.4, 420.0))).isEqualTo("unchanged");
        // Rundungsrauschen aus der Umrechnung je Portion ist kein neuer Stand.
        assertThat(service.putRecipeDish(ME, recipe("Rezept: Gericht A", 152.4000001, 420.0))).isEqualTo("unchanged");

        assertThat(Files.readString(tempDir.resolve("food.json"))).isEqualTo(before);
        assertThat(Files.getLastModifiedTime(tempDir.resolve("food.json")).toMillis()).isEqualTo(written);
    }

    /** Neue Werte ersetzen die alten - id, letzter Gebrauch und das Kennzeichen vegan bleiben. */
    @Test
    void changedValuesUpdateTheDishButKeepWhatBelongsToThePerson() {
        service.putRecipeDish(ME, recipe("Rezept: Gericht A", 152.4, 420.0));
        Dish created = service.dishes(ME).get(0);
        service.addEntry(ME, new NewEntryRequest(TODAY, created.id(), null, 420.0, null));
        service.updateDish(ME, created.id(), new DishRequest("Rezept: Gericht A", 152.4, 5.7, 18.6, 5.7, 420.0,
                null, null, null, null, null, Boolean.FALSE));

        assertThat(service.putRecipeDish(ME, recipe("Rezept: Gericht A", 160.0, 450.0))).isEqualTo("updated");

        assertThat(service.dishes(ME)).singleElement().satisfies(dish -> {
            assertThat(dish.id()).isEqualTo(created.id());
            assertThat(dish.per100g().kcal()).isEqualTo(160.0);
            assertThat(dish.portionG()).isEqualTo(450.0);
            assertThat(dish.lastUsedOn()).isEqualTo(TODAY);
            assertThat(dish.vegan()).isFalse();
        });
        // Was schon gegessen wurde, behaelt die Werte von damals.
        DaySummary day = service.day(ME, TODAY);
        assertThat(day.entries()).singleElement()
                .satisfies(entry -> assertThat(entry.per100g().kcal()).isEqualTo(152.4));
    }

    /** Eine geaenderte oder entfallene Portion ist auch ein neuer Stand. */
    @Test
    void aChangedPortionIsAnUpdate() {
        service.putRecipeDish(ME, recipe("Rezept: Gericht A", 152.4, 420.0));
        assertThat(service.putRecipeDish(ME, recipe("Rezept: Gericht A", 152.4, 400.0))).isEqualTo("updated");
        assertThat(service.putRecipeDish(ME, recipe("Rezept: Gericht A", 152.4, null))).isEqualTo("updated");
        assertThat(service.dishes(ME).get(0).portionG()).isNull();
        assertThat(service.putRecipeDish(ME, recipe("Rezept: Gericht A", 152.4, null))).isEqualTo("unchanged");
    }

    /** Detailwerte und Mikronaehrstoffe, die die Person selbst gepflegt hat, ueberleben das Update. */
    @Test
    void detailsAndMicrosThePersonAddedSurviveAnUpdate() {
        service.putRecipeDish(TORBEN, recipe("Rezept: Gericht A", 152.4, 420.0));
        Dish created = service.dishes(TORBEN).get(0);
        service.updateDish(TORBEN, created.id(), new DishRequest("Rezept: Gericht A", 152.4, 5.7, 18.6, 5.7, 420.0,
                2.1, 3.4, 1.9, 0.8, Map.of("ironMg", 1.2)));

        assertThat(service.putRecipeDish(TORBEN, recipe("Rezept: Gericht A", 170.0, 420.0))).isEqualTo("updated");

        Nutrients after = service.dishes(TORBEN).get(0).per100g();
        assertThat(after.kcal()).isEqualTo(170.0);
        assertThat(after.saturatedFatG()).isEqualTo(2.1);
        assertThat(after.sugarG()).isEqualTo(3.4);
        assertThat(after.fiberG()).isEqualTo(1.9);
        assertThat(after.saltG()).isEqualTo(0.8);
        assertThat(after.micros()).containsExactly(Map.entry("ironMg", 1.2));
    }

    /** Was die Anfrage ueber die sechs Felder hinaus mitbringt, zaehlt nicht - auch nicht "vegan". */
    @Test
    void anythingBeyondNameValuesAndPortionIsIgnored() {
        service.putRecipeDish(TORBEN, new DishRequest("Rezept: Gericht A", 152.4, 5.7, 18.6, 5.7, 420.0,
                9.0, 9.0, 9.0, 9.0, Map.of("ironMg", 9.0), Boolean.TRUE));

        Dish dish = service.dishes(TORBEN).get(0);
        assertThat(dish.vegan()).isNull();
        assertThat(dish.per100g().hasDetails()).isFalse();
        assertThat(dish.per100g().hasMicros()).isFalse();
    }

    /**
     * Im veganen Modus bekommt ein neues Rezept kein Kennzeichen: der Dienst weiss nicht,
     * was drin ist. Es steht in der Liste, laesst sich aber erst eintragen, wenn die
     * Person es einordnet - und ein nicht-veganes kommt gar nicht erst als vegan herein.
     */
    @Test
    void inVeganModeANewRecipeStaysUnclassified() {
        service.setVeganMode(ME, true);

        assertThat(service.putRecipeDish(ME, recipe("Rezept: Gericht A", 152.4, 420.0))).isEqualTo("created");

        Dish dish = service.dishes(ME).get(0);
        assertThat(dish.vegan()).isNull();
        assertThatThrownBy(() -> service.addEntry(ME, new NewEntryRequest(TODAY, dish.id(), null, 420.0, null)))
                .isInstanceOf(ResponseStatusException.class);
    }

    /** Ein von Hand gepflegtes Gericht mit anderem Namen bleibt unberuehrt - dafuer ist der Vorsatz da. */
    @Test
    void aHandMadeDishWithoutThePrefixIsLeftAlone() {
        Dish own = service.createDish(ME, new DishRequest("Gericht A", 120.0, 10.0, 10.0, 4.0, 300.0));

        service.putRecipeDish(ME, recipe("Rezept: Gericht A", 152.4, 420.0));

        assertThat(service.dishes(ME)).hasSize(2);
        assertThat(service.dishes(ME)).filteredOn(d -> d.id().equals(own.id())).singleElement()
                .satisfies(d -> assertThat(d.per100g().kcal()).isEqualTo(120.0));
    }

    @Test
    void valuesOutsideTheLimitsOfADishAreRejected() {
        assertThatThrownBy(() -> service.putRecipeDish(ME, recipe("  ", 152.4, 420.0)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("dish name is required");
        assertThatThrownBy(() -> service.putRecipeDish(ME, recipe("x".repeat(121), 152.4, 420.0)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("at most 120");
        assertThatThrownBy(() -> service.putRecipeDish(ME, recipe("Rezept: Gericht A", 1_000.1, 420.0)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("kcal must be at most");
        assertThatThrownBy(() -> service.putRecipeDish(ME,
                new DishRequest("Rezept: Gericht A", 152.4, null, 18.6, 5.7, 420.0)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("proteinG is required");
        assertThatThrownBy(() -> service.putRecipeDish(ME, recipe("Rezept: Gericht A", 152.4, 0.0)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("portionG must be greater than 0");
        assertThatThrownBy(() -> service.putRecipeDish(ME, null))
                .isInstanceOf(ResponseStatusException.class);

        assertThat(service.dishes(ME)).isEmpty();
    }

    /** Der Erhalt und alles andere im Tagebuch ueberleben das Anlegen. */
    @Test
    void theRestOfTheDiaryIsUntouched() {
        service.updateTargets(ME, new com.fherrmann.food.dto.TargetsRequest(2300.0, 195.0, 236.0, 70.0, null, null, 140.0));

        service.putRecipeDish(ME, recipe("Rezept: Gericht A", 152.4, 420.0));
        service.putRecipeDish(ME, recipe("Rezept: Gericht A", 160.0, 420.0));

        assertThat(service.targets(ME).proteinMinG()).isEqualTo(140.0);
        assertThat(service.targets(ME).proteinMinDefault()).isFalse();
    }
}

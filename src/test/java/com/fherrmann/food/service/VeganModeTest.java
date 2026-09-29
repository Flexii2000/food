package com.fherrmann.food.service;

import com.fherrmann.food.dto.DishRequest;
import com.fherrmann.food.dto.NewEntryRequest;
import com.fherrmann.food.dto.QuickCapturePreview;
import com.fherrmann.food.dto.QuickCaptureRequest;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Der vegane Modus im Dienst: das Kennzeichen am Gericht, das einmalige Markieren beim
 * ersten Einschalten, die Regeln beim Eintragen und Anlegen und die Frage an die
 * Schnellerfassung.
 */
class VeganModeTest {

    private static final String TORBEN = "torben";
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 29);

    @TempDir
    Path tempDir;

    private FoodService service;
    private FoodRepository repository;
    private Extractor extractor;

    /** Steht fuer den Agent und merkt sich, ob nach vegan gefragt wurde. */
    private static final class Extractor implements NutritionExtractor {
        ExtractedDish next;
        Boolean askedVegan;

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public ExtractedDish extract(String text, Path photo, Nutrients targets, List<Dish> known,
                                     boolean detailed, boolean micronutrients) {
            return next;
        }

        @Override
        public ExtractedDish extract(String text, Path photo, Nutrients targets, List<Dish> known,
                                     boolean detailed, boolean micronutrients, boolean vegan) {
            askedVegan = vegan;
            return next;
        }
    }

    @BeforeEach
    void setUp() {
        repository = new FoodRepository(tempDir.resolve("food.json").toString(), new ObjectMapper(),
                new UserFiles(new HealthUsers("felix", "torben:0123456789abcdef0123456789abcdef")));
        extractor = new Extractor();
        service = new FoodService(repository, extractor,
                Clock.fixed(TODAY.atStartOfDay(ZoneId.of("UTC")).toInstant(), ZoneId.of("UTC")));
    }

    private static DishRequest dish(String name, Boolean vegan) {
        return new DishRequest(name, 100.0, 5.0, 10.0, 3.0, 200.0, null, null, null, null, null, vegan);
    }

    private Dish stored(String name) {
        return service.dishes(TORBEN).stream().filter(d -> d.name().equals(name)).findFirst().orElseThrow();
    }

    private static ExtractedDish extracted(String name, Boolean vegan, List<String> estimated) {
        return new ExtractedDish(name, 120, 4, 15, 5, 350, 350.0, List.of(), estimated, "geschaetzt",
                Meal.LUNCH, null, null, null, null, null, vegan);
    }

    // MARK: - Kennzeichen

    @Test
    void theFlagIsStoredAndAPutReplacesIt() {
        Dish tofu = service.createDish(TORBEN, dish("Tofu-Curry", true));
        assertThat(tofu.vegan()).isTrue();
        assertThat(service.createDish(TORBEN, dish("Käsespätzle", false)).vegan()).isFalse();
        assertThat(service.createDish(TORBEN, dish("Eintopf", null)).vegan()).isNull();

        // PUT ersetzt das Gericht ganz: ohne Kennzeichen ist es danach unbekannt.
        assertThat(service.updateDish(TORBEN, tofu.id(), dish("Tofu-Curry", null)).vegan()).isNull();
        assertThat(stored("Tofu-Curry").vegan()).isNull();
        assertThat(service.updateDish(TORBEN, tofu.id(), dish("Tofu-Curry", false)).vegan()).isFalse();
    }

    /** Das Eintragen schiebt lastUsedOn nach - das Kennzeichen darf dabei nicht verloren gehen. */
    @Test
    void loggingADishKeepsItsFlag() {
        Dish tofu = service.createDish(TORBEN, dish("Tofu-Curry", true));
        service.addEntry(TORBEN, new NewEntryRequest(TODAY, tofu.id(), null, 300.0, Meal.LUNCH));

        assertThat(stored("Tofu-Curry").vegan()).isTrue();
        assertThat(stored("Tofu-Curry").lastUsedOn()).isEqualTo(TODAY);
    }

    // MARK: - Einschalten

    @Test
    void theFirstSwitchOnMarksEveryUnknownDishAndKeepsFalse() {
        service.createDish(TORBEN, dish("Linsen", null));
        service.createDish(TORBEN, dish("Käsespätzle", false));
        service.createDish(TORBEN, dish("Tofu", true));

        service.setVeganMode(TORBEN, true);

        assertThat(service.veganMode(TORBEN)).isTrue();
        assertThat(stored("Linsen").vegan()).isTrue();
        assertThat(stored("Käsespätzle").vegan()).isFalse();
        assertThat(stored("Tofu").vegan()).isTrue();
    }

    @Test
    void theSecondSwitchOnMarksNothing() {
        service.setVeganMode(TORBEN, true);
        service.setVeganMode(TORBEN, false);
        assertThat(service.veganMode(TORBEN)).isFalse();

        service.createDish(TORBEN, dish("Neu ohne Einordnung", null));
        service.setVeganMode(TORBEN, true);

        assertThat(service.veganMode(TORBEN)).isTrue();
        assertThat(stored("Neu ohne Einordnung").vegan()).isNull();
    }

    /** Aus, ohne je an gewesen zu sein, aendert nichts - sonst waere das erste Einschalten keins mehr. */
    @Test
    void switchingOffWhatWasNeverOnChangesNothing() throws Exception {
        service.createDish(TORBEN, dish("Linsen", null));
        Path file = tempDir.resolve("users").resolve("torben").resolve("food.json");
        String before = Files.readString(file);

        service.setVeganMode(TORBEN, false);

        assertThat(Files.readString(file)).isEqualTo(before).doesNotContain("vegan");
        service.setVeganMode(TORBEN, true);
        assertThat(stored("Linsen").vegan()).isTrue();
    }

    @Test
    void otherChangesKeepTheMode() {
        service.setVeganMode(TORBEN, true);
        service.updateTargets(TORBEN, new TargetsRequest(2000.0, 120.0, 230.0, 65.0, null));
        Dish tofu = service.createDish(TORBEN, dish("Tofu", true));
        service.addEntry(TORBEN, new NewEntryRequest(TODAY, tofu.id(), null, 200.0, null));

        assertThat(service.veganMode(TORBEN)).isTrue();
    }

    // MARK: - Regeln im Modus

    @Test
    void inTheModeOnlyVeganDishesCanBeLogged() {
        Dish tofu = service.createDish(TORBEN, dish("Tofu", true));
        service.setVeganMode(TORBEN, true);
        Dish cheese = service.updateDish(TORBEN, service.createDish(TORBEN, dish("Käse", true)).id(),
                dish("Käse", false));
        Dish unknown = service.updateDish(TORBEN, service.createDish(TORBEN, dish("Unklar", true)).id(),
                dish("Unklar", null));

        assertThat(service.addEntry(TORBEN, new NewEntryRequest(TODAY, tofu.id(), null, 200.0, null)).entries())
                .hasSize(1);
        for (Dish dish : List.of(cheese, unknown)) {
            assertThatThrownBy(() -> service.addEntry(TORBEN, new NewEntryRequest(TODAY, dish.id(), null, 200.0, null)))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("400")
                    .hasMessageContaining("Im veganen Modus lassen sich nur vegane Gerichte eintragen.");
        }
        assertThat(service.day(TORBEN, TODAY).entries()).hasSize(1);
    }

    @Test
    void inTheModeNewDishesAreVeganUnlessSaidOtherwiseAndThenRejected() {
        service.setVeganMode(TORBEN, true);

        assertThat(service.createDish(TORBEN, dish("Hummus", null)).vegan()).isTrue();
        assertThatThrownBy(() -> service.createDish(TORBEN, dish("Butterbrot", false)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Im veganen Modus lassen sich nur vegane Gerichte anlegen.");

        service.addEntry(TORBEN, new NewEntryRequest(TODAY, null, dish("Falafel", null), 150.0, null));
        assertThat(stored("Falafel").vegan()).isTrue();
        assertThatThrownBy(() -> service.addEntry(TORBEN,
                new NewEntryRequest(TODAY, null, dish("Schnitzel", false), 150.0, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Im veganen Modus lassen sich nur vegane Gerichte eintragen.");

        assertThat(service.dishes(TORBEN)).extracting(Dish::name).containsExactlyInAnyOrder("Hummus", "Falafel");
        assertThat(service.day(TORBEN, TODAY).entries()).hasSize(1);
    }

    /** Korrigieren muss gehen: ein Gericht, das doch nicht vegan ist, darf auch im Modus so heissen. */
    @Test
    void inTheModeADishCanBeCorrectedToNotVegan() {
        service.setVeganMode(TORBEN, true);
        Dish pesto = service.createDish(TORBEN, dish("Pesto", null));

        assertThat(service.updateDish(TORBEN, pesto.id(), dish("Pesto", false)).vegan()).isFalse();
    }

    /** Ausserhalb des Modus gilt keine der Regeln. */
    @Test
    void withoutTheModeAnythingGoes() {
        Dish cheese = service.createDish(TORBEN, dish("Käse", false));
        service.addEntry(TORBEN, new NewEntryRequest(TODAY, cheese.id(), null, 50.0, null));
        service.addEntry(TORBEN, new NewEntryRequest(TODAY, null, dish("Omelett", null), 150.0, null));

        assertThat(stored("Omelett").vegan()).isNull();
        assertThat(service.day(TORBEN, TODAY).entries()).hasSize(2);
    }

    // MARK: - Schnellerfassung

    @Test
    void quickCaptureAsksAboutVeganOnlyOnceTheModeWasEverOn() {
        extractor.next = extracted("Linsencurry", true, List.of("kcalPer100g", "vegan"));

        QuickCapturePreview before = service.quickCapture(TORBEN, new QuickCaptureRequest(TODAY, "Linsencurry", null));
        assertThat(extractor.askedVegan).isFalse();
        // Auch wenn der Agent es ungefragt liefert: nicht im Vorschlag.
        assertThat(before.vegan()).isNull();
        assertThat(before.valueSources()).doesNotContainKey("vegan");

        service.setVeganMode(TORBEN, true);
        service.setVeganMode(TORBEN, false);
        QuickCapturePreview after = service.quickCapture(TORBEN, new QuickCaptureRequest(TODAY, "Linsencurry", null));
        assertThat(extractor.askedVegan).isTrue();
        assertThat(after.vegan()).isTrue();
        assertThat(after.valueSources()).containsEntry("vegan", "estimated");
    }

    @Test
    void theProposalCarriesTheAgentsVerdictWithItsSource() {
        service.setVeganMode(TORBEN, true);

        extractor.next = extracted("Käsekuchen", false, List.of());
        QuickCapturePreview read = service.quickCapture(TORBEN, new QuickCaptureRequest(TODAY, "Käsekuchen", null));
        assertThat(read.vegan()).isFalse();
        assertThat(read.valueSources()).containsEntry("vegan", "read");

        // Unklar: kein Kennzeichen, keine Herkunft.
        extractor.next = extracted("Brötchen", null, List.of("vegan"));
        QuickCapturePreview unclear = service.quickCapture(TORBEN, new QuickCaptureRequest(TODAY, "Brötchen", null));
        assertThat(unclear.vegan()).isNull();
        assertThat(unclear.valueSources()).doesNotContainKey("vegan");
    }

    /** Im Modus wird trotzdem vorgeschlagen - erst das Bestaetigen scheitert an der Regel. */
    @Test
    void aNonVeganProposalIsShownButCannotBeConfirmed() {
        service.setVeganMode(TORBEN, true);
        extractor.next = extracted("Käsekuchen", false, List.of("vegan"));

        QuickCapturePreview preview = service.quickCapture(TORBEN, new QuickCaptureRequest(TODAY, "Käsekuchen", null));
        assertThat(preview.vegan()).isFalse();

        assertThatThrownBy(() -> service.addEntry(TORBEN, new NewEntryRequest(TODAY, null,
                dish(preview.name(), preview.vegan()), preview.grams(), preview.meal())))
                .hasMessageContaining("Im veganen Modus lassen sich nur vegane Gerichte eintragen.");
    }

    @Test
    void aKnownDishBringsItsStoredFlag() {
        service.createDish(TORBEN, dish("Käsespätzle", false));
        service.setVeganMode(TORBEN, true);
        extractor.next = extracted("käsespätzle", true, List.of("vegan"));

        QuickCapturePreview preview = service.quickCapture(TORBEN, new QuickCaptureRequest(TODAY, "Käsespätzle", null));

        assertThat(preview.known()).isTrue();
        assertThat(preview.vegan()).isFalse();
        assertThat(preview.valueSources()).containsEntry("vegan", "stored");
    }
}

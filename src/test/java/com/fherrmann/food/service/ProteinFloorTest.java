package com.fherrmann.food.service;

import com.fherrmann.food.dto.DaySummary;
import com.fherrmann.food.dto.DishRequest;
import com.fherrmann.food.dto.NewEntryRequest;
import com.fherrmann.food.dto.TargetsRequest;
import com.fherrmann.food.dto.TargetsView;
import com.fherrmann.food.dto.UpdateEntryRequest;
import com.fherrmann.food.model.Dish;
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
 * Der Eiweiß-Erhalt neben dem Eiweißziel (Vertrag §11.1): Vorgabe, Speichern, Zuruecksetzen,
 * das stille Mitsinken bei aelteren Clients - und dass kein anderer Schreibweg ihn verliert.
 */
class ProteinFloorTest {

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
        service = new FoodService(repository, new NoExtractor(), clock, DetailedNutrition.none(),
                new MicronutrientTracking(TORBEN));
    }

    private static TargetsRequest targets(double proteinG, Double proteinMinG) {
        return new TargetsRequest(2300.0, proteinG, 236.0, 70.0, null, null, proteinMinG);
    }

    /** So schicken es alle Clients, die das Feld nicht kennen. */
    private static TargetsRequest oldClient(double proteinG) {
        return new TargetsRequest(2300.0, proteinG, 236.0, 70.0, null);
    }

    /** Ohne gespeicherten Erhalt gilt 1,6/2,2 des Ziels, auf ganze Gramm - und nichts davon steht in der Datei. */
    @Test
    void withoutAStoredFloorTheDefaultFollowsTheTarget() throws Exception {
        TargetsView fresh = service.targets(ME);
        assertThat(fresh.proteinG()).isEqualTo(200.0);
        assertThat(fresh.proteinMinG()).isEqualTo(145.0);
        assertThat(fresh.proteinMinDefault()).isTrue();

        TargetsView changed = service.updateTargets(ME, oldClient(195.0));
        assertThat(changed.proteinMinG()).isEqualTo(142.0);
        assertThat(changed.proteinMinDefault()).isTrue();
        assertThat(service.day(ME, TODAY).targets()).isEqualTo(changed);
        assertThat(Files.readString(tempDir.resolve("food.json"))).doesNotContain("proteinMin");
    }

    /** Die Antwort ist das bisherige Ziele-JSON mit genau zwei Feldern mehr. */
    @Test
    void theTargetsJsonIsAFlatSupersetOfTheOldOne() throws Exception {
        ObjectMapper json = new ObjectMapper();
        assertThat(json.writeValueAsString(service.targets(ME))).isEqualTo(
                "{\"kcal\":2300.0,\"proteinG\":200.0,\"carbsG\":235.5,\"fatG\":62.0,"
                        + "\"proteinMinG\":145.0,\"proteinMinDefault\":true}");
        assertThat(json.readTree(json.writeValueAsString(service.day(ME, TODAY))).path("targets").toString())
                .isEqualTo(json.writeValueAsString(service.targets(ME)));
        // Wer Mikronaehrstoffe erfasst, behaelt seine Ziele daneben.
        assertThat(service.targets(TORBEN).micros()).isEqualTo(Micronutrient.defaultTargets());
        assertThat(service.targets(TORBEN).proteinMinG()).isEqualTo(145.0);
    }

    @Test
    void aStoredFloorIsReturnedAndWritten() throws Exception {
        TargetsView saved = service.updateTargets(ME, targets(195.0, 140.0));

        assertThat(saved.proteinG()).isEqualTo(195.0);
        assertThat(saved.proteinMinG()).isEqualTo(140.0);
        assertThat(saved.proteinMinDefault()).isFalse();
        assertThat(service.targets(ME)).isEqualTo(saved);
        assertThat(service.day(ME, TODAY).targets()).isEqualTo(saved);
        assertThat(Files.readString(tempDir.resolve("food.json"))).contains("\"proteinMinG\" : 140.0");
        // Je Person: bei Torben gilt weiter die Vorgabe.
        assertThat(service.targets(TORBEN).proteinMinDefault()).isTrue();
    }

    /** Der Erhalt darf dem Ziel gleich sein - dann gibt es kein Gelb, nur Rot und Gruen. */
    @Test
    void theFloorMayEqualTheTarget() {
        assertThat(service.updateTargets(ME, targets(150.0, 150.0)).proteinMinG()).isEqualTo(150.0);
    }

    /** Ein Client, der das Feld nicht kennt, loescht nichts. */
    @Test
    void targetsWithoutAFloorKeepTheStoredOne() {
        service.updateTargets(ME, targets(195.0, 140.0));

        TargetsView after = service.updateTargets(ME, oldClient(200.0));

        assertThat(after.proteinG()).isEqualTo(200.0);
        assertThat(after.proteinMinG()).isEqualTo(140.0);
        assertThat(after.proteinMinDefault()).isFalse();
    }

    /** Senkt eine aeltere App das Eiweißziel unter den Erhalt, sinkt der still mit - und bleibt gespeichert. */
    @Test
    void anOlderClientLoweringTheTargetPullsTheFloorDown() {
        service.updateTargets(ME, targets(195.0, 140.0));

        TargetsView lowered = service.updateTargets(ME, oldClient(120.0));
        assertThat(lowered.proteinMinG()).isEqualTo(120.0);
        assertThat(lowered.proteinMinDefault()).isFalse();

        // Hoch geht er von selbst nicht wieder: das waere geraten.
        assertThat(service.updateTargets(ME, oldClient(195.0)).proteinMinG()).isEqualTo(120.0);
    }

    /** Ein Eiweißziel von 0 laesst keinen Erhalt uebrig - dann gilt wieder die Vorgabe (0). */
    @Test
    void aTargetOfZeroDropsTheStoredFloor() throws Exception {
        service.updateTargets(ME, targets(195.0, 140.0));

        TargetsView none = service.updateTargets(ME, oldClient(0.0));

        assertThat(none.proteinMinG()).isZero();
        assertThat(none.proteinMinDefault()).isTrue();
        assertThat(Files.readString(tempDir.resolve("food.json"))).doesNotContain("proteinMin");
    }

    @Test
    void zeroResetsTheFloorToTheDefault() throws Exception {
        service.updateTargets(ME, targets(195.0, 140.0));

        TargetsView reset = service.updateTargets(ME, targets(195.0, 0.0));

        assertThat(reset.proteinMinG()).isEqualTo(142.0);
        assertThat(reset.proteinMinDefault()).isTrue();
        assertThat(Files.readString(tempDir.resolve("food.json"))).doesNotContain("proteinMin");
    }

    @Test
    void aFloorAboveTheTargetOrBelowZeroIsRejected() {
        service.updateTargets(ME, targets(195.0, 140.0));

        assertThatThrownBy(() -> service.updateTargets(ME, targets(195.0, 195.1)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Eiweißziel");
        assertThatThrownBy(() -> service.updateTargets(ME, targets(195.0, -1.0)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("proteinMinG");
        assertThatThrownBy(() -> service.updateTargets(ME, targets(195.0, Double.NaN)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("proteinMinG");
        // Gegen das NEUE Ziel geprueft, nicht gegen das alte.
        assertThatThrownBy(() -> service.updateTargets(ME, targets(130.0, 140.0)))
                .isInstanceOf(ResponseStatusException.class);

        // Nichts davon wurde gespeichert.
        assertThat(service.targets(ME).proteinG()).isEqualTo(195.0);
        assertThat(service.targets(ME).proteinMinG()).isEqualTo(140.0);
    }

    /** Jeder andere Schreibweg speichert das ganze Tagebuch - der Erhalt muss mit. */
    @Test
    void theFloorSurvivesEveryOtherChange() {
        service.updateTargets(TORBEN, new TargetsRequest(2800.0, 180.0, 300.0, 90.0, null,
                Map.of("zincMg", 11.0), 130.0));
        Dish dish = service.createDish(TORBEN, new DishRequest("Gericht A", 80.0, 8.0, 6.0, 1.3, 300.0));
        DaySummary day = service.addEntry(TORBEN, new NewEntryRequest(TODAY, dish.id(), null, 300.0, null));
        service.updateEntry(TORBEN, day.entries().get(0).id(), new UpdateEntryRequest(250.0, null, null));
        service.updateDish(TORBEN, dish.id(), new DishRequest("Gericht A", 90.0, 8.0, 6.0, 1.3, 300.0));
        service.setVeganMode(TORBEN, true);
        service.setVeganMode(TORBEN, false);
        service.deleteEntry(TORBEN, day.entries().get(0).id());
        service.deleteDish(TORBEN, dish.id());

        TargetsView after = service.targets(TORBEN);
        assertThat(after.proteinMinG()).isEqualTo(130.0);
        assertThat(after.proteinMinDefault()).isFalse();
        // Und umgekehrt verliert das Speichern des Erhalts nichts anderes.
        assertThat(after.micros()).containsExactly(Map.entry("zincMg", 11.0));
        assertThat(service.veganMode(TORBEN)).isFalse();
    }

    /** Eine Datei aus der Zeit davor - ohne das Feld - laedt wie immer und bekommt die Vorgabe. */
    @Test
    void aFileFromBeforeTheFloorStillLoads() throws Exception {
        Files.writeString(tempDir.resolve("food.json"), """
                {
                  "targets" : { "kcal" : 2300.0, "proteinG" : 175.0, "carbsG" : 236.0, "fatG" : 70.0 },
                  "mealShares" : { "BREAKFAST" : 0.25, "LUNCH" : 0.35, "DINNER" : 0.3, "SNACK" : 0.1 },
                  "dishes" : [ ],
                  "entries" : [ ]
                }
                """);

        TargetsView loaded = service.targets(ME);

        assertThat(loaded.proteinG()).isEqualTo(175.0);
        assertThat(loaded.proteinMinG()).isEqualTo(127.0);
        assertThat(loaded.proteinMinDefault()).isTrue();
    }
}

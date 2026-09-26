package com.fherrmann.food.repository;

import com.fherrmann.food.model.Dish;
import com.fherrmann.food.model.FoodData;
import com.fherrmann.food.model.Nutrients;
import com.fherrmann.food.security.HealthUsers;
import com.fherrmann.food.security.UserFiles;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FoodRepositoryTest {

    /** Die Eigentuemerin - ihre Datei liegt, wo sie immer lag. */
    private static final String ME = "felix";
    private static final UserFiles FILES = new UserFiles(
            new HealthUsers(ME, "torben:0123456789abcdef0123456789abcdef"));

    @TempDir
    Path tempDir;

    private Path dataFile;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        dataFile = tempDir.resolve("food.json");
    }

    private FoodRepository repository() {
        return new FoodRepository(dataFile.toString(), mapper, FILES);
    }

    @Test
    void loadWithoutFileReturnsDefaults() {
        FoodData data = repository().load(ME);
        assertThat(data.targets()).isEqualTo(FoodData.DEFAULT_TARGETS);
        assertThat(data.dishes()).isEmpty();
        assertThat(data.entries()).isEmpty();
    }

    @Test
    void saveCreatesMissingDirectoriesAndRoundTrips() {
        Path nested = tempDir.resolve("sub").resolve("food.json");
        FoodRepository repository = new FoodRepository(nested.toString(), mapper, FILES);
        Dish dish = new Dish("d1", "Skyr", new Nutrients(80, 8, 6, 1.3), 300.0, LocalDate.of(2026, 8, 30));
        repository.save(ME, new FoodData(new Nutrients(2300, 200, 235.5, 62),
                FoodData.DEFAULT_MEAL_SHARES, List.of(dish), List.of()));

        FoodData reloaded = repository.load(ME);
        assertThat(reloaded.targets().kcal()).isEqualTo(2300);
        assertThat(reloaded.dishes()).hasSize(1);
        assertThat(reloaded.dishes().get(0).name()).isEqualTo("Skyr");
        assertThat(reloaded.dishes().get(0).portionG()).isEqualTo(300.0);
        assertThat(reloaded.dishes().get(0).lastUsedOn()).isEqualTo(LocalDate.of(2026, 8, 30));
    }

    /** Eine zweite Person landet in ihrem eigenen Ordner und startet mit den Vorgaben. */
    @Test
    void aSecondPersonGetsHerOwnFileNextToTheOwners() throws Exception {
        FoodRepository repository = repository();
        repository.save(ME, new FoodData(new Nutrients(2300, 200, 235.5, 62),
                FoodData.DEFAULT_MEAL_SHARES, List.of(), List.of()));
        String owners = java.nio.file.Files.readString(dataFile);

        assertThat(repository.load("torben").targets()).isEqualTo(FoodData.DEFAULT_TARGETS);
        repository.save("torben", new FoodData(new Nutrients(2800, 180, 300, 90),
                FoodData.DEFAULT_MEAL_SHARES, List.of(), List.of()));

        assertThat(tempDir.resolve("users/torben/food.json")).exists();
        assertThat(repository.load("torben").targets().kcal()).isEqualTo(2800);
        assertThat(java.nio.file.Files.readString(dataFile)).isEqualTo(owners);
    }
}

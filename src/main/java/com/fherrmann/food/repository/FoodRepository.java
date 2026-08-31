package com.fherrmann.food.repository;

import com.fherrmann.food.model.FoodData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads and writes the single source of truth ({@code food.json}).
 *
 * <p>All persistence lives here so the rules around dishes and entries stay free of
 * I/O and easy to unit-test. Unlike the weight tracker's data file this one is allowed
 * not to exist yet: a fresh install starts with the default targets and an empty
 * library rather than failing to boot.
 */
@Repository
public class FoodRepository {

    private final Path dataFile;
    private final ObjectMapper objectMapper;

    public FoodRepository(
            @Value("${food.data-file:data/food.json}") String dataFile,
            ObjectMapper objectMapper) {
        this.dataFile = Path.of(dataFile);
        this.objectMapper = objectMapper;
    }

    /** Loads the stored data, or a default-initialised one if the file does not exist yet. */
    public synchronized FoodData load() {
        if (!Files.exists(dataFile)) {
            return FoodData.empty();
        }
        try {
            return objectMapper.readValue(Files.readAllBytes(dataFile), FoodData.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read food data file: " + dataFile, e);
        }
    }

    /** Persists the given state, creating the data directory if necessary. */
    public synchronized void save(FoodData data) {
        try {
            if (dataFile.getParent() != null) {
                Files.createDirectories(dataFile.getParent());
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(dataFile.toFile(), data);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write food data file: " + dataFile, e);
        }
    }
}

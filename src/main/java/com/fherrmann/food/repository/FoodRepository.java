package com.fherrmann.food.repository;

import com.fherrmann.food.model.FoodData;
import com.fherrmann.food.security.UserFiles;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads and writes the single source of truth ({@code food.json}) - one per person, see
 * {@link UserFiles}.
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
    private final UserFiles userFiles;

    public FoodRepository(
            @Value("${food.data-file:data/food.json}") String dataFile,
            ObjectMapper objectMapper,
            UserFiles userFiles) {
        this.dataFile = Path.of(dataFile);
        this.objectMapper = objectMapper;
        this.userFiles = userFiles;
    }

    /**
     * Loads the stored data, or a default-initialised one if the file does not exist yet -
     * which is also how a new person starts: default targets, empty library.
     */
    public synchronized FoodData load(String user) {
        Path dataFile = userFiles.resolve(user, this.dataFile);
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
    public synchronized void save(String user, FoodData data) {
        Path dataFile = userFiles.resolve(user, this.dataFile);
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

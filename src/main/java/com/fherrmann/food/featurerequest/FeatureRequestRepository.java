package com.fherrmann.food.featurerequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Liest und schreibt {@code feature-requests.json} - eine Datei fuer alle Personen,
 * jede Anfrage mit ihrem {@code author}.
 *
 * <p>Bewusst nicht je Person wie das Tagebuch ({@code UserFiles}): Felix sieht alle
 * Anfragen, und der Nachlauf geht ueber alle. Eine Datei je Person hiesse, fuer
 * jede Liste der Eigentuemerin alle Ordner abzulaufen.
 */
@Repository
public class FeatureRequestRepository {

    /** Die Form der Datei. Ein Objekt statt einer blossen Liste, damit spaeter Felder dazukommen koennen. */
    record Stored(List<FeatureRequest> requests) {
        Stored {
            requests = requests == null ? List.of() : List.copyOf(requests);
        }
    }

    private final Path file;
    private final ObjectMapper objectMapper;

    public FeatureRequestRepository(
            @Value("${food.feature-requests.data-file:data/feature-requests.json}") String file,
            ObjectMapper objectMapper) {
        this.file = Path.of(file);
        this.objectMapper = objectMapper;
    }

    /** Alle Anfragen in der Reihenfolge, in der sie kamen. */
    public synchronized List<FeatureRequest> all() {
        return load();
    }

    public synchronized Optional<FeatureRequest> find(String id) {
        return load().stream().filter(r -> r.id().equals(id)).findFirst();
    }

    public synchronized void add(FeatureRequest request) {
        List<FeatureRequest> requests = new ArrayList<>(load());
        requests.add(request);
        save(requests);
    }

    /**
     * Aendert eine Anfrage an Ort und Stelle. Lesen, Aendern und Schreiben unter
     * einer Sperre: der Nachlauf und eine neue Anfrage duerfen sich nicht
     * gegenseitig eine Aenderung ueberschreiben.
     *
     * @return die geaenderte Anfrage, oder leer, wenn es sie nicht gibt
     */
    public synchronized Optional<FeatureRequest> update(String id, UnaryOperator<FeatureRequest> change) {
        List<FeatureRequest> requests = new ArrayList<>(load());
        for (int i = 0; i < requests.size(); i++) {
            if (requests.get(i).id().equals(id)) {
                FeatureRequest changed = change.apply(requests.get(i));
                if (!changed.equals(requests.get(i))) {
                    requests.set(i, changed);
                    save(requests);
                }
                return Optional.of(changed);
            }
        }
        return Optional.empty();
    }

    private List<FeatureRequest> load() {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(Files.readAllBytes(file), Stored.class).requests();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read feature requests: " + file, e);
        }
    }

    /** Erst daneben schreiben, dann umbenennen - eine halbe Datei waeren alle Anfragen. */
    private void save(List<FeatureRequest> requests) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), new Stored(requests));
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write feature requests: " + file, e);
        }
    }
}

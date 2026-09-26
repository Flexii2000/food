package com.fherrmann.food.service;

import com.fherrmann.food.security.HealthUsers;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Eine Auswahl von Personen aus der Umgebung: {@code felix,torben}, {@code *} fuer alle
 * oder leer fuer eine Vorgabe. Dieselbe Form fuer jede Einstellung, die je Person gilt
 * (Schnellerfassung, Detailwerte).
 */
final class PeopleSelection {

    private final boolean everyone;
    private final Set<String> people;

    private PeopleSelection(boolean everyone, Set<String> people) {
        this.everyone = everyone;
        this.people = people;
    }

    /**
     * @param setting  der Wert aus der Umgebung
     * @param fallback wer gilt, wenn nichts eingestellt ist (leer heisst niemand)
     * @param property Name der Einstellung, fuer die Fehlermeldung
     */
    static PeopleSelection parse(String setting, Set<String> fallback, String property) {
        String value = setting == null ? "" : setting.trim();
        if (value.equals("*")) {
            return new PeopleSelection(true, Set.of());
        }
        if (value.isEmpty()) {
            return new PeopleSelection(false, Set.copyOf(fallback));
        }
        Set<String> people = new LinkedHashSet<>();
        for (String part : value.split(",")) {
            String name = part.trim();
            if (name.isEmpty()) {
                continue;
            }
            if (!HealthUsers.isValidName(name)) {
                throw new IllegalStateException(property + ": ungueltiger Name '" + name + "'");
            }
            people.add(name);
        }
        return new PeopleSelection(false, people);
    }

    boolean contains(String user) {
        return everyone || people.contains(user);
    }
}

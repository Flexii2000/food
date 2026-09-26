package com.fherrmann.food.service;

import com.fherrmann.food.security.HealthUsers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Wer die Schnellerfassung benutzen darf.
 *
 * <p>Jede Auswertung ist eine Claude-Code-Session unter Felix' Login und zaehlt auf
 * sein Kontingent - deshalb ist sie pro Person schaltbar statt einfach fuer jeden
 * mit Token an. Form in der Umgebung: {@code FOOD_QUICK_CAPTURE=felix,torben};
 * {@code *} heisst alle. Leer heisst: nur die Eigentuemerin, also genau das
 * Verhalten von vorher.
 */
@Component
public class QuickCaptureAccess {

    private final boolean everyone;
    private final Set<String> people = new LinkedHashSet<>();

    public QuickCaptureAccess(HealthUsers users, @Value("${food.agent.people:}") String configured) {
        String value = configured == null ? "" : configured.trim();
        this.everyone = value.equals("*");
        if (value.isEmpty()) {
            people.add(users.owner());
        } else if (!everyone) {
            for (String part : value.split(",")) {
                String name = part.trim();
                if (name.isEmpty()) {
                    continue;
                }
                if (!HealthUsers.isValidName(name)) {
                    throw new IllegalStateException("food.agent.people: ungueltiger Name '" + name + "'");
                }
                people.add(name);
            }
        }
    }

    public boolean allows(String user) {
        return everyone || people.contains(user);
    }
}

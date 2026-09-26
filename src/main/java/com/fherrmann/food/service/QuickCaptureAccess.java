package com.fherrmann.food.service;

import com.fherrmann.food.security.HealthUsers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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

    private final PeopleSelection people;

    public QuickCaptureAccess(HealthUsers users, @Value("${food.agent.people:}") String configured) {
        this.people = PeopleSelection.parse(configured, Set.of(users.owner()), "food.agent.people");
    }

    public boolean allows(String user) {
        return people.contains(user);
    }
}

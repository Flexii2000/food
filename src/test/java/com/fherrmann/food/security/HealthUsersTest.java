package com.fherrmann.food.security;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HealthUsersTest {

    private static final String TORBEN = "0123456789abcdef0123456789abcdef";
    private static final String JOANA = "fedcba9876543210fedcba9876543210";

    @Test
    void withoutTokensOnlyTheOwnerExists() {
        HealthUsers users = new HealthUsers("felix", "");
        assertThat(users.names()).containsExactly("felix");
        assertThat(users.nameFor(TORBEN)).isEmpty();
        assertThat(users.isOwner("felix")).isTrue();
    }

    @Test
    void mapsEachTokenToItsPerson() {
        HealthUsers users = new HealthUsers("felix", "torben:" + TORBEN + ", joana:" + JOANA);
        assertThat(users.nameFor(TORBEN)).contains("torben");
        assertThat(users.nameFor(JOANA)).contains("joana");
        assertThat(users.nameFor("nope")).isEmpty();
        assertThat(users.nameFor(null)).isEmpty();
        assertThat(users.nameFor("")).isEmpty();
        assertThat(users.names()).containsExactly("felix", "torben", "joana");
    }

    /** Die Eigentuemerin darf zusaetzlich einen eigenen Token haben - er meint sie. */
    @Test
    void theOwnerMayHaveAPersonalTokenToo() {
        HealthUsers users = new HealthUsers("felix", "felix:" + TORBEN);
        assertThat(users.nameFor(TORBEN)).contains("felix");
        assertThat(users.names()).containsExactly("felix");
    }

    /** Namen werden zu Ordnern - alles ausser der engen Form scheitert beim Start. */
    @Test
    void rejectsNamesThatWouldNotBeSafeAsDirectories() {
        assertThatThrownBy(() -> new HealthUsers("felix", "../x:" + TORBEN))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new HealthUsers("felix", "Torben:" + TORBEN))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new HealthUsers("", ""))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsShortDuplicateAndMalformedEntries() {
        assertThatThrownBy(() -> new HealthUsers("felix", "torben:kurz"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new HealthUsers("felix", "torben:" + TORBEN + ",joana:" + TORBEN))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new HealthUsers("felix", "torben:" + TORBEN + ",torben:" + JOANA))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new HealthUsers("felix", "torben"))
                .isInstanceOf(IllegalStateException.class);
    }

    /** Ein Token gehoert nicht in die Fehlermeldung - die landet im Journal. */
    @Test
    void errorMessagesDoNotLeakTokens() {
        assertThatThrownBy(() -> new HealthUsers("felix", "tor ben:" + TORBEN))
                .hasMessageNotContaining(TORBEN);
    }

    @Test
    void theOwnerKeepsHerPathsAndEveryoneElseGetsAFolder() {
        UserFiles files = new UserFiles(new HealthUsers("felix", "torben:" + TORBEN));
        assertThat(files.resolve("felix", Path.of("data/food.json")))
                .isEqualTo(Path.of("data/food.json"));
        assertThat(files.resolve("torben", Path.of("data/food.json")))
                .isEqualTo(Path.of("data/users/torben/food.json"));
        assertThat(files.resolve("torben", Path.of("food.json")))
                .isEqualTo(Path.of("users/torben/food.json"));
        assertThatThrownBy(() -> files.resolve("../felix", Path.of("data/food.json")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

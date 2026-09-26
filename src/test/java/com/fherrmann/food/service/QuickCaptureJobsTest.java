package com.fherrmann.food.service;

import com.fherrmann.food.dto.QuickCaptureJob;
import com.fherrmann.food.dto.QuickCapturePreview;
import com.fherrmann.food.dto.QuickCaptureRequest;
import com.fherrmann.food.model.Meal;
import com.fherrmann.food.model.Nutrients;
import com.fherrmann.food.push.PushNotifier;
import com.fherrmann.food.security.HealthUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuickCaptureJobsTest {

    private FoodService service;
    private PushNotifier notifier;
    private QuickCaptureJobs jobs;

    private static final QuickCaptureRequest REQUEST =
            new QuickCaptureRequest(LocalDate.of(2026, 9, 26), "ein Apfel", null);

    private static final QuickCapturePreview PREVIEW = new QuickCapturePreview(
            false, null, "Apfel", new Nutrients(52, 0.3, 14, 0.2), 150.0, 150, Meal.SNACK,
            Map.of(), "geschaetzt");

    @BeforeEach
    void setUp() {
        service = mock(FoodService.class);
        notifier = mock(PushNotifier.class);
        HealthUsers users = new HealthUsers("felix", "torben:0123456789abcdef0123456789abcdef,"
                + "joana:fedcba9876543210fedcba9876543210");
        jobs = new QuickCaptureJobs(service, Clock.systemUTC(), notifier,
                new QuickCaptureAccess(users, "felix,torben"));
        when(service.quickCapture(anyString(), any(), anyString())).thenReturn(PREVIEW);
    }

    @Test
    void theResultGoesToThePersonWhoAskedAndOnlyThere() {
        QuickCaptureJob started = jobs.start("torben", REQUEST);

        verify(service, timeout(2000)).quickCapture(eq("torben"), eq(REQUEST), eq(started.id()));
        verify(notifier, timeout(2000)).quickCaptureFinished(
                eq("torben"), eq(started.id()), eq(QuickCaptureJob.DONE), eq(PREVIEW), any());
    }

    /** Den Auftrag einer anderen Person gibt es fuer einen nicht - nicht einmal als 403. */
    @Test
    void someoneElsesJobLooksLikeAnUnknownOne() {
        QuickCaptureJob started = jobs.start("torben", REQUEST);
        assertThat(jobs.status("torben", started.id()).id()).isEqualTo(started.id());
        assertThatThrownBy(() -> jobs.status("felix", started.id()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    void peopleWithoutAccessAreTurnedAwayBeforeAnythingRuns() {
        assertThatThrownBy(() -> jobs.start("joana", REQUEST))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
        verify(service, never()).quickCapture(anyString(), any(), anyString());
    }

    @Test
    void accessDefaultsToTheOwnerAndStarMeansEveryone() {
        HealthUsers users = new HealthUsers("felix", "torben:0123456789abcdef0123456789abcdef");
        QuickCaptureAccess ownerOnly = new QuickCaptureAccess(users, "");
        assertThat(ownerOnly.allows("felix")).isTrue();
        assertThat(ownerOnly.allows("torben")).isFalse();
        QuickCaptureAccess everyone = new QuickCaptureAccess(users, "*");
        assertThat(everyone.allows("torben")).isTrue();
        assertThatThrownBy(() -> new QuickCaptureAccess(users, "../x"))
                .isInstanceOf(IllegalStateException.class);
    }
}

package com.fherrmann.food.security;

import com.fherrmann.food.controller.FoodController;
import com.fherrmann.food.dto.DaySummary;
import com.fherrmann.food.model.Meal;
import com.fherrmann.food.model.Nutrients;
import com.fherrmann.food.push.DeviceTokens;
import com.fherrmann.food.push.DeviceTokens.Platform;
import com.fherrmann.food.service.FoodService;
import com.fherrmann.food.service.QuickCaptureAccess;
import com.fherrmann.food.service.QuickCaptureJobs;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Wer mit welchem Token hereinkommt - und wessen Tagebuch er dann sieht. Hier laeuft
 * der echte Filter, kein {@code @WithMockUser}.
 */
@WebMvcTest({FoodController.class, SetupController.class})
@Import({SecurityConfig.class, HealthUsers.class, QuickCaptureAccess.class})
@TestPropertySource(properties = {
        "food.security.token=private-token",
        "food.cors.allowed-origins=https://weight.fherrmann.com",
        "health.owner=felix",
        "health.tokens=torben:" + FoodAccessTest.TORBEN,
        "health.cookie-domain=fherrmann.com",
        "food.agent.people=felix",
})
class FoodAccessTest {

    static final String TORBEN = "0123456789abcdef0123456789abcdef";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @MockitoBean
    private FoodService service;

    @MockitoBean
    private QuickCaptureJobs quickCaptureJobs;

    @MockitoBean
    private DeviceTokens devices;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
        when(service.day(eq("felix"), any())).thenReturn(day(2300));
        when(service.day(eq("torben"), any())).thenReturn(day(2800));
        when(service.quickCaptureAvailable()).thenReturn(true);
    }

    private static DaySummary day(double kcal) {
        Nutrients targets = new Nutrients(kcal, 200, 235.5, 62);
        return new DaySummary(LocalDate.of(2026, 9, 26), targets, Nutrients.ZERO, targets, List.of(),
                Map.of(Meal.BREAKFAST, 0.0, Meal.LUNCH, 0.0, Meal.DINNER, 0.0, Meal.SNACK, 0.0));
    }

    @Test
    void bearerTokenSeesOnlyItsOwnPersonsDiary() throws Exception {
        mockMvc.perform(get("/api/food/day").header("Authorization", "Bearer " + TORBEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targets.kcal").value(2800.0));
        verify(service, never()).day(eq("felix"), any());
    }

    @Test
    void healthCookieWorksLikeTheBearerToken() throws Exception {
        mockMvc.perform(get("/api/food/day").cookie(new Cookie("health_token", TORBEN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targets.kcal").value(2800.0));
    }

    /** Der Privat-Cookie von fherrmann.com meint weiterhin die Eigentuemerin. */
    @Test
    void privateModeCookieStillMeansTheOwner() throws Exception {
        mockMvc.perform(get("/api/food/day").cookie(new Cookie("fh_private", "private-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targets.kcal").value(2300.0));
        verify(service, never()).day(eq("torben"), any());
    }

    /** Beide Cookies im selben Browser: der persoenliche gewinnt. */
    @Test
    void personalCookieWinsOverThePrivateModeCookie() throws Exception {
        mockMvc.perform(get("/api/food/day")
                        .cookie(new Cookie("fh_private", "private-token"), new Cookie("health_token", TORBEN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targets.kcal").value(2800.0));
    }

    @Test
    void unknownTokensAreRejected() throws Exception {
        mockMvc.perform(get("/api/food/day").header("Authorization", "Bearer private-token"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/food/day").cookie(new Cookie("health_token", "private-token")))
                .andExpect(status().isForbidden());
        verify(service, never()).day(anyString(), any());
    }

    /** features sagt, wer fragt - und ob die Schnellerfassung fuer genau diese Person frei ist. */
    @Test
    void featuresAreAnsweredPerPerson() throws Exception {
        mockMvc.perform(get("/api/food/features").header("Authorization", "Bearer " + TORBEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.me").value("torben"))
                .andExpect(jsonPath("$.quickCapture").value(false));
        mockMvc.perform(get("/api/food/features").cookie(new Cookie("fh_private", "private-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.me").value("felix"))
                .andExpect(jsonPath("$.quickCapture").value(true));
    }

    @Test
    void androidDevicesAreRegisteredForThePersonBehindTheToken() throws Exception {
        mockMvc.perform(post("/api/food/devices").header("Authorization", "Bearer " + TORBEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"fcm-abc\",\"platform\":\"android\"}"))
                .andExpect(status().isNoContent());
        verify(devices).add("torben", Platform.ANDROID, "fcm-abc");

        // Ohne Plattform: die iPhone-App, wie immer.
        mockMvc.perform(post("/api/food/devices").cookie(new Cookie("fh_private", "private-token"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"apns-hex\"}"))
                .andExpect(status().isNoContent());
        verify(devices).add("felix", Platform.IOS, "apns-hex");

        mockMvc.perform(post("/api/food/devices").header("Authorization", "Bearer " + TORBEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"x\",\"platform\":\"windows-phone\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void setupSetsTheHealthCookieForAPersonalToken() throws Exception {
        mockMvc.perform(get("https://food.fherrmann.com/setup").param("token", TORBEN))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, "/"))
                .andExpect(cookie().value("health_token", TORBEN))
                .andExpect(cookie().domain("health_token", "fherrmann.com"))
                .andExpect(cookie().httpOnly("health_token", true))
                .andExpect(cookie().secure("health_token", true));
    }

    /** Der Privat-Cookie wird hier nie ausgestellt - er oeffnet weit mehr als diesen Dienst. */
    @Test
    void setupRefusesThePrivateModeToken() throws Exception {
        mockMvc.perform(get("/setup").param("token", "private-token"))
                .andExpect(status().isForbidden())
                .andExpect(cookie().doesNotExist("fh_private"))
                .andExpect(cookie().doesNotExist("health_token"));
        mockMvc.perform(get("/setup"))
                .andExpect(status().isForbidden());
    }
}

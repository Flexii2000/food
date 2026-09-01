package com.fherrmann.food.controller;

import com.fherrmann.food.dto.DaySummary;
import com.fherrmann.food.model.Meal;
import com.fherrmann.food.model.Nutrients;
import com.fherrmann.food.security.SecurityConfig;
import com.fherrmann.food.service.FoodService;
import com.fherrmann.food.push.DeviceTokens;
import com.fherrmann.food.service.QuickCaptureJobs;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.context.annotation.Import;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FoodController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "food.security.token=testtoken",
        "food.cors.allowed-origins=https://weight.fherrmann.com",
})
class FoodControllerTest {

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
    void setUpMockMvc() {
        // Wie in der Weight-App: @WebMvcTest haengt die Security-Filterkette nicht
        // mehr von selbst an MockMvc, das muss explizit passieren.
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    private static DaySummary emptyDay() {
        return new DaySummary(
                LocalDate.of(2026, 8, 31),
                new Nutrients(2300, 200, 235.5, 62),
                Nutrients.ZERO,
                new Nutrients(2300, 200, 235.5, 62),
                List.of(),
                java.util.Map.of(Meal.BREAKFAST, 575.0, Meal.LUNCH, 805.0,
                        Meal.DINNER, 690.0, Meal.SNACK, 230.0));
    }

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/food/day")).andExpect(status().isForbidden());
    }

    @Test
    void wrongCookieValueIsRejected() throws Exception {
        mockMvc.perform(get("/api/food/day").cookie(new Cookie("fh_private", "wrong")))
                .andExpect(status().isForbidden());
    }

    @Test
    void thePrivateModeCookieOfFherrmannComIsAccepted() throws Exception {
        when(service.day(any())).thenReturn(emptyDay());
        mockMvc.perform(get("/api/food/day").cookie(new Cookie("fh_private", "testtoken")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targets.kcal").value(2300.0));
    }

    @Test
    @WithMockUser
    void dayReturnsTargetsAndTotals() throws Exception {
        when(service.day(LocalDate.of(2026, 8, 31))).thenReturn(emptyDay());
        mockMvc.perform(get("/api/food/day").param("date", "2026-08-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-08-31"))
                .andExpect(jsonPath("$.remaining.kcal").value(2300.0));
    }

    @Test
    @WithMockUser
    void entriesEndpointRejectsAMalformedBody() throws Exception {
        when(service.addEntry(any())).thenThrow(
                new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST, "grams is required"));
        mockMvc.perform(post("/api/food/entries")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void dailyIsReadableFromTheWeightTrackersOrigin() throws Exception {
        when(service.dailyTotals(any(), any())).thenReturn(List.of());
        // Der kcal-Overlay der Weight-App liest diesen Endpunkt cross-site; ohne die
        // beiden Header (und mit Credentials nur bei konkreter Origin) blockt der
        // Browser die Antwort.
        mockMvc.perform(get("/api/food/daily")
                        .param("from", "2026-08-01").param("to", "2026-08-31")
                        .header("Origin", "https://weight.fherrmann.com"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://weight.fherrmann.com"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    @WithMockUser
    void targetsAreReadableFromTheWeightTrackersOrigin() throws Exception {
        when(service.targets()).thenReturn(new Nutrients(2300, 200, 235.5, 62));
        // Die Weight-App zeichnet die kcal-Ziellinie in ihre Charts und braucht
        // dafuer denselben Wert, gegen den hier gerechnet wird.
        mockMvc.perform(get("/api/food/targets").header("Origin", "https://weight.fherrmann.com"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://weight.fherrmann.com"));
    }

    @Test
    @WithMockUser
    void dayIsNotReadableCrossSite() throws Exception {
        when(service.day(any())).thenReturn(emptyDay());
        // Nur /daily und /status sind fuer andere Origins freigegeben - der Rest der
        // API bleibt auf food.fherrmann.com beschraenkt.
        mockMvc.perform(get("/api/food/day").header("Origin", "https://weight.fherrmann.com"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}

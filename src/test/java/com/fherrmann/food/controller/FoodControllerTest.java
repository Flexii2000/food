package com.fherrmann.food.controller;

import com.fherrmann.food.dto.DaySummary;
import com.fherrmann.food.model.Meal;
import com.fherrmann.food.model.Nutrients;
import com.fherrmann.food.security.HealthUsers;
import com.fherrmann.food.security.SecurityConfig;
import com.fherrmann.food.service.FoodService;
import com.fherrmann.food.push.DeviceTokens;
import com.fherrmann.food.service.QuickCaptureAccess;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FoodController.class)
@Import({SecurityConfig.class, HealthUsers.class})
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

    @MockitoBean
    private QuickCaptureAccess quickCaptureAccess;

    @MockitoBean
    private com.fherrmann.food.service.DetailedNutrition detailedNutrition;

    @MockitoBean
    private com.fherrmann.food.service.MicronutrientTracking micronutrients;

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
        when(service.day(anyString(), any())).thenReturn(emptyDay());
        mockMvc.perform(get("/api/food/day").cookie(new Cookie("fh_private", "testtoken")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targets.kcal").value(2300.0));
    }

    @Test
    @WithMockUser
    void dayReturnsTargetsAndTotals() throws Exception {
        when(service.day(anyString(), eq(LocalDate.of(2026, 8, 31)))).thenReturn(emptyDay());
        mockMvc.perform(get("/api/food/day").param("date", "2026-08-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-08-31"))
                .andExpect(jsonPath("$.remaining.kcal").value(2300.0));
    }

    @Test
    @WithMockUser
    void entriesEndpointRejectsAMalformedBody() throws Exception {
        when(service.addEntry(anyString(), any())).thenThrow(
                new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST, "grams is required"));
        mockMvc.perform(post("/api/food/entries")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void dailyIsReadableFromTheWeightTrackersOrigin() throws Exception {
        when(service.dailyTotals(anyString(), any(), any())).thenReturn(List.of());
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
    void dailyNamesTheMealsOfEachDay() throws Exception {
        when(service.dailyTotals(anyString(), any(), any())).thenReturn(List.of(
                new com.fherrmann.food.dto.DayTotal(java.time.LocalDate.of(2026, 10, 8),
                        com.fherrmann.food.model.Nutrients.ZERO,
                        List.of(com.fherrmann.food.model.Meal.BREAKFAST, com.fherrmann.food.model.Meal.DINNER))));
        mockMvc.perform(get("/api/food/daily")
                        .param("from", "2026-10-08").param("to", "2026-10-08"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].meals[0]").value("BREAKFAST"))
                .andExpect(jsonPath("$[0].meals[1]").value("DINNER"));
    }

    @Test
    @WithMockUser
    void dailyAveragesAreReadableFromTheWeightTrackersOrigin() throws Exception {
        when(service.dailyAverages(anyString(), any(), any())).thenReturn(List.of(
                new com.fherrmann.food.dto.DayAverage(java.time.LocalDate.of(2026, 8, 30), 2100, 6, false)));
        mockMvc.perform(get("/api/food/daily-average")
                        .param("from", "2026-08-01").param("to", "2026-08-31")
                        .header("Origin", "https://weight.fherrmann.com"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://weight.fherrmann.com"))
                .andExpect(jsonPath("$[0].date").value("2026-08-30"))
                .andExpect(jsonPath("$[0].kcal").value(2100.0))
                .andExpect(jsonPath("$[0].days").value(6))
                .andExpect(jsonPath("$[0].complete").value(false));
    }

    @Test
    @WithMockUser
    void targetsAreReadableFromTheWeightTrackersOrigin() throws Exception {
        when(service.targets(anyString())).thenReturn(
                com.fherrmann.food.dto.TargetsView.of(new Nutrients(2300, 200, 235.5, 62), null));
        // Die Weight-App zeichnet die kcal-Ziellinie in ihre Charts und braucht
        // dafuer denselben Wert, gegen den hier gerechnet wird.
        mockMvc.perform(get("/api/food/targets").header("Origin", "https://weight.fherrmann.com"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://weight.fherrmann.com"));
    }

    /** Der Eiweiß-Erhalt reist mit den Zielen - hinein wie hinaus (Vertrag §11.1). */
    @Test
    @WithMockUser
    void theProteinFloorTravelsWithTheTargets() throws Exception {
        when(service.updateTargets(anyString(), any())).thenReturn(
                com.fherrmann.food.dto.TargetsView.of(new Nutrients(2300, 195, 236, 70), 140.0));
        mockMvc.perform(put("/api/food/targets").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kcal\":2300,\"proteinG\":195,\"carbsG\":236,\"fatG\":70,\"proteinMinG\":140}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proteinG").value(195.0))
                .andExpect(jsonPath("$.proteinMinG").value(140.0))
                .andExpect(jsonPath("$.proteinMinDefault").value(false));
        org.mockito.Mockito.verify(service).updateTargets(anyString(), eq(
                new com.fherrmann.food.dto.TargetsRequest(2300.0, 195.0, 236.0, 70.0, null, null, 140.0)));

        // Ein Client, der das Feld nicht kennt: es kommt als "keine Angabe" an, nicht als 0.
        mockMvc.perform(put("/api/food/targets").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kcal\":2300,\"proteinG\":195,\"carbsG\":236,\"fatG\":70}"))
                .andExpect(status().isOk());
        org.mockito.Mockito.verify(service).updateTargets(anyString(), eq(
                new com.fherrmann.food.dto.TargetsRequest(2300.0, 195.0, 236.0, 70.0, null, null, null)));
    }

    /** Was der Weight Tracker fuer den Chat liest (Vertrag §11.4): vom Server aus, also ohne CORS. */
    @Test
    @WithMockUser
    void entriesAndFrequentDishesAreReadServerSideOnly() throws Exception {
        when(service.entryLines(anyString(), eq(LocalDate.of(2026, 11, 1)), eq(LocalDate.of(2026, 11, 10))))
                .thenReturn(List.of(new com.fherrmann.food.dto.EntryLine(LocalDate.of(2026, 11, 9), Meal.LUNCH,
                        "Gericht A", 300.0, 240.0, 24.0, 18.0, 3.9)));
        when(service.frequentDishes(anyString(), eq(90), eq(30))).thenReturn(List.of(
                new com.fherrmann.food.dto.FrequentDish("Gericht A", 3, LocalDate.of(2026, 11, 10), 250.0,
                        80.0, 8.0, 6.0, 1.3)));
        when(service.frequentDishes(anyString(), eq(14), eq(5))).thenReturn(List.of());

        mockMvc.perform(get("/api/food/entries").param("from", "2026-11-01").param("to", "2026-11-10")
                        .header("Origin", "https://weight.fherrmann.com"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
                .andExpect(jsonPath("$[0].date").value("2026-11-09"))
                .andExpect(jsonPath("$[0].meal").value("LUNCH"))
                .andExpect(jsonPath("$[0].name").value("Gericht A"))
                .andExpect(jsonPath("$[0].kcal").value(240.0));
        // Ohne Angaben: 90 Tage, 30 Gerichte.
        mockMvc.perform(get("/api/food/frequent-dishes").header("Origin", "https://weight.fherrmann.com"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
                .andExpect(jsonPath("$[0].name").value("Gericht A"))
                .andExpect(jsonPath("$[0].count").value(3))
                .andExpect(jsonPath("$[0].lastUsedOn").value("2026-11-10"))
                .andExpect(jsonPath("$[0].kcalPer100g").value(80.0));
        mockMvc.perform(get("/api/food/frequent-dishes").param("days", "14").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        // Ohne Spanne gibt es keine Eintraege - die Spanne ist Pflicht.
        mockMvc.perform(get("/api/food/entries")).andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void dayIsNotReadableCrossSite() throws Exception {
        when(service.day(anyString(), any())).thenReturn(emptyDay());
        // Nur /daily und /status sind fuer andere Origins freigegeben - der Rest der
        // API bleibt auf food.fherrmann.com beschraenkt.
        mockMvc.perform(get("/api/food/day").header("Origin", "https://weight.fherrmann.com"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}

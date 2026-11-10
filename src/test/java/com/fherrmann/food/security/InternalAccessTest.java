package com.fherrmann.food.security;

import com.fherrmann.food.controller.FoodController;
import com.fherrmann.food.controller.InternalController;
import com.fherrmann.food.dto.DishRequest;
import com.fherrmann.food.push.DeviceTokens;
import com.fherrmann.food.push.PushNotifier;
import com.fherrmann.food.service.FoodService;
import com.fherrmann.food.service.QuickCaptureAccess;
import com.fherrmann.food.service.QuickCaptureJobs;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Wer an die internen Endpunkte kommt (Vertrag §11.3) - mit dem echten Filter, kein
 * {@code @WithMockUser}: nur von innen, je Endpunkt nur mit seinem einen Schluessel, und
 * nie mit einem Cookie.
 */
@WebMvcTest({InternalController.class, FoodController.class})
@Import({SecurityConfig.class, HealthUsers.class, QuickCaptureAccess.class,
        com.fherrmann.food.service.DetailedNutrition.class, com.fherrmann.food.service.MicronutrientTracking.class})
@TestPropertySource(properties = {
        "food.security.token=private-token",
        "food.cors.allowed-origins=https://weight.fherrmann.com",
        "health.owner=felix",
        "health.tokens=felix:" + InternalAccessTest.FELIX + ",torben:" + InternalAccessTest.TORBEN,
        "food.recipe-token=" + InternalAccessTest.RECIPES,
})
class InternalAccessTest {

    static final String FELIX = "ffffffffffffffffffffffffffffffff";
    static final String TORBEN = "0123456789abcdef0123456789abcdef";
    static final String RECIPES = "recipe-service-token-0123456789";

    private static final String PUSH = "{\"person\":\"torben\",\"kind\":\"friend-nudge\",\"title\":\"Person A\","
            + "\"body\":\"stupst dich an\",\"data\":{\"friendshipId\":\"f-1\"}}";
    private static final String DISHES = "{\"persons\":[\"felix\",\"joana\",\"torben\"],\"dish\":{"
            + "\"name\":\"Rezept: Gericht A\",\"kcal\":152.4,\"proteinG\":5.7,\"carbsG\":18.6,\"fatG\":5.7,"
            + "\"portionG\":420.0}}";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @MockitoBean
    private FoodService service;

    @MockitoBean
    private PushNotifier notifier;

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
        when(notifier.send(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(new PushNotifier.Delivery(1, 0));
        when(service.putRecipeDish(anyString(), any())).thenReturn("created");
    }

    private static MockHttpServletRequestBuilder push() {
        return post("/api/internal/push").contentType(MediaType.APPLICATION_JSON).content(PUSH);
    }

    private static MockHttpServletRequestBuilder dishes() {
        return post("/api/internal/recipe-dishes").contentType(MediaType.APPLICATION_JSON).content(DISHES);
    }

    // --- /api/internal/push -----------------------------------------------------------

    /** Der Weight Tracker: Bearer-Token der Eigentuemerin, ohne nginx davor. */
    @Test
    void theOwnersBearerTokenSendsAPushToAnotherPerson() throws Exception {
        mockMvc.perform(push().header("Authorization", "Bearer " + FELIX))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"ios\":1,\"android\":0}", org.springframework.test.json.JsonCompareMode.STRICT));
        verify(notifier).send("torben", "friend-nudge", "Person A", "stupst dich an", Map.of("friendshipId", "f-1"));
    }

    @Test
    void anotherPersonsTokenMayNotPush() throws Exception {
        mockMvc.perform(push().header("Authorization", "Bearer " + TORBEN)).andExpect(status().isForbidden());
        verifyNoInteractions(notifier);
    }

    /** Kein Cookie zaehlt hier - weder der Privat-Cookie noch der Healthy-Cookie der Eigentuemerin. */
    @Test
    void cookiesNeverOpenTheInternalEndpoints() throws Exception {
        mockMvc.perform(push().cookie(new Cookie("fh_private", "private-token"))).andExpect(status().isForbidden());
        mockMvc.perform(push().cookie(new Cookie("health_token", FELIX))).andExpect(status().isForbidden());
        mockMvc.perform(dishes().cookie(new Cookie("fh_private", "private-token"))).andExpect(status().isForbidden());
        mockMvc.perform(push()).andExpect(status().isForbidden());
        // Ein ungueltiger Bearer faellt nicht auf den Cookie daneben zurueck.
        mockMvc.perform(push().header("Authorization", "Bearer falsch")
                        .cookie(new Cookie("fh_private", "private-token")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(notifier);
        verify(service, never()).putRecipeDish(anyString(), any());
    }

    /** Ueber nginx (X-Real-IP) gibt es den Pfad nicht - auch nicht mit dem richtigen Token. */
    @Test
    void requestsThroughNginxGetNotFound() throws Exception {
        mockMvc.perform(push().header("Authorization", "Bearer " + FELIX).header("X-Real-IP", "203.0.113.7"))
                .andExpect(status().isNotFound());
        mockMvc.perform(dishes().header("Authorization", "Bearer " + RECIPES).header("X-Real-IP", "203.0.113.7"))
                .andExpect(status().isNotFound());
        mockMvc.perform(push().header("X-Real-IP", "203.0.113.7")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/internal/irgendwas").header("X-Real-IP", "203.0.113.7"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(notifier);
        verify(service, never()).putRecipeDish(anyString(), any());
    }

    @Test
    void pushingToAnUnknownPersonIsNotFound() throws Exception {
        mockMvc.perform(post("/api/internal/push").header("Authorization", "Bearer " + FELIX)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"person\":\"joana\",\"kind\":\"friend-nudge\",\"title\":\"Person A\",\"body\":\"x\"}"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(notifier);
    }

    @Test
    void aPushNeedsPersonKindAndTitle() throws Exception {
        for (String body : new String[] {
                "{}",
                "{\"kind\":\"friend-nudge\",\"title\":\"Person A\"}",
                "{\"person\":\"torben\",\"title\":\"Person A\"}",
                "{\"person\":\"torben\",\"kind\":\"Friend Nudge\",\"title\":\"Person A\"}",
                "{\"person\":\"torben\",\"kind\":\"friend-nudge\",\"title\":\" \"}",
        }) {
            mockMvc.perform(post("/api/internal/push").header("Authorization", "Bearer " + FELIX)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(notifier);
    }

    /** Text und Daten duerfen fehlen - der Notifier bekommt dann Leeres, nie null. */
    @Test
    void bodyAndDataAreOptional() throws Exception {
        mockMvc.perform(post("/api/internal/push").header("Authorization", "Bearer " + FELIX)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"person\":\"felix\",\"kind\":\"chat-answer\",\"title\":\"Healthy\"}"))
                .andExpect(status().isOk());
        verify(notifier).send("felix", "chat-answer", "Healthy", "", Map.of());
    }

    // --- /api/internal/recipe-dishes --------------------------------------------------

    /** Die Einkaufsliste: nur ihr Dienst-Token, und je Person ein Ergebnis in der Reihenfolge der Anfrage. */
    @Test
    void theRecipeTokenPutsADishIntoEveryLinkedDiary() throws Exception {
        when(service.putRecipeDish(eq("torben"), any())).thenThrow(
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "kcal must be at most 1000.0"));

        mockMvc.perform(dishes().header("Authorization", "Bearer " + RECIPES))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].person").value("felix"))
                .andExpect(jsonPath("$[0].status").value("created"))
                .andExpect(jsonPath("$[0].message").isEmpty())
                // Keine Healthy-Person dieses Namens: kein Fehler, nur die Auskunft.
                .andExpect(jsonPath("$[1].person").value("joana"))
                .andExpect(jsonPath("$[1].status").value("not-linked"))
                // Scheitert es bei einer Person, bleibt es bei den anderen stehen.
                .andExpect(jsonPath("$[2].person").value("torben"))
                .andExpect(jsonPath("$[2].status").value("rejected"))
                .andExpect(jsonPath("$[2].message").value("kcal must be at most 1000.0"));

        DishRequest dish = new DishRequest("Rezept: Gericht A", 152.4, 5.7, 18.6, 5.7, 420.0);
        verify(service).putRecipeDish("felix", dish);
        verify(service).putRecipeDish("torben", dish);
        verify(service, never()).putRecipeDish(eq("joana"), any());
    }

    /** Ein persoenlicher Token - auch der der Eigentuemerin - ist nicht der Dienst-Token. */
    @Test
    void personalTokensMayNotPutRecipeDishes() throws Exception {
        mockMvc.perform(dishes().header("Authorization", "Bearer " + FELIX)).andExpect(status().isForbidden());
        mockMvc.perform(dishes().header("Authorization", "Bearer " + TORBEN)).andExpect(status().isForbidden());
        mockMvc.perform(dishes()).andExpect(status().isForbidden());
        verify(service, never()).putRecipeDish(anyString(), any());
    }

    /** Jeder Schluessel oeffnet genau seinen Endpunkt - nicht den anderen und nichts sonst. */
    @Test
    void eachKeyOpensOnlyItsOwnEndpoint() throws Exception {
        mockMvc.perform(push().header("Authorization", "Bearer " + RECIPES)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/food/day").header("Authorization", "Bearer " + RECIPES))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/food/dishes").header("Authorization", "Bearer " + RECIPES))
                .andExpect(status().isForbidden());
        // Was es unter /api/internal/ nicht gibt, oeffnet auch kein gueltiger Token.
        mockMvc.perform(post("/api/internal/irgendwas").header("Authorization", "Bearer " + FELIX))
                .andExpect(status().isForbidden());
        verifyNoInteractions(notifier);
    }

    @Test
    void recipeDishesNeedPersonsAndADish() throws Exception {
        for (String body : new String[] {
                "{}",
                "{\"persons\":[\"felix\"]}",
                "{\"dish\":{\"name\":\"Rezept: Gericht A\",\"kcal\":1,\"proteinG\":1,\"carbsG\":1,\"fatG\":1}}",
                "{\"persons\":[],\"dish\":{\"name\":\"Rezept: Gericht A\",\"kcal\":1,\"proteinG\":1,\"carbsG\":1,\"fatG\":1}}",
                "{\"persons\":[\" \"],\"dish\":{\"name\":\"Rezept: Gericht A\",\"kcal\":1,\"proteinG\":1,\"carbsG\":1,\"fatG\":1}}",
        }) {
            mockMvc.perform(post("/api/internal/recipe-dishes").header("Authorization", "Bearer " + RECIPES)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verify(service, never()).putRecipeDish(anyString(), any());
    }

    /** Dieselbe Person zweimal genannt wird einmal bedient. */
    @Test
    void aPersonNamedTwiceIsServedOnce() throws Exception {
        mockMvc.perform(post("/api/internal/recipe-dishes").header("Authorization", "Bearer " + RECIPES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"persons\":[\"felix\",\" felix \"],\"dish\":{\"name\":\"Rezept: Gericht A\","
                                + "\"kcal\":1,\"proteinG\":1,\"carbsG\":1,\"fatG\":1}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    /** Die uebrigen Endpunkte merken von alledem nichts: der Privat-Cookie meint weiter die Eigentuemerin. */
    @Test
    void theRestOfTheApiIsUnchanged() throws Exception {
        when(service.dishes("felix")).thenReturn(java.util.List.of());
        mockMvc.perform(get("/api/food/dishes").cookie(new Cookie("fh_private", "private-token")))
                .andExpect(status().isOk());
        // X-Real-IP traegt jede Anfrage ueber nginx - ausserhalb von /api/internal/ stoert es nicht.
        mockMvc.perform(get("/api/food/dishes").header("Authorization", "Bearer " + FELIX)
                        .header("X-Real-IP", "203.0.113.7"))
                .andExpect(status().isOk());
    }
}

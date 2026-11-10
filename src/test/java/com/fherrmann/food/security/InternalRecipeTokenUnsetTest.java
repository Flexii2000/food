package com.fherrmann.food.security;

import com.fherrmann.food.controller.InternalController;
import com.fherrmann.food.push.PushNotifier;
import com.fherrmann.food.service.FoodService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ohne {@code FOOD_RECIPE_TOKEN} gibt es {@code /api/internal/recipe-dishes} nicht - so
 * sieht der Dienst aus, bevor das Setup der Einkaufsliste gelaufen ist. {@code /push}
 * haengt davon nicht ab.
 */
@WebMvcTest(InternalController.class)
@Import({SecurityConfig.class, HealthUsers.class})
@TestPropertySource(properties = {
        "food.security.token=private-token",
        "food.cors.allowed-origins=https://weight.fherrmann.com",
        "health.tokens=felix:" + InternalRecipeTokenUnsetTest.FELIX,
})
class InternalRecipeTokenUnsetTest {

    static final String FELIX = "ffffffffffffffffffffffffffffffff";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @MockitoBean
    private FoodService service;

    @MockitoBean
    private PushNotifier notifier;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
    }

    @Test
    void withoutAConfiguredTokenTheRecipeEndpointDoesNotExist() throws Exception {
        String body = "{\"persons\":[\"felix\"],\"dish\":{\"name\":\"Rezept: Gericht A\",\"kcal\":1,"
                + "\"proteinG\":1,\"carbsG\":1,\"fatG\":1}}";
        mockMvc.perform(post("/api/internal/recipe-dishes").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
        // Auch ein leerer oder irgendein Bearer ist kein Schluessel fuer einen leeren Token.
        mockMvc.perform(post("/api/internal/recipe-dishes").header("Authorization", "Bearer ")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/internal/recipe-dishes").header("Authorization", "Bearer " + FELIX)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
        verify(service, never()).putRecipeDish(anyString(), any());
    }

    @Test
    void pushWorksWithoutTheRecipeToken() throws Exception {
        when(notifier.send(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(new PushNotifier.Delivery(0, 0));
        mockMvc.perform(post("/api/internal/push").header("Authorization", "Bearer " + FELIX)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"person\":\"felix\",\"kind\":\"chat-answer\",\"title\":\"Healthy\"}"))
                .andExpect(status().isOk());
    }
}

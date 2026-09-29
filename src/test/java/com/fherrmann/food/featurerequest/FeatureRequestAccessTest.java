package com.fherrmann.food.featurerequest;

import com.fherrmann.food.security.HealthUsers;
import com.fherrmann.food.security.SecurityConfig;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Wer mit welchem Token welche Anfragen sieht - mit dem echten Filter, der echten
 * Ablage und nur dem To-Do und Claude als Attrappe.
 */
@WebMvcTest({FeatureRequestController.class, FeatureRequestPage.class})
@Import({SecurityConfig.class, HealthUsers.class, FeatureRequestService.class, FeatureRequestRepository.class})
@TestPropertySource(properties = {
        "food.security.token=private-token",
        "food.cors.allowed-origins=https://weight.fherrmann.com",
        "health.owner=felix",
        "health.tokens=torben:" + FeatureRequestAccessTest.TORBEN + ",joana:" + FeatureRequestAccessTest.JOANA,
        "food.feature-requests.data-file=" + FeatureRequestAccessTest.DATA_FILE,
})
class FeatureRequestAccessTest {

    static final String TORBEN = "0123456789abcdef0123456789abcdef";
    static final String JOANA = "fedcba9876543210fedcba9876543210";
    static final String DATA_FILE = "build/tmp/feature-request-access/feature-requests.json";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @MockitoBean
    private FeatureRequestTodos todos;

    @MockitoBean
    private StoryDraftJobs drafts;

    @Autowired
    private FeatureRequestRepository repository;

    private MockMvc mockMvc;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        Files.deleteIfExists(Path.of(DATA_FILE));
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        when(todos.ensureTodo(any())).thenAnswer(call -> call.getArgument(0));
        when(todos.cardUrl(anyString())).thenAnswer(call -> "https://fherrmann.com/feature-requests/" + call.getArgument(0));
        when(todos.board()).thenReturn(Optional.empty());
        when(drafts.isAvailable()).thenReturn(true);
        // Wie das Original, nur ohne To-Do dahinter: die Anfrage verschwindet aus der Ablage.
        when(todos.remove(anyString())).thenAnswer(call -> repository.remove(call.getArgument(0)));
    }

    private static MockHttpServletRequestBuilder asTorben(MockHttpServletRequestBuilder request) {
        return request.cookie(new Cookie("health_token", TORBEN));
    }

    private static MockHttpServletRequestBuilder asJoana(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + JOANA);
    }

    private static MockHttpServletRequestBuilder asFelix(MockHttpServletRequestBuilder request) {
        return request.cookie(new Cookie("fh_private", "private-token"));
    }

    private String submit(MockHttpServletRequestBuilder as, String title) throws Exception {
        String json = mockMvc.perform(as.contentType(MediaType.APPLICATION_JSON).content("""
                        {"originalText":"ich will","title":"%s","story":"Als … möchte ich …, damit …",
                         "acceptanceCriteria":["eins"]}""".formatted(title)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(json).path("id").asString();
    }

    @Test
    void everyoneSeesTheirOwnRequestsAndFelixSeesAll() throws Exception {
        submit(asTorben(post("/feature-requests/api/requests")), "Von Torben");
        submit(asFelix(post("/feature-requests/api/requests")), "Von Felix");

        mockMvc.perform(asTorben(get("/feature-requests/api/requests")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Von Torben"))
                .andExpect(jsonPath("$[0].author").value("torben"))
                .andExpect(jsonPath("$[0].status").value("open"));
        mockMvc.perform(asFelix(get("/feature-requests/api/requests")))
                .andExpect(jsonPath("$.length()").value(2));
        mockMvc.perform(asJoana(get("/feature-requests/api/requests")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    /** Die Kartenseite sehen Autor und Eigentuemerin; fuer alle anderen gibt es sie nicht. */
    @Test
    void aCardIsForItsAuthorAndTheOwnerOnly() throws Exception {
        String id = submit(asTorben(post("/feature-requests/api/requests")), "Nur für zwei");

        mockMvc.perform(asTorben(get("/feature-requests/api/requests/" + id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originalText").value("ich will"))
                .andExpect(jsonPath("$.url").value("https://fherrmann.com/feature-requests/" + id));
        mockMvc.perform(asFelix(get("/feature-requests/api/requests/" + id)))
                .andExpect(status().isOk());
        mockMvc.perform(asJoana(get("/feature-requests/api/requests/" + id)))
                .andExpect(status().isNotFound());
    }

    /** Loeschen darf nur Felix - fuer alle anderen gibt es die Anfrage nicht, auch fuer den Autor. */
    @Test
    void onlyTheOwnerDeletesAndTheSubtaskGoesWithIt() throws Exception {
        String id = submit(asTorben(post("/feature-requests/api/requests")), "Doch nicht");

        mockMvc.perform(asTorben(delete("/feature-requests/api/requests/" + id)))
                .andExpect(status().isNotFound());
        mockMvc.perform(asJoana(delete("/feature-requests/api/requests/" + id)))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/feature-requests/api/requests/" + id))
                .andExpect(status().isForbidden());
        verify(todos, never()).remove(anyString());
        mockMvc.perform(asTorben(get("/feature-requests/api/requests/" + id)))
                .andExpect(status().isOk());

        mockMvc.perform(asFelix(delete("/feature-requests/api/requests/" + id)))
                .andExpect(status().isNoContent());
        verify(todos).remove(id);
        mockMvc.perform(asTorben(get("/feature-requests/api/requests/" + id)))
                .andExpect(status().isNotFound());
        mockMvc.perform(asFelix(delete("/feature-requests/api/requests/" + id)))
                .andExpect(status().isNotFound());
    }

    /** Kein permitAll: ohne Token ist alles unter /feature-requests/ gesperrt, auch die Seite und ihre Dateien. */
    @Test
    void withoutATokenEverythingIsForbidden() throws Exception {
        mockMvc.perform(get("/feature-requests/api/requests")).andExpect(status().isForbidden());
        mockMvc.perform(get("/feature-requests/api/features")).andExpect(status().isForbidden());
        mockMvc.perform(post("/feature-requests/api/drafts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"x\"}")).andExpect(status().isForbidden());
        mockMvc.perform(get("/feature-requests/").accept(MediaType.TEXT_HTML)).andExpect(status().isForbidden());
        mockMvc.perform(get("/feature-requests/app.js")).andExpect(status().isForbidden());
        mockMvc.perform(get("/feature-requests/api/requests").header("Authorization", "Bearer private-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void thePageIsServedForTheListAndForEveryCard() throws Exception {
        mockMvc.perform(asTorben(get("/feature-requests/")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("<title>Feature Requests</title>")));
        mockMvc.perform(asFelix(get("/feature-requests/5b7d2c9e-0000-4000-8000-000000000000")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<title>Feature Requests</title>")));
        mockMvc.perform(asTorben(get("/feature-requests")))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/feature-requests/"));
        // Dateien kommen als Dateien, nicht als weitere Kopie der Seite.
        mockMvc.perform(asTorben(get("/feature-requests/app.js")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("<title>"))));
    }

    @Test
    void featuresSayWhoAsksAndWhetherClaudeDrafts() throws Exception {
        mockMvc.perform(asTorben(get("/feature-requests/api/features")))
                .andExpect(jsonPath("$.me").value("torben"))
                .andExpect(jsonPath("$.owner").value(false))
                .andExpect(jsonPath("$.drafting").value(true));
        when(drafts.isAvailable()).thenReturn(false);
        mockMvc.perform(asFelix(get("/feature-requests/api/features")))
                .andExpect(jsonPath("$.me").value("felix"))
                .andExpect(jsonPath("$.owner").value(true))
                .andExpect(jsonPath("$.drafting").value(false));
    }

    @Test
    void anInvalidCardIsABadRequestWithItsReason() throws Exception {
        mockMvc.perform(asTorben(post("/feature-requests/api/requests")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"%s\",\"story\":\"s\"}".formatted("x".repeat(121))))
                .andExpect(status().isBadRequest())
                .andExpect(status().reason(containsString("120")));
    }
}

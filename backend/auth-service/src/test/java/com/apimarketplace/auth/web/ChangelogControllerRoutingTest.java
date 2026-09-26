package com.apimarketplace.auth.web;

import com.apimarketplace.auth.service.ChangelogSeenService;
import com.apimarketplace.auth.service.ChangelogSeenService.ChangelogState;
import com.apimarketplace.auth.service.ChangelogSeenService.SeenOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the URLs and the SERIALIZED payload, neither of which a direct method call can.
 *
 * <p>Two distinct failures live here, both invisible to the unit tests:
 * <ul>
 *   <li>the gateway routes {@code /api/changelog/**} here and the browser asks through the proxy,
 *       so a changed class or method mapping 404s in production while every direct-invocation test
 *       stays green;</li>
 *   <li>the response is serialized by the ObjectMapper of whichever context hosts this controller,
 *       and in CE that is catalog-service's {@code @Primary} one, configured with
 *       {@code WRITE_NULL_MAP_VALUES=false}. A {@code Map} payload therefore LOSES its null keys
 *       in CE and keeps them in cloud. This suite serializes through exactly that configuration,
 *       so the map form fails here rather than three hours later on a running stack, which is how
 *       it was actually found.</li>
 * </ul>
 */
class ChangelogControllerRoutingTest {

    private ChangelogSeenService service;
    private MockMvc mockMvc;

    /**
     * The CE monolith's effective mapper: catalog-service's WebConfig declares it {@code @Primary}
     * and switches off null map values. Mirrored here rather than imported, because auth-service
     * does not depend on catalog-service, and the point is the SETTING, not that class.
     */
    private static ObjectMapper ceStyleMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        mapper.configure(SerializationFeature.WRITE_NULL_MAP_VALUES, false);
        return mapper;
    }

    @BeforeEach
    void setUp() {
        service = mock(ChangelogSeenService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ChangelogController(service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(ceStyleMapper()))
                .build();
    }

    @Test
    @DisplayName("GET /api/changelog/state answers on that exact path, from the X-User-ID header")
    void stateIsMappedAtTheDocumentedPath() throws Exception {
        when(service.state(42L, "2026-09-entry"))
                .thenReturn(new ChangelogState(true, "2026-09-entry", false));

        // The entry key rides on the query string, which is also what stamps this install's
        // first sight of it: a binding change here silently disables the sealing rule.
        mockMvc.perform(get("/api/changelog/state").param("entry", "2026-09-entry")
                        .header("X-User-ID", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.seenKey").value("2026-09-entry"))
                .andExpect(jsonPath("$.seal").value(false));
    }

    @Test
    @DisplayName("an unacknowledged user gets seenKey PRESENT and null, even under CE's mapper")
    void nullSeenKeyIsWrittenNotDropped() throws Exception {
        when(service.state(42L, null)).thenReturn(new ChangelogState(true, null, false));

        String json = mockMvc.perform(get("/api/changelog/state").header("X-User-ID", "42"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // The exact regression: with a Map payload this answered {"accountCreatedAt":...,
        // "enabled":true} on CE, no seenKey at all. A JavaScript client reads the missing key as
        // undefined, which happens to behave like null today - so nothing breaks visibly, and the
        // endpoint quietly has a different contract per edition.
        assertThat(json)
                .as("seenKey must be written as null rather than omitted")
                .contains("\"seenKey\":null");
        assertThat(json).contains("\"seal\":false");
    }

    @Test
    @DisplayName("GET /api/changelog/state without the header is 401, not a 500 or a default user")
    void stateWithoutHeaderIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/changelog/state")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/changelog/seen accepts the documented JSON body on that exact path")
    void seenIsMappedAtTheDocumentedPath() throws Exception {
        when(service.markSeen(42L, "2026-09-entry")).thenReturn(SeenOutcome.RECORDED);

        mockMvc.perform(post("/api/changelog/seen")
                        .header("X-User-ID", "42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"2026-09-entry\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.seenKey").value("2026-09-entry"));
    }

    @Test
    @DisplayName("a disabled deployment answers enabled=false with seenKey present and null")
    void disabledAcknowledgementKeepsTheShape() throws Exception {
        when(service.markSeen(42L, "2026-09-entry")).thenReturn(SeenOutcome.DISABLED);

        mockMvc.perform(post("/api/changelog/seen")
                        .header("X-User-ID", "42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"2026-09-entry\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"seenKey\":null")))
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    @DisplayName("POST /api/changelog/seen rejects a malformed key without echoing it back")
    void seenRejectsAMalformedKeyWithoutEchoingIt() throws Exception {
        mockMvc.perform(post("/api/changelog/seen")
                        .header("X-User-ID", "42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"<script>alert(1)</script>\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists())
                // Unbounded caller input is not reflected back; its length is what helps a real
                // debugging session.
                .andExpect(jsonPath("$.received").doesNotExist())
                .andExpect(jsonPath("$.receivedLength").value("<script>alert(1)</script>".length()));
    }

    @Test
    @DisplayName("an empty body is rejected rather than stored as a null key")
    void emptyBodyIsRejected() throws Exception {
        mockMvc.perform(post("/api/changelog/seen")
                        .header("X-User-ID", "42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verify(service, never()).markSeen(anyLong(), anyString());
    }

    @Test
    @DisplayName("POST /api/changelog/seen for a deleted user answers 404 with a JSON error")
    void seenForDeletedUserIsNotFoundOnTheWire() throws Exception {
        when(service.markSeen(404L, "2026-09-entry")).thenReturn(SeenOutcome.UNKNOWN_USER);

        mockMvc.perform(post("/api/changelog/seen")
                        .header("X-User-ID", "404")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"2026-09-entry\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("user not found"));
    }
}

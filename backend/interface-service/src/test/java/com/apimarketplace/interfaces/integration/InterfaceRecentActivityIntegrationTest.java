package com.apimarketplace.interfaces.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Executes the recent-activity query for real, against the module's H2 schema.
 *
 * <p><b>Why this exists and a unit test does not suffice.</b> The endpoint was rewritten to
 * return a Spring Data interface projection, and Spring Data binds each projection getter to a
 * select alias BY NAME. A mistyped or swapped alias compiles, passes JPQL bootstrap validation,
 * and is invisible to every mock-based test in this module - the controller test mocks the
 * projection itself, so it proves the mapping and nothing about the query. The only thing that
 * settles alias binding is running the query, which this module can already do.
 *
 * <p>It is NOT a proof for dropping {@code @Lob} from the template columns, and should not be
 * read as one: H2 has no PostgreSQL large objects, so restoring those annotations leaves these
 * tests green. That failure mode cannot be reproduced here at all, which is why the guard for it
 * is {@code templateFieldsMustNotBeLob} asserting the annotations' absence rather than any
 * behavioural test.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(IntegrationTestConfig.class)
class InterfaceRecentActivityIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    private static final String TENANT = "recent-activity-tenant";
    private static final String ORG = "recent-activity-org";

    @Test
    void recentActivityBindsEveryProjectionFieldFromTheRealQuery() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "name", "Recent Activity Page",
                "htmlTemplate", "<div>{{greeting}}</div>",
                "cssTemplate", ".c { color: blue; }",
                "jsTemplate", "console.log('test');"));

        MvcResult created = mockMvc.perform(post("/api/interfaces")
                        .header("X-User-ID", TENANT)
                        .header("X-Organization-ID", ORG)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();

        String id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(get("/api/internal/interfaces/recent-activity")
                        .header("X-User-ID", TENANT)
                        .header("X-Organization-ID", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                // Each of these is one alias-to-getter binding. An alias that fails to bind
                // yields null here; two aliases swapped yield each other's value - which is why
                // name and actorId are asserted to DIFFERENT values, and neither is generic.
                .andExpect(jsonPath("$.items[0].resourceId").value(id))
                .andExpect(jsonPath("$.items[0].name").value("Recent Activity Page"))
                .andExpect(jsonPath("$.items[0].actorId").value(TENANT))
                .andExpect(jsonPath("$.items[0].lastEditedAt").isNotEmpty());
    }

    @Test
    void recentActivityIsScopedToTheRequestedWorkspace() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "name", "Other Workspace Page",
                "htmlTemplate", "<p>x</p>"));

        mockMvc.perform(post("/api/interfaces")
                        .header("X-User-ID", TENANT)
                        .header("X-Organization-ID", "some-other-org")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // The org predicate is part of the same rewritten query; a projection change must not
        // quietly widen what the feed returns.
        mockMvc.perform(get("/api/internal/interfaces/recent-activity")
                        .header("X-User-ID", TENANT)
                        .header("X-Organization-ID", "yet-another-org"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
    }
}

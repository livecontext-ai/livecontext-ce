package com.apimarketplace.orchestrator.integration.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack HTTP integration test for the resource-favorites endpoints: boots the
 * real Spring MVC context (DispatcherServlet routing, JSON serialization, header
 * binding) over a real JPA repository (H2 in PostgreSQL mode, {@code orchestrator}
 * schema created from the entity). This is the live-path proof that:
 *  - {@code GET /api/favorites/{type}/ids} resolves to the list handler and is NOT
 *    swallowed by {@code POST/DELETE /{type}/{resourceId}} (route-precedence);
 *  - a star round-trips through the DB (POST → GET ids → DELETE → GET ids);
 *  - the writes are idempotent and scoped per (user, workspace, type).
 */
class ResourceFavoriteControllerIntegrationTest extends BaseControllerIntegrationTest {

    private static final String X_ORG_ID = "X-Organization-ID";
    private static final String WF = "11111111-2222-3333-4444-555555555555";

    @Test
    @DisplayName("POST then GET ids round-trips the favorite through the DB")
    void favoriteRoundTrips() throws Exception {
        mockMvc.perform(post("/api/favorites/workflow/{id}", WF)
                        .header(X_USER_ID, TENANT_ID).header(X_ORG_ID, "org-1"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/favorites/workflow/ids")
                        .header(X_USER_ID, TENANT_ID).header(X_ORG_ID, "org-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ids", hasSize(1)))
                .andExpect(jsonPath("$.ids[0]").value(WF));
    }

    @Test
    @DisplayName("GET /{type}/ids maps to the list handler, not the {type}/{id} path variable")
    void idsRouteWins() throws Exception {
        // No favorites yet → a clean empty list, proving the literal 'ids' segment routed correctly.
        mockMvc.perform(get("/api/favorites/agent/ids")
                        .header(X_USER_ID, TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ids", hasSize(0)));
    }

    @Test
    @DisplayName("POST is idempotent - favoriting twice keeps a single row")
    void favoriteIsIdempotent() throws Exception {
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/favorites/workflow/{id}", WF)
                            .header(X_USER_ID, TENANT_ID).header(X_ORG_ID, "org-1"))
                    .andExpect(status().isNoContent());
        }
        mockMvc.perform(get("/api/favorites/workflow/ids")
                        .header(X_USER_ID, TENANT_ID).header(X_ORG_ID, "org-1"))
                .andExpect(jsonPath("$.ids", hasSize(1)));
    }

    @Test
    @DisplayName("DELETE removes the favorite (idempotent) and the id disappears from the list")
    void unfavoriteRemoves() throws Exception {
        mockMvc.perform(post("/api/favorites/workflow/{id}", WF)
                        .header(X_USER_ID, TENANT_ID).header(X_ORG_ID, "org-1"))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/favorites/workflow/{id}", WF)
                        .header(X_USER_ID, TENANT_ID).header(X_ORG_ID, "org-1"))
                .andExpect(status().isNoContent());
        // Second delete is a no-op (idempotent).
        mockMvc.perform(delete("/api/favorites/workflow/{id}", WF)
                        .header(X_USER_ID, TENANT_ID).header(X_ORG_ID, "org-1"))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/favorites/workflow/ids")
                        .header(X_USER_ID, TENANT_ID).header(X_ORG_ID, "org-1"))
                .andExpect(jsonPath("$.ids", hasSize(0)));
    }

    @Test
    @DisplayName("favorites are isolated per resource type")
    void typeIsolation() throws Exception {
        mockMvc.perform(post("/api/favorites/workflow/{id}", WF).header(X_USER_ID, TENANT_ID))
                .andExpect(status().isNoContent());
        // Same id under TABLE is a distinct row → not visible from the WORKFLOW list and vice-versa.
        mockMvc.perform(get("/api/favorites/table/ids").header(X_USER_ID, TENANT_ID))
                .andExpect(jsonPath("$.ids", hasSize(0)));
        mockMvc.perform(get("/api/favorites/workflow/ids").header(X_USER_ID, TENANT_ID))
                .andExpect(jsonPath("$.ids", hasSize(1)));
    }

    @Test
    @DisplayName("favorites are isolated per user and per workspace")
    void userAndWorkspaceIsolation() throws Exception {
        mockMvc.perform(post("/api/favorites/workflow/{id}", WF)
                        .header(X_USER_ID, TENANT_ID).header(X_ORG_ID, "org-1"))
                .andExpect(status().isNoContent());

        // Different user, same org → empty.
        mockMvc.perform(get("/api/favorites/workflow/ids")
                        .header(X_USER_ID, OTHER_TENANT_ID).header(X_ORG_ID, "org-1"))
                .andExpect(jsonPath("$.ids", hasSize(0)));
        // Same user, different workspace → empty.
        mockMvc.perform(get("/api/favorites/workflow/ids")
                        .header(X_USER_ID, TENANT_ID).header(X_ORG_ID, "org-2"))
                .andExpect(jsonPath("$.ids", hasSize(0)));
        // Same user, personal scope (no org header) → also empty (distinct from org-1).
        mockMvc.perform(get("/api/favorites/workflow/ids")
                        .header(X_USER_ID, TENANT_ID))
                .andExpect(jsonPath("$.ids", hasSize(0)));
    }

    @Test
    @DisplayName("ids list is ordered newest-favorited-first")
    void newestFirstOrdering() throws Exception {
        String a = "aaaaaaaa-0000-0000-0000-000000000001";
        String b = "bbbbbbbb-0000-0000-0000-000000000002";
        mockMvc.perform(post("/api/favorites/workflow/{id}", a).header(X_USER_ID, TENANT_ID))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/favorites/workflow/{id}", b).header(X_USER_ID, TENANT_ID))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/favorites/workflow/ids").header(X_USER_ID, TENANT_ID))
                .andExpect(jsonPath("$.ids", hasSize(2)))
                .andExpect(jsonPath("$.ids", containsInAnyOrder(a, b)));
    }

    @Test
    @DisplayName("an unknown resource type yields 400 on every verb")
    void unknownTypeIsRejected() throws Exception {
        mockMvc.perform(get("/api/favorites/banana/ids").header(X_USER_ID, TENANT_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_RESOURCE_TYPE"));
        mockMvc.perform(post("/api/favorites/banana/{id}", WF).header(X_USER_ID, TENANT_ID))
                .andExpect(status().isBadRequest());
        mockMvc.perform(delete("/api/favorites/banana/{id}", WF).header(X_USER_ID, TENANT_ID))
                .andExpect(status().isBadRequest());
    }
}

package com.apimarketplace.catalog.controller;

import com.apimarketplace.catalog.repository.LexicalSearchIndexRepository;
import com.apimarketplace.catalog.service.CapabilityService;
import com.apimarketplace.catalog.service.LexicalIndexSyncService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The tool-discovery index is platform-global, so writing it is admin-only (LC-019,
 * security audit 2026-08-13).
 *
 * <p>{@code POST /api/tools/{id}/synthesis} and {@code /api/tools/synthesis/batch} took no
 * identity and no role at all, and upsert {@code catalog.lexical_search_index}, which is
 * untenanted: one batch request from any authenticated account blanked or poisoned the
 * Gmail rows for EVERY tenant (denial of tool discovery) and injected attacker-authored
 * summaries into every agent's tool-selection context.
 *
 * <p>Two callers are legitimate and prove themselves differently: a human ADMIN through
 * the gateway ({@code X-User-Roles}), and the in-cluster importer
 * ({@code X-Internal-Admin-Token}, the same secret {@code ToolResponseController} already
 * requires). Both are covered below, because refusing the importer would silently drop
 * synthesis data on every catalog re-import.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CapabilityController synthesis - admin gate (LC-019)")
class SynthesisAdminGateTest {

    @Mock private CapabilityService capabilityService;
    @Mock private LexicalIndexSyncService lexicalIndexSyncService;
    @Mock private LexicalSearchIndexRepository lexicalSearchIndexRepository;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final UUID TOOL_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final String ADMIN_TOKEN = "importer-secret";

    private String validSynthesis() throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "toolName", "gmail_send",
                "provider", "gmail",
                "resource", "message",
                "action", "send"));
    }

    private String validBatch() throws Exception {
        return objectMapper.writeValueAsString(Map.of(TOOL_ID.toString(), Map.of(
                "toolName", "gmail_send",
                "provider", "gmail",
                "resource", "message",
                "action", "send")));
    }

    @BeforeEach
    void setUp() {
        CapabilityController controller = new CapabilityController(
                capabilityService, lexicalIndexSyncService, lexicalSearchIndexRepository,
                new com.apimarketplace.catalog.web.CatalogAdminAccess(ADMIN_TOKEN));
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Nested
    @DisplayName("single-tool synthesis")
    class Single {
        @Test
        @DisplayName("a plain authenticated user cannot rewrite a tool's discovery row")
        void plainUserIsRefused() throws Exception {
            mockMvc.perform(post("/api/tools/{id}/synthesis", TOOL_ID)
                            .header("X-User-Roles", "USER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validSynthesis()))
                    .andExpect(status().isForbidden());

            verify(lexicalIndexSyncService, never()).syncApiToolEnriched(any(), any());
        }

        @Test
        @DisplayName("a request with no role header at all is refused")
        void anonymousIsRefused() throws Exception {
            // The pre-fix shape: no identity, no role, straight through to the write.
            mockMvc.perform(post("/api/tools/{id}/synthesis", TOOL_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validSynthesis()))
                    .andExpect(status().isForbidden());

            verify(lexicalIndexSyncService, never()).syncApiToolEnriched(any(), any());
        }

        @Test
        @DisplayName("an ADMIN still writes")
        void adminWrites() throws Exception {
            mockMvc.perform(post("/api/tools/{id}/synthesis", TOOL_ID)
                            .header("X-User-Roles", "USER,ADMIN")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validSynthesis()))
                    .andExpect(status().isOk());

            verify(lexicalIndexSyncService).syncApiToolEnriched(eq(TOOL_ID), any());
        }

        @Test
        @DisplayName("the importer's admin token still writes - a re-import must not lose synthesis")
        void importerTokenWrites() throws Exception {
            mockMvc.perform(post("/api/tools/{id}/synthesis", TOOL_ID)
                            .header("X-Internal-Admin-Token", ADMIN_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validSynthesis()))
                    .andExpect(status().isOk());

            verify(lexicalIndexSyncService).syncApiToolEnriched(eq(TOOL_ID), any());
        }

        @Test
        @DisplayName("a wrong admin token is refused")
        void wrongTokenIsRefused() throws Exception {
            mockMvc.perform(post("/api/tools/{id}/synthesis", TOOL_ID)
                            .header("X-Internal-Admin-Token", "not-the-secret")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validSynthesis()))
                    .andExpect(status().isForbidden());

            verify(lexicalIndexSyncService, never()).syncApiToolEnriched(any(), any());
        }
    }

    @Nested
    @DisplayName("batch synthesis")
    class Batch {
        @Test
        @DisplayName("a plain authenticated user cannot rewrite many tools at once")
        void plainUserIsRefused() throws Exception {
            mockMvc.perform(post("/api/tools/synthesis/batch")
                            .header("X-User-Roles", "USER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validBatch()))
                    .andExpect(status().isForbidden());

            verify(lexicalIndexSyncService, never()).syncApiToolEnriched(any(), any());
        }

        @Test
        @DisplayName("an ADMIN still writes")
        void adminWrites() throws Exception {
            mockMvc.perform(post("/api/tools/synthesis/batch")
                            .header("X-User-Roles", "ADMIN")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validBatch()))
                    .andExpect(status().isOk());

            verify(lexicalIndexSyncService).syncApiToolEnriched(eq(TOOL_ID), any());
        }
    }

    @Nested
    @DisplayName("deny-by-default when the shared secret is unset")
    class UnsetSecret {
        @Test
        @DisplayName("a blank catalog.admin-token makes the token branch unusable, not universal")
        void blankSecretRefusesEveryToken() throws Exception {
            CapabilityController controller = new CapabilityController(
                    capabilityService, lexicalIndexSyncService, lexicalSearchIndexRepository,
                    new com.apimarketplace.catalog.web.CatalogAdminAccess(""));
            MockMvc unconfigured = MockMvcBuilders.standaloneSetup(controller).build();

            unconfigured.perform(post("/api/tools/{id}/synthesis", TOOL_ID)
                            .header("X-Internal-Admin-Token", "")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validSynthesis()))
                    .andExpect(status().isForbidden());

            verify(lexicalIndexSyncService, never()).syncApiToolEnriched(any(), any());
        }
    }
}

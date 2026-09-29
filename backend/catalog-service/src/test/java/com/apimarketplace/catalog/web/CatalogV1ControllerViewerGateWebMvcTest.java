package com.apimarketplace.catalog.web;

import com.apimarketplace.catalog.domain.dto.ToolExecutionResponse;
import com.apimarketplace.catalog.service.CatalogV1Service;
import com.apimarketplace.catalog.service.execution.MockToolExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression: POST /catalog/v1/tools/{id}/execute and /tools/{api}/{tool}/execute ran a
 * third-party action with the workspace credentials for a read-only VIEWER. Both now refuse
 * the VIEWER role before any lookup or execution.
 */
@DisplayName("CatalogV1Controller execute refuses the workspace VIEWER role")
class CatalogV1ControllerViewerGateWebMvcTest {

    private CatalogV1Service service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(CatalogV1Service.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                new CatalogV1Controller(service, mock(MockToolExecutionService.class))).build();
    }

    @Test
    @DisplayName("VIEWER: /tools/{id}/execute answers 403 ROLE_READ_ONLY and executes nothing")
    void viewerRefusedById() throws Exception {
        mockMvc.perform(post("/catalog/v1/tools/tool-1/execute").contentType(MediaType.APPLICATION_JSON)
                        .content("{}").header("X-User-ID", "7").header("X-Organization-ID", "org-1")
                        .header("X-Organization-Role", "VIEWER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ROLE_READ_ONLY"));
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("VIEWER: /tools/{apiSlug}/{toolSlug}/execute answers 403 and executes nothing")
    void viewerRefusedBySlug() throws Exception {
        mockMvc.perform(post("/catalog/v1/tools/gmail/send/execute").contentType(MediaType.APPLICATION_JSON)
                        .content("{}").header("X-User-ID", "7").header("X-Organization-ID", "org-1")
                        .header("X-Organization-Role", "VIEWER"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("MEMBER, and a request with no role (internal / share visitor), still execute")
    void memberAndNoRoleExecute() throws Exception {
        when(service.executeTool(anyString(), any(), anyString(), any(), anyString()))
                .thenReturn(ToolExecutionResponse.builder().build());

        mockMvc.perform(post("/catalog/v1/tools/tool-1/execute").contentType(MediaType.APPLICATION_JSON)
                        .content("{}").header("X-User-ID", "7").header("X-Organization-ID", "org-1")
                        .header("X-Organization-Role", "MEMBER"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/catalog/v1/tools/tool-2/execute").contentType(MediaType.APPLICATION_JSON)
                        .content("{}").header("X-User-ID", "7").header("X-Organization-ID", "org-1"))
                .andExpect(status().isOk());

        verify(service).executeTool(eq("tool-1"), any(), eq("7"), eq("org-1"), anyString());
        verify(service).executeTool(eq("tool-2"), any(), eq("7"), eq("org-1"), anyString());
    }
}

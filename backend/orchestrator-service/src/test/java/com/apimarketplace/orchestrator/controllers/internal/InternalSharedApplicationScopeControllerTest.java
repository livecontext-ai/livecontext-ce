package com.apimarketplace.orchestrator.controllers.internal;

import com.apimarketplace.orchestrator.services.SharedApplicationScopeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("InternalSharedApplicationScopeController - share-scope checks for other services")
class InternalSharedApplicationScopeControllerTest {

    private static final UUID PUB = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID IFACE = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID FILE = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String BASE = "/api/internal/orchestrator/share-scope";

    @Mock private SharedApplicationScopeService scopeService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new InternalSharedApplicationScopeController(scopeService)).build();
    }

    @Test
    @DisplayName("interfaces: answers the service verdict for (publication, workspace, interface)")
    void interfaceVerdict() throws Exception {
        when(scopeService.interfaceBelongsToApplication(PUB, "org-1", IFACE)).thenReturn(true);

        mockMvc.perform(get(BASE + "/interfaces/" + IFACE).param("publicationId", PUB.toString())
                        .param("organizationId", "org-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true));
    }

    @Test
    @DisplayName("interfaces: a tenant parameter cannot widen the check (the workspace is the scope)")
    void interfaceIgnoresTenant() throws Exception {
        when(scopeService.interfaceBelongsToApplication(PUB, "org-1", IFACE)).thenReturn(false);

        mockMvc.perform(get(BASE + "/interfaces/" + IFACE).param("publicationId", PUB.toString())
                        .param("organizationId", "org-1").param("tenantId", "someone-else"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false));
        verify(scopeService).interfaceBelongsToApplication(PUB, "org-1", IFACE);
    }

    @Test
    @DisplayName("files: forwards run, workflow and the parsed file id")
    void fileVerdictForwardsFileId() throws Exception {
        when(scopeService.fileBelongsToApplication(PUB, "owner-1", "org-1", "", "", FILE)).thenReturn(true);

        mockMvc.perform(get(BASE + "/files").param("publicationId", PUB.toString())
                        .param("tenantId", "owner-1").param("organizationId", "org-1")
                        .param("runId", "").param("workflowId", "").param("fileId", FILE.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true));
    }

    @Test
    @DisplayName("files: a malformed file id is treated as absent, never as a match")
    void malformedFileIdIsAbsent() throws Exception {
        when(scopeService.fileBelongsToApplication(PUB, "owner-1", "org-1", "run_x", null, null)).thenReturn(false);

        mockMvc.perform(get(BASE + "/files").param("publicationId", PUB.toString())
                        .param("tenantId", "owner-1").param("organizationId", "org-1")
                        .param("runId", "run_x").param("fileId", "not-a-uuid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false));
        verify(scopeService).fileBelongsToApplication(PUB, "owner-1", "org-1", "run_x", null, null);
    }

    @Test
    @DisplayName("missing or malformed publication id: 400, the service is never asked")
    void missingPublicationIs400() throws Exception {
        mockMvc.perform(get(BASE + "/files").param("fileId", FILE.toString()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(BASE + "/interfaces/" + IFACE).param("publicationId", "nope"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(scopeService);
    }
}

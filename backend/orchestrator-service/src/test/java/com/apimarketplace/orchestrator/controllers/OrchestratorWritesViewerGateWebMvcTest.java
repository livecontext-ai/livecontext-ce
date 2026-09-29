package com.apimarketplace.orchestrator.controllers;

import com.apimarketplace.orchestrator.controllers.project.ProjectController;
import com.apimarketplace.orchestrator.controllers.storage.StorageExplorerController;
import com.apimarketplace.orchestrator.controllers.workflow.WorkflowRunQueryController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression (MockMvc, real header binding) for three orchestrator writes that ignored the
 * role: PUT /api/workflows/storage/{id} (overwrites a storage row), POST
 * /api/storage/explorer/folders (received X-Organization-Role but never checked it) and
 * POST /api/projects. A VIEWER is now refused before any service is reached; a MEMBER is not.
 */
@DisplayName("Orchestrator writes refuse the X-Organization-Role VIEWER header")
class OrchestratorWritesViewerGateWebMvcTest {

    private final List<Object> deps = new ArrayList<>();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.standaloneSetup(
                build(WorkflowRunQueryController.class),
                build(StorageExplorerController.class),
                build(ProjectController.class)).build();
    }

    private <T> T build(Class<T> type) throws Exception {
        Constructor<?> ctor = Arrays.stream(type.getConstructors())
                .max(Comparator.comparingInt(Constructor::getParameterCount)).orElseThrow();
        Object[] args = Arrays.stream(ctor.getParameterTypes()).map(t -> {
            Object m = mock(t);
            deps.add(m);
            return m;
        }).toArray();
        return type.cast(ctor.newInstance(args));
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String role) {
        return b.header("X-User-ID", "7").header("X-Organization-ID", "org-1")
                .header("X-Organization-Role", role).contentType(MediaType.APPLICATION_JSON);
    }

    @Test
    @DisplayName("VIEWER: storage row update, folder create and project create are 403 and reach no service")
    void viewerRefused() throws Exception {
        mockMvc.perform(as(put("/api/workflows/storage/5b0f5c1e-4d3a-4c55-9d7e-3f6f0a2b9c11"), "VIEWER")
                .content("{\"data\":{}}")).andExpect(status().isForbidden());
        mockMvc.perform(as(post("/api/storage/explorer/folders"), "VIEWER")
                .content("{\"name\":\"x\"}")).andExpect(status().isForbidden());
        mockMvc.perform(as(post("/api/projects"), "VIEWER")
                .content("{\"name\":\"p\"}")).andExpect(status().isForbidden());
        verifyNoInteractions(deps.toArray());
    }

    @Test
    @DisplayName("MEMBER: the storage row update is not refused by the role gate (404: no such row)")
    void memberNotRefused() throws Exception {
        mockMvc.perform(as(put("/api/workflows/storage/5b0f5c1e-4d3a-4c55-9d7e-3f6f0a2b9c11"), "MEMBER")
                .content("{\"data\":{}}")).andExpect(r -> assertThat(r.getResponse().getStatus()).isNotEqualTo(403));
    }
}

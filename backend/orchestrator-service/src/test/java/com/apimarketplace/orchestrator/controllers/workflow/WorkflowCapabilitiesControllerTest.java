package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.orchestrator.services.OptionalFeatureCapabilityService;
import com.apimarketplace.orchestrator.services.OptionalFeatureCapabilityService.FeatureCapabilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkflowCapabilitiesControllerTest {

    @Test
    @DisplayName("GET /api/workflows/capabilities returns the service verdict for the X-User-ID tenant")
    void returnsServiceVerdict() {
        OptionalFeatureCapabilityService service = mock(OptionalFeatureCapabilityService.class);
        when(service.resolve("tenant-1")).thenReturn(new FeatureCapabilities(false, true, true));
        WorkflowCapabilitiesController controller = new WorkflowCapabilitiesController(service);

        ResponseEntity<FeatureCapabilities> response = controller.getCapabilities("tenant-1");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isEqualTo(new FeatureCapabilities(false, true, true));
    }

    @Test
    @DisplayName("missing X-User-ID header still resolves (gate fail-closes on browsing, renderer is global)")
    void missingHeaderStillResolves() {
        OptionalFeatureCapabilityService service = mock(OptionalFeatureCapabilityService.class);
        when(service.resolve(null)).thenReturn(new FeatureCapabilities(true, false, false));
        WorkflowCapabilitiesController controller = new WorkflowCapabilitiesController(service);

        ResponseEntity<FeatureCapabilities> response = controller.getCapabilities(null);

        assertThat(response.getBody()).isEqualTo(new FeatureCapabilities(true, false, false));
    }

    /**
     * Stand-in for the sibling {@code /api/workflows/{workflowId}} template mappings
     * (WorkflowListController etc.) - pins that Spring's mapping comparator routes the
     * literal {@code /capabilities} segment to the capabilities controller, never to a
     * path-variable sibling that would try to parse "capabilities" as a workflow id.
     */
    @RestController
    @RequestMapping("/api/workflows")
    static class PathVariableSiblingController {
        @GetMapping("/{workflowId}")
        public ResponseEntity<String> getWorkflow(@PathVariable("workflowId") String workflowId) {
            return ResponseEntity.ok("workflow:" + workflowId);
        }
    }

    @Test
    @DisplayName("literal /api/workflows/capabilities wins over the sibling /{workflowId} template mapping")
    void literalPathBeatsPathVariableSibling() throws Exception {
        OptionalFeatureCapabilityService service = mock(OptionalFeatureCapabilityService.class);
        when(service.resolve("tenant-1")).thenReturn(new FeatureCapabilities(false, true, true));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                new WorkflowCapabilitiesController(service),
                new PathVariableSiblingController()
        ).build();

        mockMvc.perform(get("/api/workflows/capabilities").header("X-User-ID", "tenant-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.screenshotRenderer").value(false))
                .andExpect(jsonPath("$.browserAgent").value(true))
                .andExpect(jsonPath("$.webSearch").value(true));

        mockMvc.perform(get("/api/workflows/some-workflow-id"))
                .andExpect(status().isOk())
                .andExpect(content().string("workflow:some-workflow-id"));
    }
}

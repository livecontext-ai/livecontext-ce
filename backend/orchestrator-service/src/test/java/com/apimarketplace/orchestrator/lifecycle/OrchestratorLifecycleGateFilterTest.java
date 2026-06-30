package com.apimarketplace.orchestrator.lifecycle;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for the {@link OrchestratorLifecycleGateFilter} path patterns.
 *
 * <p>All URIs in this class mirror the REAL prod mounts under {@code /api/v2/workflows/dag/*}:
 * <ul>
 *   <li>{@code WorkflowExecutionController} - {@code POST /execute},
 *       {@code POST /{workflowId}/runs/{runId}/start}</li>
 *   <li>{@code WorkflowRunController} - {@code POST /runs/{runId}/resume},
 *       {@code POST /runs/{runId}/rerun/{stepId}}</li>
 *   <li>{@code StepByStepController} - {@code POST /runs/{runId}/step/{stepId}/execute},
 *       {@code POST /runs/{runId}/start-step-by-step},
 *       {@code POST /runs/{runId}/core/{coreId}/execute}</li>
 * </ul>
 *
 * <p>Audit 2026-05-23 caught the prior version of this test asserting against fictional
 * {@code /api/workflows/...} paths (off by an {@code v2/dag/} prefix) - the filter was
 * silently inert in prod. Tests now use real URIs; a refactor of the controller mounts
 * MUST update both the filter pattern list and this test.
 */
@ExtendWith(MockitoExtension.class)
class OrchestratorLifecycleGateFilterTest {

    @Mock private OrchestratorLifecycleGate gate;
    @Mock private FilterChain chain;

    private OrchestratorLifecycleGateFilter filter;

    @BeforeEach
    void setUp() {
        filter = new OrchestratorLifecycleGateFilter(gate);
    }

    @Test
    @DisplayName("postStartReturns503WhenDrainingWithRetryAfterHeader: planned shutdown refuses new in-flight work (WorkflowExecutionController POST /{workflowId}/runs/{runId}/start)")
    void postStartReturns503WhenDraining() throws Exception {
        when(gate.isDraining()).thenReturn(true);
        MockHttpServletRequest req = mockPost("/api/v2/workflows/dag/abc-123/runs/run-1/start");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        ReflectionTestUtils.invokeMethod(filter, "doFilterInternal", req, resp, chain);

        assertThat(resp.getStatus()).isEqualTo(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        assertThat(resp.getHeader("Retry-After")).isEqualTo("10");
        assertThat(resp.getContentAsString()).contains("orchestrator_draining");
        verify(chain, never()).doFilter(req, resp);
    }

    @Test
    @DisplayName("postExecuteReturns503WhenDraining: legacy WorkflowExecutionController POST /execute also grows in-flight, refused")
    void postExecuteReturns503WhenDraining() throws Exception {
        when(gate.isDraining()).thenReturn(true);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        ReflectionTestUtils.invokeMethod(filter, "doFilterInternal",
            mockPost("/api/v2/workflows/dag/execute"), resp, chain);

        assertThat(resp.getStatus()).isEqualTo(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("postResumeReturns503WhenDraining: WorkflowRunController POST /runs/{runId}/resume")
    void postResumeReturns503WhenDraining() throws Exception {
        when(gate.isDraining()).thenReturn(true);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        ReflectionTestUtils.invokeMethod(filter, "doFilterInternal",
            mockPost("/api/v2/workflows/dag/runs/run-1/resume"), resp, chain);

        assertThat(resp.getStatus()).isEqualTo(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("postRerunReturns503WhenDraining: WorkflowRunController POST /runs/{runId}/rerun/{stepId}")
    void postRerunReturns503WhenDraining() throws Exception {
        when(gate.isDraining()).thenReturn(true);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        ReflectionTestUtils.invokeMethod(filter, "doFilterInternal",
            mockPost("/api/v2/workflows/dag/runs/run-1/rerun/mcp:step1"), resp, chain);

        assertThat(resp.getStatus()).isEqualTo(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("postStepExecuteReturns503WhenDraining: StepByStepController POST /runs/{runId}/step/{stepId}/execute")
    void postStepExecuteReturns503WhenDraining() throws Exception {
        when(gate.isDraining()).thenReturn(true);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        ReflectionTestUtils.invokeMethod(filter, "doFilterInternal",
            mockPost("/api/v2/workflows/dag/runs/run-1/step/mcp:step1/execute"), resp, chain);

        assertThat(resp.getStatus()).isEqualTo(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("postStartStepByStepReturns503WhenDraining: StepByStepController POST /runs/{runId}/start-step-by-step")
    void postStartStepByStepReturns503WhenDraining() throws Exception {
        when(gate.isDraining()).thenReturn(true);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        ReflectionTestUtils.invokeMethod(filter, "doFilterInternal",
            mockPost("/api/v2/workflows/dag/runs/run-1/start-step-by-step"), resp, chain);

        assertThat(resp.getStatus()).isEqualTo(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("postStepByStepExecuteReturns503WhenDraining: StepByStepController POST /runs/{runId}/step-by-step/{stepId}/execute (8th gated pattern)")
    void postStepByStepExecuteReturns503WhenDraining() throws Exception {
        when(gate.isDraining()).thenReturn(true);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        ReflectionTestUtils.invokeMethod(filter, "doFilterInternal",
            mockPost("/api/v2/workflows/dag/runs/run-1/step-by-step/mcp:step1/execute"), resp, chain);

        assertThat(resp.getStatus()).isEqualTo(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("postCoreExecuteReturns503WhenDraining: StepByStepController POST /runs/{runId}/core/{coreId}/execute")
    void postCoreExecuteReturns503WhenDraining() throws Exception {
        when(gate.isDraining()).thenReturn(true);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        ReflectionTestUtils.invokeMethod(filter, "doFilterInternal",
            mockPost("/api/v2/workflows/dag/runs/run-1/core/core:loop1/execute"), resp, chain);

        assertThat(resp.getStatus()).isEqualTo(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("postCancelPassesThroughWhenDraining: cancel reduces in-flight set, must remain available during drain")
    void postCancelPassesThroughWhenDraining() throws Exception {
        when(gate.isDraining()).thenReturn(true);
        MockHttpServletRequest req = mockPost("/api/v2/workflows/dag/runs/run-1/cancel");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        ReflectionTestUtils.invokeMethod(filter, "doFilterInternal", req, resp, chain);

        verify(chain).doFilter(req, resp);
        assertThat(resp.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    @DisplayName("getRunStatusPassesThroughWhenDraining: read paths stay responsive for frontend polling during drain")
    void getRunStatusPassesThrough() throws Exception {
        when(gate.isDraining()).thenReturn(true);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v2/workflows/dag/runs/run-1");
        req.setRequestURI("/api/v2/workflows/dag/runs/run-1");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        ReflectionTestUtils.invokeMethod(filter, "doFilterInternal", req, resp, chain);

        verify(chain).doFilter(req, resp);
    }

    @Test
    @DisplayName("postStartPassesThroughWhenReady: only DRAINING triggers the 503; READY/WARMING let the request through")
    void postStartPassesThroughWhenReady() throws Exception {
        when(gate.isDraining()).thenReturn(false);
        MockHttpServletRequest req = mockPost("/api/v2/workflows/dag/abc-123/runs/run-1/start");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        ReflectionTestUtils.invokeMethod(filter, "doFilterInternal", req, resp, chain);

        verify(chain).doFilter(req, resp);
    }

    private static MockHttpServletRequest mockPost(String uri) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", uri);
        req.setRequestURI(uri);
        return req;
    }
}

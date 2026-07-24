package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MockRunGate} - run-level mock-mode resolution with the
 * editor-run hard guard and fail-closed (OFF) semantics.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MockRunGate - run-level mock mode resolution")
class MockRunGateTest {

    @Mock private WorkflowRunRepository runRepository;

    private MockRunGate gate;

    @BeforeEach
    void setUp() {
        gate = new MockRunGate(runRepository);
    }

    private void stubRun(String runId, Map<String, Object> metadata) {
        WorkflowRunEntity run = mock(WorkflowRunEntity.class);
        when(run.getMetadata()).thenReturn(metadata);
        when(runRepository.findByRunIdPublic(runId)).thenReturn(Optional.of(run));
    }

    @Test
    @DisplayName("editor run without __mockMode__ resolves to DEFAULT (enabled node mocks apply)")
    void editorRunDefaultsToDefault() {
        stubRun("run-1", Map.of("__editorRun__", true));

        assertThat(gate.mode("run-1")).isEqualTo(MockRunGate.MockRunMode.DEFAULT);
        assertThat(gate.mode("run-1").isMockingEnabled()).isTrue();
    }

    @Test
    @DisplayName("__mockMode__=off resolves to OFF; __mockMode__=all_mcp resolves to ALL_MCP (case/space tolerant)")
    void explicitOverridesResolve() {
        stubRun("run-off", Map.of("__editorRun__", true, "__mockMode__", " OFF "));
        stubRun("run-all", Map.of("__editorRun__", true, "__mockMode__", "All_Mcp"));

        assertThat(gate.mode("run-off")).isEqualTo(MockRunGate.MockRunMode.OFF);
        assertThat(gate.mode("run-all")).isEqualTo(MockRunGate.MockRunMode.ALL_MCP);
    }

    @Test
    @DisplayName("Regression 2026-07-21: the PRODUCTION run never mocks, even though promotion left __editorRun__ + __mockMode__ on it")
    void productionRunNeverMocks() {
        // Pinning PROMOTES an existing run (usually the editor run the user tested
        // with) and strips neither flag - so the metadata alone says "mock". The FK
        // identity must win: production fires never mock.
        stubRun("promoted-prod", Map.of("__editorRun__", true, "__mockMode__", "all_mcp"));
        when(runRepository.isProductionRunByRunIdPublic("promoted-prod")).thenReturn(true);

        assertThat(gate.mode("promoted-prod")).isEqualTo(MockRunGate.MockRunMode.OFF);
    }

    @Test
    @DisplayName("the production check runs ONLY on the non-OFF path - flag-less runs stay one query")
    void productionCheckSkippedOnOffPath() {
        stubRun("plain-run", new HashMap<>());

        assertThat(gate.mode("plain-run")).isEqualTo(MockRunGate.MockRunMode.OFF);
        verify(runRepository, org.mockito.Mockito.never()).isProductionRunByRunIdPublic("plain-run");
    }

    @Test
    @DisplayName("HARD GUARD: a run without __editorRun__=true is always OFF, even with __mockMode__ set")
    void nonEditorRunIsAlwaysOff() {
        stubRun("prod-run", Map.of("__mockMode__", "all_mcp"));
        stubRun("no-meta-run", new HashMap<>());

        assertThat(gate.mode("prod-run")).isEqualTo(MockRunGate.MockRunMode.OFF);
        assertThat(gate.mode("no-meta-run")).isEqualTo(MockRunGate.MockRunMode.OFF);
    }

    @Test
    @DisplayName("fail-closed: unknown run, null run id, or repository error resolve to OFF")
    void failClosedToOff() {
        when(runRepository.findByRunIdPublic("ghost")).thenReturn(Optional.empty());
        when(runRepository.findByRunIdPublic("boom")).thenThrow(new IllegalStateException("db down"));

        assertThat(gate.mode("ghost")).isEqualTo(MockRunGate.MockRunMode.OFF);
        assertThat(gate.mode("boom")).isEqualTo(MockRunGate.MockRunMode.OFF);
        assertThat(gate.mode(null)).isEqualTo(MockRunGate.MockRunMode.OFF);
        assertThat(gate.mode("  ")).isEqualTo(MockRunGate.MockRunMode.OFF);
    }

    @Test
    @DisplayName("mode is cached per run id (one repository read) and invalidate() forces a re-read")
    void cachedAndInvalidatable() {
        stubRun("run-1", Map.of("__editorRun__", true));

        gate.mode("run-1");
        gate.mode("run-1");
        verify(runRepository, times(1)).findByRunIdPublic("run-1");

        gate.invalidate("run-1");
        gate.mode("run-1");
        verify(runRepository, times(2)).findByRunIdPublic("run-1");
    }

    @Test
    @DisplayName("unknown __mockMode__ values fall back to DEFAULT on an editor run (lenient forward compatibility)")
    void unknownModeFallsBackToDefault() {
        stubRun("run-1", Map.of("__editorRun__", true, "__mockMode__", "something_new"));

        assertThat(gate.mode("run-1")).isEqualTo(MockRunGate.MockRunMode.DEFAULT);
    }
}

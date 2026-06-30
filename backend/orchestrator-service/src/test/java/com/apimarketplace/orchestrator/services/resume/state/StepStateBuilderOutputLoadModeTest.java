package com.apimarketplace.orchestrator.services.resume.state;

import com.apimarketplace.orchestrator.domain.WorkflowStepDataEntity;
import com.apimarketplace.orchestrator.domain.workflow.*;
import com.apimarketplace.orchestrator.services.resume.WorkflowRunState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Verifies that {@link StepStateBuilder} respects {@link StateReconstructor.OutputLoadMode}.
 *
 * <p>FULL: every alias type dereferences output blob via {@code helper.loadStepOutput}.
 * <p>AGENT_AND_INTERFACE_ONLY: only {@code agent:} + {@code interface:} aliases load output;
 * {@code mcp:}, {@code trigger:}, {@code core:}, {@code table:} aliases get {@code output==null}.
 *
 * <p>This test exercises mcp + trigger + interface aliases - covers the two behaviours
 * (skip-in-lean and always-load) without instantiating Agent/Core records (47+ fields).
 * Method-level filtering in StepStateBuilder is symmetric across processStepNodes /
 * processTriggerNodes / processCores / processTableNodes (all gated by the same boolean),
 * so the mcp + trigger coverage proves the contract for the other skip-eligible types too.
 *
 * <p>Why it matters: on long runs with many epochs, the FULL path pays N storage round-trips
 * via {@code helper.loadStepOutput} (StorageService.getByIdReadOnly per COMPLETED entity).
 * The lean variant collapses this to ≤ (#agents + #interfaces) round-trips on the REST path.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("StepStateBuilder OutputLoadMode")
class StepStateBuilderOutputLoadModeTest {

    @Mock private StateReconstructorHelper helper;
    @Mock private StatusCountsBuilder statusCountsBuilder;
    @Mock private ExecutionGraph graph;
    @Mock private WorkflowPlan plan;

    private StepStateBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new StepStateBuilder(helper, statusCountsBuilder);
        when(helper.determineStepStatus(anyString(), any(), any(), any(), any()))
            .thenReturn(RunStatus.COMPLETED);
        when(statusCountsBuilder.getStatusCountsMap(anyString(), anyString(), any()))
            .thenReturn(Map.of());
        when(graph.getDependencies(anyString())).thenReturn(Set.of());
        when(helper.loadStepOutput(any())).thenReturn(Map.of("payload", "loaded"));
        when(helper.calculateExecutionTime(any())).thenReturn(0L);

        when(plan.getMcps()).thenReturn(List.of(makeMcp()));
        when(plan.getTriggers()).thenReturn(List.of(makeTrigger()));
        when(plan.getAgents()).thenReturn(List.of());
        when(plan.getCores()).thenReturn(List.of());
        when(plan.getTables()).thenReturn(List.of());
        when(plan.getInterfaces()).thenReturn(List.of(makeInterface()));
    }

    @Test
    @DisplayName("Lean mode loads output for agent: alias (panel reads agent_config_snapshot at refresh)")
    void leanModeLoadsAgent() {
        // Override the default fixture: use only an agent step, no other types.
        when(plan.getMcps()).thenReturn(List.of());
        when(plan.getTriggers()).thenReturn(List.of());
        when(plan.getInterfaces()).thenReturn(List.of());
        when(plan.getAgents()).thenReturn(List.of(makeAgent()));

        Map<String, List<WorkflowStepDataEntity>> stepsByAlias = new HashMap<>();
        stepsByAlias.put("a1", List.of(makeEntity()));

        List<WorkflowRunState.StepState> states = builder.buildStepStates(
            plan, graph, stepsByAlias,
            Set.of("agent:a1"), Set.of(), Set.of(), Set.of(),
            Map.of(), null, Map.of(), Map.of(), Set.of(),
            StateReconstructor.OutputLoadMode.AGENT_AND_INTERFACE_ONLY);

        // Agent must dereference its output blob even in lean mode - sidebar agent panel
        // reads agent_config_snapshot synchronously at first paint (WorkflowBuilder.tsx:917-953).
        verify(helper, times(1)).loadStepOutput(any());
        WorkflowRunState.StepState agentState = states.stream()
            .filter(s -> "agent:a1".equals(s.stepId())).findFirst().orElseThrow();
        assertNotNull(agentState.output(), "agent: output MUST be loaded in lean mode");
        assertEquals("loaded", agentState.output().get("payload"));
    }

    @Test
    @DisplayName("FULL mode loads output for mcp + trigger + interface (3 storage round-trips)")
    void fullModeLoadsAllAliases() {
        Map<String, List<WorkflowStepDataEntity>> stepsByAlias = makeEntities();

        List<WorkflowRunState.StepState> states = builder.buildStepStates(
            plan, graph, stepsByAlias,
            Set.of("mcp:m1", "trigger:t1", "interface:i1"),
            Set.of(), Set.of(), Set.of(), Map.of(), null, Map.of(), Map.of(), Set.of(),
            StateReconstructor.OutputLoadMode.FULL);

        verify(helper, times(3)).loadStepOutput(any());
        for (WorkflowRunState.StepState s : states) {
            assertNotNull(s.output(), "FULL mode must populate output for alias=" + s.stepId());
            assertEquals("loaded", s.output().get("payload"));
        }
    }

    @Test
    @DisplayName("AGENT_AND_INTERFACE_ONLY skips output for mcp/trigger; loads for interface")
    void leanModeOnlyLoadsAgentAndInterface() {
        Map<String, List<WorkflowStepDataEntity>> stepsByAlias = makeEntities();

        List<WorkflowRunState.StepState> states = builder.buildStepStates(
            plan, graph, stepsByAlias,
            Set.of("mcp:m1", "trigger:t1", "interface:i1"),
            Set.of(), Set.of(), Set.of(), Map.of(), null, Map.of(), Map.of(), Set.of(),
            StateReconstructor.OutputLoadMode.AGENT_AND_INTERFACE_ONLY);

        // Only interface dereferences storage; mcp + trigger skip.
        verify(helper, times(1)).loadStepOutput(any());

        Map<String, WorkflowRunState.StepState> byId = new HashMap<>();
        for (WorkflowRunState.StepState s : states) byId.put(s.stepId(), s);

        assertNull(byId.get("mcp:m1").output(), "mcp output must be null in lean mode");
        assertNull(byId.get("trigger:t1").output(), "trigger output must be null in lean mode");
        assertNotNull(byId.get("interface:i1").output(), "interface output MUST be loaded (modal reads it)");
    }

    @Test
    @DisplayName("Default overload (no mode arg) defaults to FULL - backwards compatible")
    void defaultOverloadDefaultsToFull() {
        Map<String, List<WorkflowStepDataEntity>> stepsByAlias = makeEntities();

        builder.buildStepStates(
            plan, graph, stepsByAlias,
            Set.of("mcp:m1", "trigger:t1", "interface:i1"),
            Set.of(), Set.of(), Set.of(), Map.of(), null, Map.of(), Map.of(), Set.of());

        verify(helper, times(3)).loadStepOutput(any());
    }

    @Test
    @DisplayName("Lean mode preserves entity column fields (httpStatus/errorMessage) - only skips storage blob")
    void leanModeKeepsEntityColumns() {
        Map<String, List<WorkflowStepDataEntity>> stepsByAlias = makeEntities();
        WorkflowStepDataEntity mcpEntity = stepsByAlias.get("m1").get(0);
        when(mcpEntity.getHttpStatus()).thenReturn(429);
        when(mcpEntity.getErrorMessage()).thenReturn("rate-limit");

        List<WorkflowRunState.StepState> states = builder.buildStepStates(
            plan, graph, stepsByAlias,
            Set.of("mcp:m1", "trigger:t1", "interface:i1"),
            Set.of(), Set.of(), Set.of(), Map.of(), null, Map.of(), Map.of(), Set.of(),
            StateReconstructor.OutputLoadMode.AGENT_AND_INTERFACE_ONLY);

        WorkflowRunState.StepState mcpState = states.stream()
            .filter(s -> "mcp:m1".equals(s.stepId())).findFirst().orElseThrow();
        assertNull(mcpState.output(), "lean mode strips output blob");
        assertEquals(429, mcpState.httpStatus(), "lean mode keeps httpStatus column");
        assertEquals("rate-limit", mcpState.errorMessage(), "lean mode keeps errorMessage column");
    }

    // ------------------------------------------------------------------------
    // Fixture helpers - real records (Step/Trigger/InterfaceDef are records, not mockable)
    // ------------------------------------------------------------------------

    private Step makeMcp() {
        // Step canonical: (id, type, label, parentLoopId, params, dataSourceId, crud, graphNodeId)
        return new Step("mcp-id", "mcp", "m1", null, Map.of(), null, null, null);
    }

    private Trigger makeTrigger() {
        // Trigger canonical: (id, label, strategy, type, params, chatMatch)
        return new Trigger("trigger-id", "t1", null, "manual", Map.of(), null);
    }

    private Agent makeAgent() {
        // Agent canonical: 21 fields. Use minimal valid values; defaults handle the rest.
        return new Agent(
            "agent-id", "agent", "a1",
            null,                  // agentConfigId
            null,                  // withMemory
            null, null,            // provider, model
            null, null,            // systemPrompt, prompt
            null, null, null, null, // temperature, maxTokens, maxIterations, maxTools
            List.of(),             // tools
            null,                  // parentLoopId
            null,                  // params
            null, null,            // classifyCategories, classifyParams
            null, null,            // guardrailRules, guardrailParams
            null                   // graphNodeId
        );
    }

    private InterfaceDef makeInterface() {
        // InterfaceDef canonical: (id, label, actionMapping, variableMapping, showPreview, position, isEntryInterface)
        return new InterfaceDef("interface-id", "i1", Map.of(), Map.of(), false, null, false);
    }

    private Map<String, List<WorkflowStepDataEntity>> makeEntities() {
        Map<String, List<WorkflowStepDataEntity>> map = new HashMap<>();
        map.put("m1", List.of(makeEntity()));
        map.put("t1", List.of(makeEntity()));
        map.put("i1", List.of(makeEntity()));
        return map;
    }

    private WorkflowStepDataEntity makeEntity() {
        WorkflowStepDataEntity e = mock(WorkflowStepDataEntity.class);
        when(e.getStatus()).thenReturn("COMPLETED");
        return e;
    }
}

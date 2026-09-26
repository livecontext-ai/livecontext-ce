package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.service.NodeLibraryService;
import com.apimarketplace.orchestrator.services.WorkflowManagementService;
import com.apimarketplace.orchestrator.services.WorkflowPlanVersionService;
import com.apimarketplace.trigger.client.TriggerClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * An agent builder session rebuilds the WHOLE plan from its own fields on every save
 * ({@link WorkflowBuilderSession#buildPlanMap()}), so plan content the session does not load
 * is deleted by the first agent edit. Two such losses, both reproduced from the code:
 *
 * <ul>
 *   <li>{@code layoutDirection}: a workflow saved top to bottom came back without its
 *       direction, and the canvas reads positions without a direction as the historical
 *       left-to-right layout, so every agent edit re-opened it sideways;</li>
 *   <li>{@code notes}: sticky notes were never loaded into the session, so they disappeared
 *       on the first agent edit of a workflow. Loaded, they then reached the orphan check,
 *       which refused the agent's save.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowBuilderSession - plan content an agent edit must keep")
class WorkflowBuilderSessionLayoutDirectionTest {

    private static final UUID WORKFLOW_ID = UUID.randomUUID();

    /** A stored plan with one positioned core, a sticky note and, optionally, a direction. */
    private static Map<String, Object> storedPlan(String layoutDirection) {
        Map<String, Object> core = new LinkedHashMap<>();
        core.put("id", "core:shape");
        core.put("label", "Shape");
        core.put("type", "transform");
        core.put("position", new LinkedHashMap<>(Map.of("x", 80, "y", 380)));
        Map<String, Object> note = new LinkedHashMap<>();
        note.put("id", "note-1");
        note.put("label", "Read me");
        note.put("content", "Why this workflow exists");
        note.put("position", new LinkedHashMap<>(Map.of("x", 400, "y", 0)));
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("name", "Direction Test");
        if (layoutDirection != null) {
            plan.put("layoutDirection", layoutDirection);
        }
        plan.put("cores", new ArrayList<>(List.of(core)));
        plan.put("notes", new ArrayList<>(List.of(note)));
        plan.put("edges", new ArrayList<>());
        return plan;
    }

    @Nested
    @DisplayName("loading a stored workflow into a session")
    class Loading {

        @Mock private WorkflowBuilderSessionStore sessionStore;
        @Mock private WorkflowManagementService workflowService;
        @Mock private WorkflowRepository workflowRepository;
        @Mock private WorkflowBuilderLogger buildLogger;
        @Mock private WorkflowBuilderValidator validator;
        @Mock private DataSourceClient dataSourceClient;
        @Mock private ToolSchemaFetcher toolSchemaFetcher;
        @Mock private NodeLibraryService nodeLibraryService;
        @Mock private TriggerClient triggerClient;
        @Mock private WorkflowPlanVersionService versionService;
        @Mock private AgentWorkflowFireService agentFireService;

        private WorkflowBuilderLoader loader;

        @BeforeEach
        void setUp() {
            loader = new WorkflowBuilderLoader(
                    sessionStore, workflowService, workflowRepository,
                    buildLogger, validator, dataSourceClient,
                    toolSchemaFetcher, nodeLibraryService,
                    new ObjectMapper(), triggerClient, versionService,
                    agentFireService);
        }

        /**
         * The canvas saved {@code stored} after the session last wrote: the next agent action
         * rebuilds the session from the stored plan (convertWorkflowToSession +
         * adoptPlanContentFrom), which is the path under test.
         */
        private WorkflowBuilderSession sessionRebuiltFrom(Map<String, Object> stored) {
            WorkflowBuilderSession session = WorkflowBuilderSession.create("t", "conv-1", "Direction Test", null);
            session.setLoadedWorkflowId(WORKFLOW_ID.toString());
            // What the session last wrote: an older copy, so the stored plan reads as a save
            // made elsewhere since.
            Map<String, Object> olderCopy = storedPlan(null);
            olderCopy.put("name", "Older copy");
            loader.recordStoredPlan(session, olderCopy);
            WorkflowEntity workflow = new WorkflowEntity();
            workflow.setId(WORKFLOW_ID);
            workflow.setTenantId("t");
            workflow.setName("Direction Test");
            workflow.setPlan(stored);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));

            assertThat(loader.resyncWithStoredPlan(session)).isTrue();
            return session;
        }

        @Test
        @DisplayName("regression: the direction the canvas saved is written back by the next agent save")
        void keepsTheSavedDirection() {
            WorkflowBuilderSession session = sessionRebuiltFrom(storedPlan("vertical"));

            assertThat(session.getLayoutDirection()).isEqualTo("vertical");
            assertThat(session.buildPlanMap()).containsEntry("layoutDirection", "vertical");
        }

        @Test
        @DisplayName("a plan saved without a direction is still saved without one (no direction is invented)")
        void inventsNoDirection() {
            WorkflowBuilderSession session = sessionRebuiltFrom(storedPlan(null));

            assertThat(session.buildPlanMap()).doesNotContainKey("layoutDirection");
        }

        @Test
        @DisplayName("a stored value that is not a direction is dropped rather than written back")
        void dropsAnUnknownDirection() {
            WorkflowBuilderSession session = sessionRebuiltFrom(storedPlan("diagonal"));

            assertThat(session.buildPlanMap()).doesNotContainKey("layoutDirection");
        }

        @Test
        @DisplayName("regression: sticky notes survive an agent edit")
        @SuppressWarnings("unchecked")
        void keepsTheNotes() {
            WorkflowBuilderSession session = sessionRebuiltFrom(storedPlan("vertical"));

            List<Map<String, Object>> notes = (List<Map<String, Object>>) session.buildPlanMap().get("notes");
            assertThat(notes).hasSize(1);
            assertThat(notes.get(0)).containsEntry("content", "Why this workflow exists");
        }

        @Test
        @DisplayName("regression: a loaded note is neither an orphan nor a dead end, so the agent's save is not refused")
        void noteIsNotAnOrphan() {
            // Loading the notes (above) made them reach the save validator, which read every
            // note as a node nobody connects to and refused the save with ORPHAN_NODE
            // (reproduced live on a CE slot). A note never runs.
            WorkflowBuilderSession session = sessionRebuiltFrom(storedPlan("vertical"));

            assertThat(session.findOrphanNodes()).noneMatch(id -> id.startsWith("note:"));
            assertThat(session.findDeadEndNodes()).noneMatch(id -> id.startsWith("note:"));
            // Real nodes are still checked: the unconnected core is still reported.
            assertThat(session.findOrphanNodes()).anyMatch(id -> id.startsWith("core:"));
        }
    }

    @Nested
    @DisplayName("set_plan")
    class SetPlan {

        @Mock private WorkflowBuilderSessionStore sessionStore;
        @Mock private ToolSchemaFetcher toolSchemaFetcher;

        private WorkflowBuilderPlanExporter exporter;

        @BeforeEach
        void setUp() {
            exporter = new WorkflowBuilderPlanExporter(sessionStore, toolSchemaFetcher);
        }

        private WorkflowBuilderSession sessionIn(String layoutDirection) {
            WorkflowBuilderSession session = WorkflowBuilderSession.builder()
                    .sessionId("test-session")
                    .tenantId("test-tenant")
                    .workflowName("Direction Test")
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();
            session.setLayoutDirection(layoutDirection);
            return session;
        }

        private ToolExecutionResult setPlan(WorkflowBuilderSession session, String layoutDirection) {
            Map<String, Object> trigger = new LinkedHashMap<>();
            trigger.put("label", "start");
            trigger.put("type", "manual");
            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("triggers", new ArrayList<>(List.of(trigger)));
            if (layoutDirection != null) {
                plan.put("layoutDirection", layoutDirection);
            }
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("plan", plan);
            return exporter.executeSetPlan(session, parameters);
        }

        @Test
        @DisplayName("a plan that does not state a direction keeps the one the workflow was saved in")
        void keepsTheSessionDirection() {
            WorkflowBuilderSession session = sessionIn("vertical");

            assertThat(setPlan(session, null).success()).isTrue();

            assertThat(session.buildPlanMap()).containsEntry("layoutDirection", "vertical");
        }

        @Test
        @DisplayName("a plan that states a direction replaces the session's")
        void adoptsAStatedDirection() {
            WorkflowBuilderSession session = sessionIn("vertical");

            assertThat(setPlan(session, "horizontal").success()).isTrue();

            assertThat(session.buildPlanMap()).containsEntry("layoutDirection", "horizontal");
        }

        @Test
        @DisplayName("a stated value that is not a direction is ignored")
        void ignoresAnUnknownDirection() {
            WorkflowBuilderSession session = sessionIn("vertical");

            assertThat(setPlan(session, "TB").success()).isTrue();

            assertThat(session.buildPlanMap()).containsEntry("layoutDirection", "vertical");
        }
    }
}

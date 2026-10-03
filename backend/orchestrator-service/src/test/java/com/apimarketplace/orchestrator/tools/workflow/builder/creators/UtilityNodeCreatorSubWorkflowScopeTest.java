package com.apimarketplace.orchestrator.tools.workflow.builder.creators;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.service.NodeLibraryService;
import com.apimarketplace.orchestrator.tools.workflow.builder.ResponseOptimizer;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSessionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The sub-workflow creator must not echo another tenant's workflow NAME (LC-043, security
 * audit 2026-08-13).
 *
 * <p>{@code SubWorkflowNode} was taught to refuse EXECUTING a workflow outside the caller's
 * workspace, but the builder still resolved the display name with a bare
 * {@code findById}: passing any workflow UUID wrote that workflow's name into the caller's
 * plan and echoed it back. A name is small, but it is somebody else's, and it also confirms
 * that the id names a real workflow.
 *
 * <p>The name is display metadata, so an out-of-workspace target must produce a node
 * WITHOUT the field rather than an error: erroring would leak the same fact the check
 * exists to hide, and would break a legitimate author whose id is simply stale.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("UtilityNodeCreator - sub_workflow name resolution scope (LC-043)")
class UtilityNodeCreatorSubWorkflowScopeTest {

    @Mock private WorkflowBuilderSessionStore sessionStore;
    @Mock private ResponseOptimizer responseOptimizer;
    @Mock private NodeLibraryService nodeLibraryService;
    @Mock private WorkflowRepository workflowRepository;

    private UtilityNodeCreator creator;
    private WorkflowBuilderSession session;

    private static final UUID TARGET_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final String CALLER = "user-caller";
    private static final String CALLER_ORG = "org-caller";

    @BeforeEach
    void setUp() {
        creator = new UtilityNodeCreator(sessionStore, responseOptimizer, nodeLibraryService, workflowRepository);
        session = WorkflowBuilderSession.builder()
                .sessionId("s")
                .tenantId(CALLER)
                .orgId(CALLER_ORG)
                .workflowName("parent")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("label", "Start");
        trigger.put("id", "trigger:start");
        trigger.put("type", "webhook");
        session.getTriggers().add(trigger);
        when(nodeLibraryService.findByType(anyString())).thenReturn(Optional.empty());
    }

    private WorkflowEntity target(String tenantId, String orgId) {
        WorkflowEntity workflow = new WorkflowEntity();
        workflow.setId(TARGET_ID);
        workflow.setName("Victim Payroll Export");
        workflow.setTenantId(tenantId);
        workflow.setOrganizationId(orgId);
        return workflow;
    }

    private Map<String, Object> params() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("label", "Call Child");
        p.put("connect_after", "Start");
        p.put("workflowId", TARGET_ID.toString());
        return p;
    }

    /** The sub-workflow config the creator just wrote onto the session. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> savedConfig() {
        List<Map<String, Object>> cores = session.getCores();
        assertThat(cores).as("the node must be created either way").hasSize(1);
        return (Map<String, Object>) cores.get(0).get("subWorkflow");
    }

    @Test
    @DisplayName("another workspace's workflow name is not written into the plan")
    void foreignWorkflowNameIsNotLeaked() {
        when(workflowRepository.findById(TARGET_ID))
                .thenReturn(Optional.of(target("user-victim", "org-victim")));

        ToolExecutionResult result = creator.executeAddSubWorkflow(session, params());

        assertThat(result.success()).as("the node is still created - the name is display metadata").isTrue();
        assertThat(savedConfig()).doesNotContainKey("workflowName");
        assertThat(savedConfig()).containsEntry("workflowId", TARGET_ID.toString());
    }

    @Test
    @DisplayName("a workflow of the caller's own workspace still resolves its name")
    void ownWorkspaceNameStillResolves() {
        // Anti-vacuity: dropping the name for everybody would satisfy the test above while
        // emptying the node's label in the builder.
        when(workflowRepository.findById(TARGET_ID))
                .thenReturn(Optional.of(target("user-colleague", CALLER_ORG)));

        creator.executeAddSubWorkflow(session, params());

        assertThat(savedConfig()).containsEntry("workflowName", "Victim Payroll Export");
    }

    @Test
    @DisplayName("a session with no organization falls back to ownership")
    void orglessSessionMatchesOnOwnership() {
        WorkflowBuilderSession orgless = WorkflowBuilderSession.builder()
                .sessionId("s2")
                .tenantId(CALLER)
                .workflowName("parent")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        orgless.getTriggers().add(session.getTriggers().get(0));
        when(workflowRepository.findById(TARGET_ID))
                .thenReturn(Optional.of(target(CALLER, "org-caller")));

        creator.executeAddSubWorkflow(orgless, params());

        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) orgless.getCores().get(0).get("subWorkflow");
        assertThat(config).containsEntry("workflowName", "Victim Payroll Export");
    }

    @Test
    @DisplayName("an unknown or malformed id creates the node without a name, as before")
    void unknownIdIsUnchanged() {
        when(workflowRepository.findById(TARGET_ID)).thenReturn(Optional.empty());

        ToolExecutionResult result = creator.executeAddSubWorkflow(session, params());

        assertThat(result.success()).isTrue();
        assertThat(savedConfig()).doesNotContainKey("workflowName");
    }
}

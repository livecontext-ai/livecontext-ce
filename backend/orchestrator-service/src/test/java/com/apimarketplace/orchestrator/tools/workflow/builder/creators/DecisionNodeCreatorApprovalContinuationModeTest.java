package com.apimarketplace.orchestrator.tools.workflow.builder.creators;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.tools.workflow.builder.ResponseOptimizer;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSessionStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression for the approval {@code continuationMode} gap: the agent's
 * {@code add_node type='approval'} path hand-builds the approval config map through an
 * allow-list and dropped {@code continuationMode}, even though WorkflowBuilderPrompts and the
 * node docs (V402) advertise it as an add_node param. An agent asking for
 * {@code continuationMode='per_item'} got a node that silently behaved as the default
 * ({@code all_items}): every per-item approval kept blocking the whole batch. set_plan
 * (Jackson) and modify (generic nested merge) were unaffected - only the agent's primary
 * create path lost the param. Third confirmed instance of the add_node allow-list bug class
 * (after {@link UtilityNodeCreatorExtractFromFileChunkUnitTest} and
 * {@link UtilityNodeCreatorMailParamsTest}); the class is guarded, for the covered records, by
 * {@link AddNodeConfigRecordContractTest}.
 *
 * <p>Pre-fix, {@link #perItemIsPersisted()} and {@link #snakeCaseAliasIsPersisted()} fail
 * because the persisted approval config has no {@code continuationMode} key.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DecisionNodeCreator - approval continuationMode persistence (add_node)")
class DecisionNodeCreatorApprovalContinuationModeTest {

    @Mock private WorkflowBuilderSessionStore sessionStore;
    @Mock private ResponseOptimizer responseOptimizer;

    private DecisionNodeCreator creator;
    private WorkflowBuilderSession session;

    @BeforeEach
    void setUp() {
        creator = new DecisionNodeCreator(sessionStore, responseOptimizer);
        session = WorkflowBuilderSession.builder()
            .sessionId("s")
            .tenantId("t")
            .workflowName("w")
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();
        Map<String, Object> trig = new LinkedHashMap<>();
        trig.put("label", "Start");
        trig.put("id", "trigger:start");
        trig.put("type", "webhook");
        session.getTriggers().add(trig);
    }

    private Map<String, Object> baseParams() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("label", "Item Gate");
        p.put("contextTemplate", "Approve {{item}}?");
        return p;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> approvalConfig() {
        Map<String, Object> node = session.getCores().get(0);
        return (Map<String, Object>) node.get("approval");
    }

    @Test
    @DisplayName("continuationMode='per_item' is persisted into the approval config")
    void perItemIsPersisted() {
        Map<String, Object> p = baseParams();
        p.put("continuationMode", "per_item");

        ToolExecutionResult r = creator.executeAddApproval(session, p);

        assertThat(r.success()).isTrue();
        assertThat(approvalConfig())
            .as("the agent's add_node create path must persist continuationMode, not drop it")
            .containsEntry("continuationMode", "per_item");
    }

    @Test
    @DisplayName("snake_case 'continuation_mode' alias is accepted like the sibling params")
    void snakeCaseAliasIsPersisted() {
        Map<String, Object> p = baseParams();
        p.put("continuation_mode", "per_item");

        ToolExecutionResult r = creator.executeAddApproval(session, p);

        assertThat(r.success()).isTrue();
        assertThat(approvalConfig()).containsEntry("continuationMode", "per_item");
    }

    @Test
    @DisplayName("value is normalized at creation ('PER_ITEM' -> per_item), matching runtime semantics")
    void valueIsNormalizedAtCreation() {
        Map<String, Object> p = baseParams();
        p.put("continuationMode", "PER_ITEM");

        creator.executeAddApproval(session, p);

        assertThat(approvalConfig()).containsEntry("continuationMode", "per_item");
    }

    @Test
    @DisplayName("an unknown value collapses to the stored canonical default (all_items), exactly as the runtime would resolve it")
    void unknownValueCollapsesToDefault() {
        Map<String, Object> p = baseParams();
        p.put("continuationMode", "every_item");

        creator.executeAddApproval(session, p);

        assertThat(approvalConfig()).containsEntry("continuationMode", "all_items");
    }

    @Test
    @DisplayName("omitting continuationMode leaves the key out, so the record default (all_items) applies")
    void omittedStaysAbsent() {
        creator.executeAddApproval(session, baseParams());

        assertThat(approvalConfig()).doesNotContainKey("continuationMode");
    }

    @Test
    @DisplayName("a blank value is treated as omitted, not stored")
    void blankTreatedAsOmitted() {
        Map<String, Object> p = baseParams();
        p.put("continuationMode", "   ");

        creator.executeAddApproval(session, p);

        assertThat(approvalConfig()).doesNotContainKey("continuationMode");
    }

    @Test
    @DisplayName("the agent-visible saved_params echoes continuationMode")
    @SuppressWarnings("unchecked")
    void savedParamsEchoesContinuationMode() {
        Map<String, Object> p = baseParams();
        p.put("continuationMode", "per_item");

        ToolExecutionResult r = creator.executeAddApproval(session, p);

        Map<String, Object> data = (Map<String, Object>) r.data();
        Map<String, Object> savedParams = (Map<String, Object>) data.get("saved_params");
        assertThat(savedParams)
            .as("the agent only learns what was stored from saved_params; it must echo continuationMode")
            .containsEntry("continuationMode", "per_item");
    }

    @Test
    @DisplayName("the stored config parses into the runtime record as per_item (cross-layer key contract)")
    void storedConfigParsesToPerItemRecord() {
        Map<String, Object> p = baseParams();
        p.put("continuationMode", "per_item");

        creator.executeAddApproval(session, p);

        // The plan parser reads the persisted 'approval' map back into this record.
        // Proves the key the builder writes is exactly the key the executor consumes.
        Core.ApprovalConfig parsed =
            new ObjectMapper().convertValue(approvalConfig(), Core.ApprovalConfig.class);
        assertThat(parsed.continuationMode())
            .as("a continuationMode='per_item' node built by the agent must drive per-item continuation at runtime")
            .isEqualTo(Core.ApprovalConfig.CONTINUATION_PER_ITEM);
    }
}

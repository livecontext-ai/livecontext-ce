package com.apimarketplace.orchestrator.services;

import com.apimarketplace.orchestrator.controllers.dto.ActiveAutomationDto.TriggerType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-layer parity guard for the trigger-kind ↔ nodeId mapping.
 *
 * <p>Three artifacts share this 8-entry mapping and must stay in lockstep:
 * <ol>
 *   <li>{@code WorkflowIconExtractor.TRIGGER_TYPE_TO_NODE_ID} in
 *       <b>orchestrator-service</b> (this module).</li>
 *   <li>{@code WorkflowIconExtractor.TRIGGER_TYPE_TO_NODE_ID} in
 *       <b>publication-service</b> (twin - guarded by a sibling test in that
 *       module asserting against the same canonical map below).</li>
 *   <li>Frontend {@code KIND_TO_NODE_ICON_KEY} in
 *       {@code frontend/lib/api/orchestrator/dashboard.service.ts}
 *       (TS, inverse direction).</li>
 * </ol>
 *
 * <p>This test pins the orchestrator copy + the service-internal
 * {@code KIND_BY_NODE_ID} reverse-map. The publication-service twin has its
 * own copy of these assertions, and the frontend mirror has a Jest unit test.
 * Adding a 9th trigger kind requires touching all four artifacts; this test
 * fails loud until the orchestrator side is consistent.
 */
@DisplayName("Trigger-kind / nodeId mapping parity")
class WorkflowIconExtractorParityTest {

    /**
     * Canonical 8-entry trigger map. Any change here is a deliberate spec
     * shift across 3 artifacts - bump the test, bump both extractor twins,
     * bump the frontend mirror.
     */
    private static final Map<String, String> CANONICAL_TRIGGER_TYPE_TO_NODE_ID = Map.of(
            "manual", "manual-trigger",
            "webhook", "webhook-trigger",
            "schedule", "schedule-trigger",
            "datasource", "tables-trigger",
            "chat", "chat-trigger",
            "form", "form-trigger",
            "workflow", "workflows-trigger",
            "error", "error-trigger"
    );

    @Test
    @DisplayName("Orchestrator WorkflowIconExtractor.TRIGGER_TYPE_TO_NODE_ID matches canonical 8-entry map")
    void orchestratorExtractorMatchesCanonical() {
        assertThat(WorkflowIconExtractor.TRIGGER_TYPE_TO_NODE_ID)
                .as("If this fails, the orchestrator extractor drifted from the canonical 8-kind map. "
                  + "Update the canonical map here AND the publication-service twin AND the frontend "
                  + "KIND_TO_NODE_ICON_KEY in dashboard.service.ts.")
                .isEqualTo(CANONICAL_TRIGGER_TYPE_TO_NODE_ID);
    }

    @Test
    @DisplayName("ActiveAutomationsService.KIND_BY_NODE_ID covers exactly the 6 declared kinds (no schedule, no webhook)")
    void kindByNodeIdHasSixDeclaredKinds() {
        Map<String, TriggerType> map = ActiveAutomationsService.KIND_BY_NODE_ID;
        // Foot-gun guard: re-introducing schedule/webhook would double-emit
        // (they're already handled by trigger-service authoritative sources).
        assertThat(map).hasSize(6);
        assertThat(map.keySet()).doesNotContain("schedule-trigger", "webhook-trigger");
        assertThat(map).containsOnlyKeys(
                "manual-trigger", "chat-trigger", "form-trigger",
                "tables-trigger", "workflows-trigger", "error-trigger"
        );
    }

    @Test
    @DisplayName("Forward extractor map values are a superset of reverse map keys (every declared-kind nodeId is emitted by extractor)")
    void extractorValuesSupersetOfReverseMapKeys() {
        Set<String> extractorNodeIds = Set.copyOf(WorkflowIconExtractor.TRIGGER_TYPE_TO_NODE_ID.values());
        assertThat(extractorNodeIds)
                .as("Every nodeId in KIND_BY_NODE_ID must be a possible emission of WorkflowIconExtractor; "
                  + "otherwise the new-kind row will never appear because nodeIcons can't carry that nodeId.")
                .containsAll(ActiveAutomationsService.KIND_BY_NODE_ID.keySet());
    }

    @Test
    @DisplayName("Reverse map values cover all 6 declared TriggerType values (no enum drift)")
    void reverseMapCoversSixDeclaredEnumValues() {
        Set<TriggerType> declared = Set.copyOf(ActiveAutomationsService.KIND_BY_NODE_ID.values());
        assertThat(declared).containsExactlyInAnyOrder(
                TriggerType.MANUAL, TriggerType.CHAT, TriggerType.FORM,
                TriggerType.DATASOURCE, TriggerType.WORKFLOW, TriggerType.ERROR
        );
        // And the enum overall has the original 2 + 6 = 8 values.
        assertThat(TriggerType.values()).hasSize(8);
    }
}

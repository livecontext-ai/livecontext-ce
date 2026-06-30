package com.apimarketplace.orchestrator.execution.v2.services;

import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the equivalence flagged by the blast-radius review: once buildSplitItemData persists
 * {@code workflowItemIndex} for approval/wait signals, {@link SignalResumeService#resolveParentItemIdForSplitSignal}
 * takes the (previously dead) workflowItemIndex branch. It must return the SAME parent item id
 * the legacy itemId-suffix fallback produced, for both top-level and nested splits.
 */
@DisplayName("SignalResumeService.resolveParentItemIdForSplitSignal")
class SignalResumeServiceParentItemIdTest {

    private static SignalWaitEntity withWorkflowItemIndex(int idx) {
        SignalWaitEntity s = mock(SignalWaitEntity.class);
        when(s.getSplitItemData()).thenReturn(Map.of("workflowItemIndex", idx));
        return s;
    }

    private static SignalWaitEntity legacy(String itemId) {
        SignalWaitEntity s = mock(SignalWaitEntity.class);
        when(s.getSplitItemData()).thenReturn(Map.of()); // no workflowItemIndex (pre-fix signal)
        when(s.getItemId()).thenReturn(itemId);
        return s;
    }

    @Test
    @DisplayName("null signal -> root item \"0\"")
    void nullSignal() {
        assertEquals("0", SignalResumeService.resolveParentItemIdForSplitSignal(null));
    }

    @Test
    @DisplayName("top-level split: workflowItemIndex=0 branch == legacy itemId \"3\" (no dot) fallback == \"0\"")
    void topLevelEquivalence() {
        assertEquals("0", SignalResumeService.resolveParentItemIdForSplitSignal(withWorkflowItemIndex(0)));
        assertEquals("0", SignalResumeService.resolveParentItemIdForSplitSignal(legacy("3")));
    }

    @Test
    @DisplayName("nested split: workflowItemIndex=7 branch == legacy itemId \"7.3\" fallback == \"7\"")
    void nestedEquivalence() {
        assertEquals("7", SignalResumeService.resolveParentItemIdForSplitSignal(withWorkflowItemIndex(7)));
        assertEquals("7", SignalResumeService.resolveParentItemIdForSplitSignal(legacy("7.3")));
    }

    @Test
    @DisplayName("legacy signal with no workflowItemIndex and no dotted itemId -> \"0\" (unchanged for in-flight pre-deploy signals)")
    void legacyNoIndexNoDot() {
        assertEquals("0", SignalResumeService.resolveParentItemIdForSplitSignal(legacy("2")));
    }
}

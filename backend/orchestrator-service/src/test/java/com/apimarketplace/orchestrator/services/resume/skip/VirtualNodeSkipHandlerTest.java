package com.apimarketplace.orchestrator.services.resume.skip;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("VirtualNodeSkipHandler")
class VirtualNodeSkipHandlerTest {

    @Mock
    private SkipGraphAnalyzer graphAnalyzer;

    @Mock
    private WorkflowPlan plan;

    private VirtualNodeSkipHandler handler;

    @BeforeEach
    void setUp() {
        handler = new VirtualNodeSkipHandler(graphAnalyzer);
    }

    @Nested
    @DisplayName("isVirtualNode()")
    class IsVirtualNodeTests {

        @Test
        @DisplayName("Should return true for virtual node with ::")
        void shouldReturnTrueForVirtualNode() {
            assertTrue(handler.isVirtualNode("core:while::condition_checker"));
        }

        @Test
        @DisplayName("Should return false for regular node")
        void shouldReturnFalseForRegularNode() {
            assertFalse(handler.isVirtualNode("mcp:step1"));
        }

        @Test
        @DisplayName("Should return false for null")
        void shouldReturnFalseForNull() {
            assertFalse(handler.isVirtualNode(null));
        }

        @Test
        @DisplayName("Should return false for core node without virtual suffix")
        void shouldReturnFalseForCoreWithoutVirtual() {
            assertFalse(handler.isVirtualNode("core:decision"));
        }
    }

    @Nested
    @DisplayName("extractParentNodeId()")
    class ExtractParentNodeIdTests {

        @Test
        @DisplayName("Should extract parent from virtual node")
        void shouldExtractParent() {
            assertEquals("core:while", handler.extractParentNodeId("core:while::condition_checker"));
        }

        @Test
        @DisplayName("Should return null for non-virtual node")
        void shouldReturnNullForNonVirtual() {
            assertNull(handler.extractParentNodeId("mcp:step1"));
        }

        @Test
        @DisplayName("Should return null for null input")
        void shouldReturnNullForNull() {
            assertNull(handler.extractParentNodeId(null));
        }

        @Test
        @DisplayName("Should handle :: at start of string")
        void shouldHandleDoubleColonAtStart() {
            assertNull(handler.extractParentNodeId("::condition_checker"));
        }
    }

    @Nested
    @DisplayName("addSkippedCores()")
    class AddSkippedCoresTests {

        @Test
        @DisplayName("Should be a no-op after legacy loop removal")
        void shouldBeNoOpAfterLegacyLoopRemoval() {
            Set<String> completedStepIds = new HashSet<>();
            Set<String> skippedStepIds = new HashSet<>(Set.of("mcp:entry_step"));
            int sizeBefore = skippedStepIds.size();

            handler.addSkippedCores(plan, completedStepIds, skippedStepIds);

            // No-op: legacy loop virtual node handling was removed
            assertEquals(sizeBefore, skippedStepIds.size());
        }
    }
}

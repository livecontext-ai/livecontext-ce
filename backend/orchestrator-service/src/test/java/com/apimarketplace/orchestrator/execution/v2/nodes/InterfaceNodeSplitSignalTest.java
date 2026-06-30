package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.domain.execution.SignalType;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the fix where InterfaceNode in a split context was registering
 * signals with the wrong itemId (always "0" instead of the actual sub-item index).
 *
 * <p>The fix added {@code ExecutionContext.withItemIndex(int)} which updates both
 * {@code itemId} (as String) and {@code itemIndex}, and is called by
 * {@code SplitAwareNodeExecutor.enrichContextWithItem()} so each sub-item
 * gets the correct itemId in its context.
 */
class InterfaceNodeSplitSignalTest {

    private static final String RUN_ID = "run-abc-123";
    private static final String WORKFLOW_RUN_ID = "wfr-abc-123";
    private static final String TENANT_ID = "tenant-1";
    private static final String NODE_ID = "interface:my_form";
    private static final String INTERFACE_ID = "iface-uuid-456";
    private static final String TRIGGER_ID = "trigger:webhook";
    private static final int EPOCH = 2;

    private UnifiedSignalService signalService;
    private InterfaceNode interfaceNode;

    @BeforeEach
    void setUp() {
        signalService = mock(UnifiedSignalService.class);
    }

    /**
     * Creates a non-blocking InterfaceNode (no __continue action).
     */
    private InterfaceNode createNonBlockingNode() {
        Map<String, String> actions = Map.of(
            "#btn-search", "mcp:search",
            "#btn-refresh", "mcp:refresh"
        );
        InterfaceNode node = new InterfaceNode(NODE_ID, INTERFACE_ID, actions, false);
        injectSignalService(node);
        return node;
    }

    /**
     * Creates a blocking InterfaceNode (has __continue action).
     */
    private InterfaceNode createBlockingNode() {
        Map<String, String> actions = Map.of(
            "#btn-next", "__continue",
            "#btn-search", "mcp:search"
        );
        InterfaceNode node = new InterfaceNode(NODE_ID, INTERFACE_ID, actions, false);
        injectSignalService(node);
        return node;
    }

    private void injectSignalService(InterfaceNode node) {
        ServiceRegistry registry = ServiceRegistry.builder()
            .signalService(signalService)
            .build();
        node.acceptServices(registry);
        node.setDagTriggerId(TRIGGER_ID);
        node.setEpoch(EPOCH);
    }

    /**
     * Creates an ExecutionContext with the given itemId and itemIndex.
     */
    private ExecutionContext createContext(String itemId, int itemIndex) {
        return ExecutionContext.create(
            RUN_ID, WORKFLOW_RUN_ID, TENANT_ID,
            itemId, itemIndex,
            TRIGGER_ID, EPOCH, 0,
            new HashMap<>(), null
        );
    }

    // -----------------------------------------------------------------------
    // Test 1: InterfaceNode uses context.itemId() for signal registration
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("InterfaceNode uses context.itemId() for signal registration")
    class ItemIdPassedToSignalRegistration {

        @Test
        @DisplayName("itemId '3' from context is passed to registerSignal")
        void itemIdFromContextIsPassedToRegisterSignal() {
            InterfaceNode node = createNonBlockingNode();
            ExecutionContext ctx = createContext("3", 3);

            node.execute(ctx);

            verify(signalService).registerSignal(
                eq(RUN_ID),
                eq("3"),       // the bug was always "0" here
                eq(NODE_ID),
                eq(TRIGGER_ID),
                eq(EPOCH),
                eq(SignalType.INTERFACE_SIGNAL),
                any(),
                isNull()
            );
        }

        @Test
        @DisplayName("itemId '0' from context is correctly passed (first sub-item)")
        void itemIdZeroIsPassedCorrectly() {
            InterfaceNode node = createNonBlockingNode();
            ExecutionContext ctx = createContext("0", 0);

            node.execute(ctx);

            verify(signalService).registerSignal(
                eq(RUN_ID),
                eq("0"),
                eq(NODE_ID),
                eq(TRIGGER_ID),
                eq(EPOCH),
                eq(SignalType.INTERFACE_SIGNAL),
                any(),
                isNull()
            );
        }

        @Test
        @DisplayName("itemId '6' from context is passed for last sub-item in a 7-item split")
        void itemIdSixIsPassedCorrectly() {
            InterfaceNode node = createNonBlockingNode();
            ExecutionContext ctx = createContext("6", 6);

            node.execute(ctx);

            verify(signalService).registerSignal(
                eq(RUN_ID),
                eq("6"),
                eq(NODE_ID),
                eq(TRIGGER_ID),
                eq(EPOCH),
                eq(SignalType.INTERFACE_SIGNAL),
                any(),
                isNull()
            );
        }

        @Test
        @DisplayName("non-numeric itemId (legacy) is passed through unchanged")
        void nonNumericItemIdIsPassedThrough() {
            InterfaceNode node = createNonBlockingNode();
            ExecutionContext ctx = createContext("custom-item-id", 0);

            node.execute(ctx);

            verify(signalService).registerSignal(
                eq(RUN_ID),
                eq("custom-item-id"),
                eq(NODE_ID),
                anyString(),
                anyInt(),
                any(SignalType.class),
                any(),
                isNull()
            );
        }
    }

    // -----------------------------------------------------------------------
    // Test 2: withItemIndex correctly propagates itemIndex
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("ExecutionContext.withItemIndex() correctly updates both fields")
    class WithItemIndexPropagation {

        @Test
        @DisplayName("withItemIndex(3) sets itemId to '3' and itemIndex to 3")
        void withItemIndexSetsStringAndInt() {
            ExecutionContext original = createContext("0", 0);

            ExecutionContext updated = original.withItemIndex(3);

            assertThat(updated.itemId()).isEqualTo("3");
            assertThat(updated.itemIndex()).isEqualTo(3);
        }

        @Test
        @DisplayName("withItemIndex(0) sets itemId to '0' and itemIndex to 0")
        void withItemIndexZero() {
            ExecutionContext original = createContext("99", 99);

            ExecutionContext updated = original.withItemIndex(0);

            assertThat(updated.itemId()).isEqualTo("0");
            assertThat(updated.itemIndex()).isEqualTo(0);
        }

        @Test
        @DisplayName("withItemIndex preserves all other context fields")
        void withItemIndexPreservesOtherFields() {
            ExecutionContext original = createContext("0", 0);

            ExecutionContext updated = original.withItemIndex(5);

            assertThat(updated.runId()).isEqualTo(RUN_ID);
            assertThat(updated.workflowRunId()).isEqualTo(WORKFLOW_RUN_ID);
            assertThat(updated.tenantId()).isEqualTo(TENANT_ID);
            assertThat(updated.triggerId()).isEqualTo(TRIGGER_ID);
            assertThat(updated.epoch()).isEqualTo(EPOCH);
            assertThat(updated.spawn()).isEqualTo(0);
            assertThat(updated.plan()).isEqualTo(original.plan());
            assertThat(updated.state()).isEqualTo(original.state());
        }

        @Test
        @DisplayName("withItemIndex returns a new instance (immutability)")
        void withItemIndexReturnsNewInstance() {
            ExecutionContext original = createContext("0", 0);

            ExecutionContext updated = original.withItemIndex(4);

            assertThat(updated).isNotSameAs(original);
        }
    }

    // -----------------------------------------------------------------------
    // Test 3: Multiple sub-items get unique itemIds
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Multiple sub-items get unique itemIds (simulates SplitAwareNodeExecutor)")
    class MultipleSubItemsUniqueIds {

        @Test
        @DisplayName("7 sub-items produce 7 unique signal registrations with itemIds 0-6")
        void sevenSubItemsProduceSevenUniqueSignals() {
            int subItemCount = 7;

            // Simulate what SplitAwareNodeExecutor does: for each sub-item,
            // create a context with withItemIndex(i), then execute the InterfaceNode
            for (int i = 0; i < subItemCount; i++) {
                // Each sub-item gets a fresh InterfaceNode (same config) to avoid
                // Mockito verify confusion, but in production it is the same node
                // instance called with different contexts.
                InterfaceNode node = createNonBlockingNode();
                ExecutionContext baseCtx = createContext("0", 0);
                ExecutionContext subItemCtx = baseCtx.withItemIndex(i);

                node.execute(subItemCtx);

                verify(signalService).registerSignal(
                    eq(RUN_ID),
                    eq(String.valueOf(i)),
                    eq(NODE_ID),
                    eq(TRIGGER_ID),
                    eq(EPOCH),
                    eq(SignalType.INTERFACE_SIGNAL),
                    any(),
                    isNull()
                );
            }

            // Verify total invocation count across all mock instances
            verify(signalService, times(subItemCount)).registerSignal(
                anyString(), anyString(), anyString(), anyString(),
                anyInt(), any(SignalType.class), any(), any()
            );
        }

        @Test
        @DisplayName("all 7 itemIds are distinct strings")
        void allItemIdsAreDistinct() {
            int subItemCount = 7;
            InterfaceNode node = createNonBlockingNode();

            ArgumentCaptor<String> itemIdCaptor = ArgumentCaptor.forClass(String.class);

            for (int i = 0; i < subItemCount; i++) {
                ExecutionContext subItemCtx = createContext("0", 0).withItemIndex(i);
                node.execute(subItemCtx);
            }

            verify(signalService, times(subItemCount)).registerSignal(
                anyString(),
                itemIdCaptor.capture(),
                anyString(),
                anyString(),
                anyInt(),
                any(SignalType.class),
                any(),
                any()
            );

            Set<String> capturedItemIds = new HashSet<>(itemIdCaptor.getAllValues());
            assertThat(capturedItemIds)
                .hasSize(subItemCount)
                .containsExactlyInAnyOrder("0", "1", "2", "3", "4", "5", "6");
        }

        @Test
        @DisplayName("itemIds are passed in sequential order matching sub-item indices")
        void itemIdsMatchSubItemIndicesInOrder() {
            int subItemCount = 7;
            InterfaceNode node = createNonBlockingNode();

            ArgumentCaptor<String> itemIdCaptor = ArgumentCaptor.forClass(String.class);

            for (int i = 0; i < subItemCount; i++) {
                ExecutionContext subItemCtx = createContext("0", 0).withItemIndex(i);
                node.execute(subItemCtx);
            }

            verify(signalService, times(subItemCount)).registerSignal(
                anyString(),
                itemIdCaptor.capture(),
                anyString(),
                anyString(),
                anyInt(),
                any(SignalType.class),
                any(),
                any()
            );

            // Verify order matches 0, 1, 2, ..., 6
            for (int i = 0; i < subItemCount; i++) {
                assertThat(itemIdCaptor.getAllValues().get(i))
                    .as("itemId at position %d", i)
                    .isEqualTo(String.valueOf(i));
            }
        }
    }

    // -----------------------------------------------------------------------
    // Test 4: Blocking vs non-blocking with correct itemId in split
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Blocking vs non-blocking paths both pass correct itemId")
    class BlockingVsNonBlocking {

        @Test
        @DisplayName("non-blocking node (no __continue) returns SUCCESS and registers signal with correct itemId")
        void nonBlockingReturnsSuccessWithCorrectItemId() {
            InterfaceNode node = createNonBlockingNode();
            ExecutionContext ctx = createContext("0", 0).withItemIndex(4);

            NodeExecutionResult result = node.execute(ctx);

            // Verify result is SUCCESS (auto-advance)
            assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);

            // Verify itemId "4" was passed to signal registration
            verify(signalService).registerSignal(
                eq(RUN_ID),
                eq("4"),
                eq(NODE_ID),
                eq(TRIGGER_ID),
                eq(EPOCH),
                eq(SignalType.INTERFACE_SIGNAL),
                any(),
                isNull()
            );
        }

        @Test
        @DisplayName("blocking node (has __continue) returns AWAITING_SIGNAL and registers signal with correct itemId")
        void blockingReturnsAwaitingSignalWithCorrectItemId() {
            InterfaceNode node = createBlockingNode();
            ExecutionContext ctx = createContext("0", 0).withItemIndex(5);

            NodeExecutionResult result = node.execute(ctx);

            // Verify result is AWAITING_SIGNAL (blocking)
            assertThat(result.status()).isEqualTo(NodeStatus.AWAITING_SIGNAL);

            // Verify itemId "5" was passed to signal registration
            verify(signalService).registerSignal(
                eq(RUN_ID),
                eq("5"),
                eq(NODE_ID),
                eq(TRIGGER_ID),
                eq(EPOCH),
                eq(SignalType.INTERFACE_SIGNAL),
                any(),
                isNull()
            );
        }

        @Test
        @DisplayName("blocking node output contains INTERFACE_SIGNAL signal_type")
        void blockingNodeOutputContainsSignalType() {
            InterfaceNode node = createBlockingNode();
            ExecutionContext ctx = createContext("0", 0).withItemIndex(2);

            NodeExecutionResult result = node.execute(ctx);

            assertThat(result.output()).containsKey("signal_type");
            assertThat(result.output().get("signal_type")).isEqualTo("INTERFACE_SIGNAL");
        }

        @Test
        @DisplayName("non-blocking node output contains interface metadata")
        void nonBlockingNodeOutputContainsMetadata() {
            InterfaceNode node = createNonBlockingNode();
            ExecutionContext ctx = createContext("0", 0).withItemIndex(1);

            NodeExecutionResult result = node.execute(ctx);

            assertThat(result.output())
                .containsEntry("interface_id", INTERFACE_ID)
                .containsEntry("is_entry_interface", false)
                .containsKey("action_mapping")
                .containsKey("resolved_params");
        }
    }

    // -----------------------------------------------------------------------
    // Test 5: Original context's itemId is NOT mutated by withItemIndex
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Original context is not mutated by withItemIndex (immutability)")
    class OriginalContextImmutability {

        @Test
        @DisplayName("original context retains itemId '0' after withItemIndex(5)")
        void originalRetainsItemIdAfterWithItemIndex() {
            ExecutionContext original = createContext("0", 0);

            ExecutionContext modified = original.withItemIndex(5);

            assertThat(original.itemId()).isEqualTo("0");
            assertThat(original.itemIndex()).isEqualTo(0);
            assertThat(modified.itemId()).isEqualTo("5");
            assertThat(modified.itemIndex()).isEqualTo(5);
        }

        @Test
        @DisplayName("multiple withItemIndex calls from same base do not interfere")
        void multipleCallsFromSameBaseDoNotInterfere() {
            ExecutionContext base = createContext("0", 0);

            ExecutionContext ctx1 = base.withItemIndex(1);
            ExecutionContext ctx2 = base.withItemIndex(2);
            ExecutionContext ctx3 = base.withItemIndex(3);

            // Base is unchanged
            assertThat(base.itemId()).isEqualTo("0");
            assertThat(base.itemIndex()).isEqualTo(0);

            // Each derived context has its own itemId
            assertThat(ctx1.itemId()).isEqualTo("1");
            assertThat(ctx1.itemIndex()).isEqualTo(1);

            assertThat(ctx2.itemId()).isEqualTo("2");
            assertThat(ctx2.itemIndex()).isEqualTo(2);

            assertThat(ctx3.itemId()).isEqualTo("3");
            assertThat(ctx3.itemIndex()).isEqualTo(3);
        }

        @Test
        @DisplayName("chaining withItemIndex calls produces correct final state")
        void chainingWithItemIndexProducesCorrectState() {
            ExecutionContext original = createContext("0", 0);

            // Simulate reassigning: first item 3, then changing to item 7
            ExecutionContext first = original.withItemIndex(3);
            ExecutionContext second = first.withItemIndex(7);

            assertThat(original.itemId()).isEqualTo("0");
            assertThat(first.itemId()).isEqualTo("3");
            assertThat(second.itemId()).isEqualTo("7");
            assertThat(second.itemIndex()).isEqualTo(7);
        }

        @Test
        @DisplayName("derived contexts have independent identity from each other")
        void derivedContextsHaveIndependentIdentity() {
            ExecutionContext base = createContext("0", 0);
            ExecutionContext sub1 = base.withItemIndex(1);
            ExecutionContext sub2 = base.withItemIndex(2);

            // Verify they are distinct context instances with different itemId/itemIndex
            assertThat(sub1).isNotSameAs(sub2);
            assertThat(sub1.itemId()).isNotEqualTo(sub2.itemId());
            assertThat(sub1.itemIndex()).isNotEqualTo(sub2.itemIndex());

            // Verify they share the same runId (proving they derive from same base)
            assertThat(sub1.runId()).isEqualTo(sub2.runId());
        }
    }
}

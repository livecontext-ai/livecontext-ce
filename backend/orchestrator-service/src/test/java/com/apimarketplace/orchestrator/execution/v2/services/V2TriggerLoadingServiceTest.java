package com.apimarketplace.orchestrator.execution.v2.services;

import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionTree;
import com.apimarketplace.orchestrator.services.TriggerResolverService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for V2TriggerLoadingService.
 *
 * This service handles trigger loading for step-by-step execution,
 * including datasource loading, trigger type detection, and reusable trigger handling.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("V2TriggerLoadingService")
class V2TriggerLoadingServiceTest {

    @Mock
    private TriggerResolverService triggerResolverService;

    @Mock
    private V2StepByStepContextManager contextManager;

    @Mock
    private ExecutionTree executionTree;

    @Mock
    private WorkflowPlan workflowPlan;

    @Mock
    private WorkflowExecution execution;

    private V2TriggerLoadingService triggerLoadingService;

    @BeforeEach
    void setUp() {
        triggerLoadingService = new V2TriggerLoadingService(
            triggerResolverService,
            contextManager
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // loadTriggerItemsIfNeeded() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("loadTriggerItemsIfNeeded()")
    class LoadTriggerItemsIfNeededTests {

        @Test
        @DisplayName("Should skip loading if already loaded")
        void shouldSkipLoadingIfAlreadyLoaded() {
            // Given
            when(contextManager.hasTriggerItems("run-1")).thenReturn(true);

            // When
            triggerLoadingService.loadTriggerItemsIfNeeded(
                "run-1", executionTree, 0, "trigger:start", execution);

            // Then
            verify(triggerResolverService, never()).resolveTrigger(any(), any(), any());
        }

        @Test
        @DisplayName("Should cache empty list when no triggers in plan")
        void shouldCacheEmptyListWhenNoTriggersInPlan() {
            // Given
            when(contextManager.hasTriggerItems("run-1")).thenReturn(false);
            when(executionTree.plan()).thenReturn(workflowPlan);
            when(workflowPlan.getTriggers()).thenReturn(List.of());

            // When
            triggerLoadingService.loadTriggerItemsIfNeeded(
                "run-1", executionTree, 0, "trigger:start", execution);

            // Then
            verify(contextManager).cacheTriggerItems(eq("run-1"), argThat(List::isEmpty));
        }

        @Test
        @DisplayName("Should cache empty list when plan is null")
        void shouldCacheEmptyListWhenPlanIsNull() {
            // Given
            when(contextManager.hasTriggerItems("run-1")).thenReturn(false);
            when(executionTree.plan()).thenReturn(null);

            // When
            triggerLoadingService.loadTriggerItemsIfNeeded(
                "run-1", executionTree, 0, "trigger:start", execution);

            // Then
            verify(contextManager).cacheTriggerItems(eq("run-1"), argThat(List::isEmpty));
        }

        @Test
        @DisplayName("Should load and cache trigger items from datasource")
        void shouldLoadAndCacheTriggerItemsFromDatasource() {
            // Given
            Trigger trigger = createTrigger("t1", "My Trigger", "datasource");
            when(contextManager.hasTriggerItems("run-1")).thenReturn(false);
            when(executionTree.plan()).thenReturn(workflowPlan);
            when(executionTree.tenantId()).thenReturn("tenant-1");
            when(workflowPlan.getTriggers()).thenReturn(List.of(trigger));
            when(triggerResolverService.resolveTrigger(any(), eq("tenant-1"), any()))
                .thenReturn(Map.of("items", List.of(
                    Map.of("id", 1, "name", "Item 1"),
                    Map.of("id", 2, "name", "Item 2")
                )));

            // When
            triggerLoadingService.loadTriggerItemsIfNeeded(
                "run-1", executionTree, 0, "trigger:my_trigger", execution);

            // Then
            verify(contextManager).cacheTriggerItems(eq("run-1"), argThat(items -> items.size() == 2));
        }

        @Test
        @DisplayName("Should extract items from 'data' key if 'items' not present")
        void shouldExtractItemsFromDataKey() {
            // Given
            Trigger trigger = createTrigger("t1", "My Trigger", "datasource");
            when(contextManager.hasTriggerItems("run-1")).thenReturn(false);
            when(executionTree.plan()).thenReturn(workflowPlan);
            when(executionTree.tenantId()).thenReturn("tenant-1");
            when(workflowPlan.getTriggers()).thenReturn(List.of(trigger));
            when(triggerResolverService.resolveTrigger(any(), any(), any()))
                .thenReturn(Map.of("data", List.of(
                    Map.of("id", 1)
                )));

            // When
            triggerLoadingService.loadTriggerItemsIfNeeded(
                "run-1", executionTree, 0, "trigger:my_trigger", execution);

            // Then
            verify(contextManager).cacheTriggerItems(eq("run-1"), argThat(items -> items.size() == 1));
        }

        @Test
        @DisplayName("Explicit count=0 from resolver → cached items list is EMPTY (no phantom item)")
        void shouldHonorExplicitCountZeroAndSkipFallback() {
            // Audit 2026-05-06 round 2 P0 #1 regression guard. Without this fix,
            // ScheduleTriggerResolver's {count: 0, data: []} output was silently
            // wrapped by the fallback path (`items.isEmpty() && !triggerResult.isEmpty()
            // → items.add(triggerResult)`) and produced a single phantom item
            // downstream. A `core:split` adjacent to a schedule trigger then
            // fanned out once where the pre-Phase-D path (which threw + caught)
            // produced 0. The fix honors `count: 0` as explicit opt-out from
            // the wrap-the-whole-result branch - verify the contract end-to-end
            // through extractTriggerItems.
            Trigger trigger = createTrigger("trigger:cron", "cron", "schedule");
            when(contextManager.hasTriggerItems("run-1")).thenReturn(false);
            when(executionTree.plan()).thenReturn(workflowPlan);
            when(executionTree.tenantId()).thenReturn("tenant-1");
            when(workflowPlan.getTriggers()).thenReturn(List.of(trigger));
            // Mirrors the actual ScheduleTriggerResolver output shape.
            when(triggerResolverService.resolveTrigger(any(), any(), any()))
                .thenReturn(Map.of(
                    "triggerId", "trigger:cron",
                    "type", "schedule",
                    "status", "success",
                    "source", "schedule",
                    "triggered_at", "2026-05-06T15:00:00Z",
                    "triggered_by", "schedule",
                    "data", List.of(),
                    "count", 0));

            triggerLoadingService.loadTriggerItemsIfNeeded(
                "run-1", executionTree, 0, "trigger:cron", execution);

            verify(contextManager).cacheTriggerItems(eq("run-1"), argThat(List::isEmpty));
        }

        @Test
        @DisplayName("Chat-unmatched payload (count=0 + data=[]) → 0 items downstream (behavioural fix)")
        void shouldHonorCountZeroForChatUnmatched() {
            // Audit 2026-05-06 round 3 #1 behavioural side-effect lock-in.
            // ChatTriggerResolver.buildUnmatchedPayload sets count=0 + data=[]
            // when the chat input does not match the configured action mapping.
            // Pre-fix the fallback wrapped the entire payload into 1 phantom
            // item carrying no_match metadata - contradicting the resolver's
            // own count=0 signal and causing a downstream `core:split` to
            // fan out once. Post-fix the count=0 opt-out fires, returning 0
            // items so unmatched chat does not trigger fan-out.
            Trigger trigger = createTrigger("trigger:chat", "chat", "chat");
            when(contextManager.hasTriggerItems("run-2")).thenReturn(false);
            when(executionTree.plan()).thenReturn(workflowPlan);
            when(executionTree.tenantId()).thenReturn("tenant-1");
            when(workflowPlan.getTriggers()).thenReturn(List.of(trigger));
            // Mirrors the actual ChatTriggerResolver.buildUnmatchedPayload output
            // (chat-unmatched path). Top-level triggered_at/triggered_by are always
            // emitted by the parent resolve() method (lines 105-106). No triggerId
            // or type fields - the resolver doesn't emit them.
            when(triggerResolverService.resolveTrigger(any(), any(), any()))
                .thenReturn(Map.of(
                    "status", "no_match",
                    "message", "hello there",
                    "extracted_message", "hello there",
                    "matched", false,
                    "match_type", "exact",
                    "match_value", "expected",
                    "triggered_at", "2026-05-06T15:00:00Z",
                    "triggered_by", "user@example.com",
                    "data", List.of(),
                    "count", 0));

            triggerLoadingService.loadTriggerItemsIfNeeded(
                "run-2", executionTree, 0, "trigger:chat", execution);

            verify(contextManager).cacheTriggerItems(eq("run-2"), argThat(List::isEmpty));
        }

        @Test
        @DisplayName("Datasource-error payload (count=0 + data=[]) → 0 items downstream (behavioural fix)")
        void shouldHonorCountZeroForDatasourceError() {
            // Audit 2026-05-06 round 3 #1 behavioural side-effect lock-in.
            // TriggerPayloadBuilder.buildErrorPayload emits count=0 + data=[]
            // when datasource resolution fails. Pre-fix wrapped to 1 phantom
            // item carrying error metadata; post-fix correctly returns 0 items
            // so a downstream `core:split` does not fan out on an error trigger.
            Trigger trigger = createTrigger("trigger:ds", "ds", "datasource");
            when(contextManager.hasTriggerItems("run-3")).thenReturn(false);
            when(executionTree.plan()).thenReturn(workflowPlan);
            when(executionTree.tenantId()).thenReturn("tenant-1");
            when(workflowPlan.getTriggers()).thenReturn(List.of(trigger));
            // Mirrors the actual TriggerPayloadBuilder.buildErrorPayload shape
            // (datasource error path): triggerId, tenantId, status, error, [message],
            // data, count, source. No `type` field - the resolver doesn't emit it.
            when(triggerResolverService.resolveTrigger(any(), any(), any()))
                .thenReturn(Map.of(
                    "triggerId", "trigger:ds",
                    "tenantId", "tenant-1",
                    "status", "error",
                    "error", "DATASOURCE_TIMEOUT",
                    "message", "connection timeout",
                    "data", List.of(),
                    "count", 0,
                    "source", "datasource"));

            triggerLoadingService.loadTriggerItemsIfNeeded(
                "run-3", executionTree, 0, "trigger:ds", execution);

            verify(contextManager).cacheTriggerItems(eq("run-3"), argThat(List::isEmpty));
        }

        @Test
        @DisplayName("Should wrap whole result as single item if no standard keys")
        void shouldWrapWholeResultAsSingleItemIfNoStandardKeys() {
            // Given
            Trigger trigger = createTrigger("t1", "My Trigger", "webhook");
            when(contextManager.hasTriggerItems("run-1")).thenReturn(false);
            when(executionTree.plan()).thenReturn(workflowPlan);
            when(executionTree.tenantId()).thenReturn("tenant-1");
            when(workflowPlan.getTriggers()).thenReturn(List.of(trigger));
            when(triggerResolverService.resolveTrigger(any(), any(), any()))
                .thenReturn(Map.of("payload", "test", "status", 200));

            // When
            triggerLoadingService.loadTriggerItemsIfNeeded(
                "run-1", executionTree, 0, "trigger:my_trigger", execution);

            // Then
            verify(contextManager).cacheTriggerItems(eq("run-1"), argThat(items ->
                items.size() == 1 && items.get(0).containsKey("payload")));
        }

        @Test
        @DisplayName("Should use chat trigger input if available")
        void shouldUseChatTriggerInputIfAvailable() {
            // Given
            Trigger trigger = createTrigger("t1", "Chat", "chat");
            Map<String, Object> chatInput = Map.of("message", "Hello");
            when(contextManager.hasTriggerItems("run-1")).thenReturn(false);
            when(executionTree.plan()).thenReturn(workflowPlan);
            when(executionTree.tenantId()).thenReturn("tenant-1");
            when(workflowPlan.getTriggers()).thenReturn(List.of(trigger));
            when(execution.getChatTriggerInput("trigger:chat")).thenReturn(chatInput);
            when(triggerResolverService.resolveTrigger(any(), any(), eq(chatInput)))
                .thenReturn(Map.of("items", List.of(Map.of("response", "Hi"))));

            // When
            triggerLoadingService.loadTriggerItemsIfNeeded(
                "run-1", executionTree, 0, "trigger:chat", execution);

            // Then
            verify(triggerResolverService).resolveTrigger(any(), any(), eq(chatInput));
        }

        @Test
        @DisplayName("Should cache empty list on error")
        void shouldCacheEmptyListOnError() {
            // Given
            Trigger trigger = createTrigger("t1", "My Trigger", "datasource");
            when(contextManager.hasTriggerItems("run-1")).thenReturn(false);
            when(executionTree.plan()).thenReturn(workflowPlan);
            when(executionTree.tenantId()).thenReturn("tenant-1");
            when(workflowPlan.getTriggers()).thenReturn(List.of(trigger));
            when(triggerResolverService.resolveTrigger(any(), any(), any()))
                .thenThrow(new RuntimeException("Connection error"));

            // When
            triggerLoadingService.loadTriggerItemsIfNeeded(
                "run-1", executionTree, 0, "trigger:my_trigger", execution);

            // Then
            verify(contextManager).cacheTriggerItems(eq("run-1"), argThat(List::isEmpty));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // isReusableTrigger() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("isReusableTrigger()")
    class IsReusableTriggerTests {

        @Test
        @DisplayName("Should return false for null plan")
        void shouldReturnFalseForNullPlan() {
            assertFalse(triggerLoadingService.isReusableTrigger(null, "trigger:webhook"));
        }

        @Test
        @DisplayName("Should return false for null triggers list")
        void shouldReturnFalseForNullTriggersList() {
            when(workflowPlan.getTriggers()).thenReturn(null);
            assertFalse(triggerLoadingService.isReusableTrigger(workflowPlan, "trigger:webhook"));
        }

        @Test
        @DisplayName("Should return false for non-trigger nodeId")
        void shouldReturnFalseForNonTriggerNodeId() {
            assertFalse(triggerLoadingService.isReusableTrigger(workflowPlan, "mcp:step1"));
        }

        @Test
        @DisplayName("Should return true for webhook trigger")
        void shouldReturnTrueForWebhookTrigger() {
            // Given
            Trigger trigger = createTrigger("t1", "My Webhook", "webhook");
            when(workflowPlan.getTriggers()).thenReturn(List.of(trigger));

            // When/Then
            assertTrue(triggerLoadingService.isReusableTrigger(workflowPlan, "trigger:my_webhook"));
        }

        @Test
        @DisplayName("Should return true for manual trigger")
        void shouldReturnTrueForManualTrigger() {
            // Given
            Trigger trigger = createTrigger("t1", "Manual Start", "manual");
            when(workflowPlan.getTriggers()).thenReturn(List.of(trigger));

            // When/Then
            assertTrue(triggerLoadingService.isReusableTrigger(workflowPlan, "trigger:manual_start"));
        }

        @Test
        @DisplayName("Should return true for chat trigger")
        void shouldReturnTrueForChatTrigger() {
            // Given
            Trigger trigger = createTrigger("t1", "Chat Bot", "chat");
            when(workflowPlan.getTriggers()).thenReturn(List.of(trigger));

            // When/Then
            assertTrue(triggerLoadingService.isReusableTrigger(workflowPlan, "trigger:chat_bot"));
        }

        @Test
        @DisplayName("Should return true for datasource trigger (reusable)")
        void shouldReturnTrueForDatasourceTrigger() {
            // Given - datasource triggers are reusable (can be re-run)
            Trigger trigger = createTrigger("t1", "Data Source", "datasource");
            when(workflowPlan.getTriggers()).thenReturn(List.of(trigger));

            // When/Then
            assertTrue(triggerLoadingService.isReusableTrigger(workflowPlan, "trigger:data_source"));
        }

        @Test
        @DisplayName("Should return true for schedule trigger (reusable)")
        void shouldReturnTrueForScheduleTrigger() {
            // Given - schedule triggers are reusable (can be re-triggered)
            Trigger trigger = createTrigger("t1", "Daily Job", "schedule");
            when(workflowPlan.getTriggers()).thenReturn(List.of(trigger));

            // When/Then
            assertTrue(triggerLoadingService.isReusableTrigger(workflowPlan, "trigger:daily_job"));
        }

        @Test
        @DisplayName("Should match trigger by normalized label")
        void shouldMatchTriggerByNormalizedLabel() {
            // Given
            Trigger trigger = createTrigger("t1", "My Special Webhook", "webhook");
            when(workflowPlan.getTriggers()).thenReturn(List.of(trigger));

            // When/Then - nodeId uses normalized format
            assertTrue(triggerLoadingService.isReusableTrigger(workflowPlan, "trigger:my_special_webhook"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getTriggerType() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getTriggerType()")
    class GetTriggerTypeTests {

        @Test
        @DisplayName("Should return null for null plan")
        void shouldReturnNullForNullPlan() {
            assertNull(triggerLoadingService.getTriggerType(null, "trigger:webhook"));
        }

        @Test
        @DisplayName("Should return null for null triggers list")
        void shouldReturnNullForNullTriggersList() {
            when(workflowPlan.getTriggers()).thenReturn(null);
            assertNull(triggerLoadingService.getTriggerType(workflowPlan, "trigger:webhook"));
        }

        @Test
        @DisplayName("Should return null for non-trigger nodeId")
        void shouldReturnNullForNonTriggerNodeId() {
            assertNull(triggerLoadingService.getTriggerType(workflowPlan, "mcp:step1"));
        }

        @Test
        @DisplayName("Should return trigger type for matching trigger")
        void shouldReturnTriggerTypeForMatchingTrigger() {
            // Given
            Trigger trigger = createTrigger("t1", "My Webhook", "webhook");
            when(workflowPlan.getTriggers()).thenReturn(List.of(trigger));

            // When/Then
            assertEquals("webhook", triggerLoadingService.getTriggerType(workflowPlan, "trigger:my_webhook"));
        }

        @Test
        @DisplayName("Should return datasource type")
        void shouldReturnDatasourceType() {
            // Given
            Trigger trigger = createTrigger("t1", "Data Source", "datasource");
            when(workflowPlan.getTriggers()).thenReturn(List.of(trigger));

            // When/Then
            assertEquals("datasource", triggerLoadingService.getTriggerType(workflowPlan, "trigger:data_source"));
        }

        @Test
        @DisplayName("Should return null for non-matching trigger")
        void shouldReturnNullForNonMatchingTrigger() {
            // Given
            Trigger trigger = createTrigger("t1", "Other Trigger", "webhook");
            when(workflowPlan.getTriggers()).thenReturn(List.of(trigger));

            // When/Then
            assertNull(triggerLoadingService.getTriggerType(workflowPlan, "trigger:nonexistent"));
        }

        @Test
        @DisplayName("Should match by trigger ID as fallback")
        void shouldMatchByTriggerIdAsFallback() {
            // Given - trigger with null label
            Trigger trigger = createTrigger("my_trigger_id", null, "manual");
            when(workflowPlan.getTriggers()).thenReturn(List.of(trigger));

            // When/Then - match by ID
            assertEquals("manual", triggerLoadingService.getTriggerType(workflowPlan, "trigger:my_trigger_id"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helper methods
    // ═══════════════════════════════════════════════════════════════════════════

    private Trigger createTrigger(String id, String label, String type) {
        Trigger trigger = mock(Trigger.class);
        lenient().when(trigger.id()).thenReturn(id);
        lenient().when(trigger.label()).thenReturn(label);
        lenient().when(trigger.type()).thenReturn(type);
        return trigger;
    }
}

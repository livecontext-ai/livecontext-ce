package com.apimarketplace.orchestrator.services.streaming.redis;

import com.apimarketplace.common.event.EventBus;
import com.apimarketplace.common.event.KeyValueStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowRedisPublisher")
class WorkflowRedisPublisherTest {

    @Mock
    private EventBus eventBus;

    @Mock
    private KeyValueStore keyValueStore;

    private WorkflowRedisPublisher publisher;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        publisher = new WorkflowRedisPublisher(eventBus, keyValueStore, objectMapper);
    }

    @Nested
    @DisplayName("publishEvent()")
    class PublishEventTests {

        @Test
        @DisplayName("Should publish to correct channel")
        void shouldPublishToCorrectChannel() {
            publisher.publishEvent("run-123", "batch-update", Map.of("status", "RUNNING"));

            ArgumentCaptor<String> channelCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
            verify(eventBus).publish(channelCaptor.capture(), messageCaptor.capture());

            assertThat(channelCaptor.getValue()).isEqualTo("ws:workflow:run:run-123");
        }

        @Test
        @DisplayName("Should include envelope fields in published message")
        void shouldIncludeEnvelopeFields() throws Exception {
            publisher.publishEvent("run-123", "node.status", Map.of("nodeId", "mcp:step1"));

            ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
            verify(eventBus).publish(anyString(), messageCaptor.capture());

            Map<String, Object> envelope = objectMapper.readValue(messageCaptor.getValue(), Map.class);
            assertThat(envelope).containsEntry("v", 1);
            assertThat(envelope).containsEntry("type", "node.status");
            assertThat(envelope).containsKey("id");
            assertThat(envelope).containsKey("ts");
            assertThat(envelope).containsKey("payload");
        }

        @Test
        @DisplayName("Should enrich Map payloads with type and runId for frontend routing")
        @SuppressWarnings("unchecked")
        void shouldEnrichMapPayloads() throws Exception {
            publisher.publishEvent("run-42", "workflowStatus", Map.of("status", "COMPLETED"));

            ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
            verify(eventBus).publish(anyString(), messageCaptor.capture());

            Map<String, Object> envelope = objectMapper.readValue(messageCaptor.getValue(), Map.class);
            Map<String, Object> payload = (Map<String, Object>) envelope.get("payload");

            assertThat(payload).containsEntry("type", "workflowStatus");
            assertThat(payload).containsEntry("runId", "run-42");
            assertThat(payload).containsEntry("status", "COMPLETED");
        }

        @Test
        @DisplayName("Should not override existing type and runId in payload")
        @SuppressWarnings("unchecked")
        void shouldNotOverrideExistingFields() throws Exception {
            Map<String, Object> data = new java.util.HashMap<>();
            data.put("type", "readySteps");
            data.put("runId", "original-run");
            data.put("readySteps", java.util.List.of("step1"));

            publisher.publishEvent("run-99", "readySteps", data);

            ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
            verify(eventBus).publish(anyString(), messageCaptor.capture());

            Map<String, Object> envelope = objectMapper.readValue(messageCaptor.getValue(), Map.class);
            Map<String, Object> payload = (Map<String, Object>) envelope.get("payload");

            // putIfAbsent should preserve the original values
            assertThat(payload).containsEntry("type", "readySteps");
            assertThat(payload).containsEntry("runId", "original-run");
        }

        @Test
        @DisplayName("Should skip when runId is null")
        void shouldSkipNullRunId() {
            publisher.publishEvent(null, "event", Map.of());
            verifyNoInteractions(eventBus);
        }

        @Test
        @DisplayName("Should handle EventBus errors gracefully")
        void shouldHandleEventBusError() {
            doThrow(new RuntimeException("EventBus down")).when(eventBus)
                    .publish(anyString(), anyString());

            assertDoesNotThrow(() -> publisher.publishEvent("run-1", "test", Map.of()));
        }
    }

    @Nested
    @DisplayName("publishSequenced() - Phase A2 archi-refoundation 2026-05-04")
    class PublishSequencedTests {

        @Test
        @DisplayName("Flat wire format: {seq, type, runId, ...inner-fields}, NOT nested {inner: {...}}")
        @SuppressWarnings("unchecked")
        void flatWireFormat() throws Exception {
            // Use a record-like POJO to mimic StepStatusEvent serialization
            Map<String, Object> innerLikeRecord = Map.of(
                    "runId", "run-flat",
                    "normalizedStepId", "mcp:step1",
                    "lifecycle", "SUCCESS",
                    "timestamp", 1234567890L
            );
            publisher.publishSequenced("run-flat", "StepStatusEvent", innerLikeRecord, 42L);

            ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
            verify(eventBus).publish(eq("ws:workflow:run:run-flat"), messageCaptor.capture());

            Map<String, Object> envelope = objectMapper.readValue(messageCaptor.getValue(), Map.class);
            Map<String, Object> payload = (Map<String, Object>) envelope.get("payload");

            // Critical: flat shape, NOT nested.
            assertThat(payload).containsEntry("seq", 42);
            assertThat(payload).containsEntry("type", "StepStatusEvent");
            assertThat(payload).containsEntry("runId", "run-flat");
            assertThat(payload).containsEntry("normalizedStepId", "mcp:step1");
            assertThat(payload).containsEntry("lifecycle", "SUCCESS");
            // No "inner" wrapper - frontend reads fields directly
            assertThat(payload).doesNotContainKey("inner");
        }

        @Test
        @DisplayName("seq, type, runId are authoritative - putIfAbsent does not override")
        @SuppressWarnings("unchecked")
        void seqTypeRunIdAuthoritative() throws Exception {
            // Pathological inner that tries to overwrite envelope fields
            Map<String, Object> rogueInner = new java.util.HashMap<>();
            rogueInner.put("seq", 999L);          // wrong
            rogueInner.put("type", "WrongType");  // wrong
            rogueInner.put("runId", "wrong");     // wrong
            rogueInner.put("payload", "data");

            publisher.publishSequenced("run-correct", "CorrectType", rogueInner, 1L);

            ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
            verify(eventBus).publish(anyString(), messageCaptor.capture());

            Map<String, Object> envelope = objectMapper.readValue(messageCaptor.getValue(), Map.class);
            Map<String, Object> payload = (Map<String, Object>) envelope.get("payload");

            // Authoritative envelope-set values must survive putIfAbsent of inner
            assertThat(payload).containsEntry("seq", 1);
            assertThat(payload).containsEntry("type", "CorrectType");
            assertThat(payload).containsEntry("runId", "run-correct");
            assertThat(payload).containsEntry("payload", "data");
        }

        @Test
        @DisplayName("Skips publish when runId is null")
        void skipsNullRunId() {
            publisher.publishSequenced(null, "Type", Map.of(), 1L);
            verifyNoInteractions(eventBus);
        }
    }

    @Nested
    @DisplayName("publishEvent(runId, type, payload, seq) - Phase A2 overload")
    class PublishEventWithSeqTests {

        @Test
        @DisplayName("Injects seq field into Map payloads when seq >= 0")
        @SuppressWarnings("unchecked")
        void injectsSeqWhenProvided() throws Exception {
            publisher.publishEvent("run-x", "readySteps", Map.of("readySteps", java.util.List.of("step1")), 17L);

            ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
            verify(eventBus).publish(anyString(), messageCaptor.capture());

            Map<String, Object> envelope = objectMapper.readValue(messageCaptor.getValue(), Map.class);
            Map<String, Object> payload = (Map<String, Object>) envelope.get("payload");
            assertThat(payload).containsEntry("seq", 17);
        }

        @Test
        @DisplayName("Omits seq field when seq < 0 (back-compat for un-plumbed callers)")
        @SuppressWarnings("unchecked")
        void omitsSeqWhenNegative() throws Exception {
            publisher.publishEvent("run-y", "legacy", new java.util.HashMap<>(Map.of("foo", "bar")), -1L);

            ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
            verify(eventBus).publish(anyString(), messageCaptor.capture());

            Map<String, Object> envelope = objectMapper.readValue(messageCaptor.getValue(), Map.class);
            Map<String, Object> payload = (Map<String, Object>) envelope.get("payload");
            assertThat(payload).doesNotContainKey("seq");
            assertThat(payload).containsEntry("foo", "bar");
        }
    }

    @Nested
    @DisplayName("publishSnapshot()")
    class PublishSnapshotTests {

        @Test
        @DisplayName("Should publish snapshot as batch-update event type")
        void shouldPublishAsBatchUpdate() throws Exception {
            Map<String, Object> snapshot = Map.of("nodes", Map.of(), "edges", Map.of());
            publisher.publishSnapshot("run-1", snapshot);

            ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
            verify(eventBus).publish(eq("ws:workflow:run:run-1"), messageCaptor.capture());

            Map<String, Object> envelope = objectMapper.readValue(messageCaptor.getValue(), Map.class);
            assertThat(envelope).containsEntry("type", "batch-update");
        }
    }

    @Nested
    @DisplayName("publishNotification()")
    class PublishNotificationTests {

        @Test
        @DisplayName("Should publish to user notification channel")
        void shouldPublishToUserChannel() {
            publisher.publishNotification("user-42", "workflow.completed",
                    Map.of("workflowName", "Test"));

            ArgumentCaptor<String> channelCaptor = ArgumentCaptor.forClass(String.class);
            verify(eventBus).publish(channelCaptor.capture(), anyString());

            assertThat(channelCaptor.getValue()).isEqualTo("ws:user:user-42:notifications");
        }

        @Test
        @DisplayName("Should skip when userId is null")
        void shouldSkipNullUserId() {
            publisher.publishNotification(null, "event", Map.of());
            verifyNoInteractions(eventBus);
        }

        @Test
        @DisplayName("Should include notification kind in envelope")
        void shouldIncludeKind() throws Exception {
            publisher.publishNotification("user-1", "approval.requested", Map.of());

            ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
            verify(eventBus).publish(anyString(), messageCaptor.capture());

            Map<String, Object> envelope = objectMapper.readValue(messageCaptor.getValue(), Map.class);
            assertThat(envelope).containsEntry("type", "approval.requested");
        }
    }

    @Nested
    @DisplayName("publishOrgNotification() - PR25")
    class PublishOrgNotificationTests {

        @Test
        @DisplayName("Should publish to ws:org:{orgId}:notifications channel")
        void shouldPublishToOrgChannel() {
            publisher.publishOrgNotification("org-acme", "notification.created",
                    Map.of("category", "TASK_APPROVAL_PENDING"));

            ArgumentCaptor<String> channelCaptor = ArgumentCaptor.forClass(String.class);
            verify(eventBus).publish(channelCaptor.capture(), anyString());

            assertThat(channelCaptor.getValue()).isEqualTo("ws:org:org-acme:notifications");
        }

        @Test
        @DisplayName("Should be no-op when orgId is null - closes the personal-scope leak guarantee")
        void shouldSkipNullOrgId() {
            // Load-bearing invariant: a personal-scope publish path passing
            // run.getOrgId() = null must NEVER reach the org channel.
            publisher.publishOrgNotification(null, "notification.created", Map.of());
            verifyNoInteractions(eventBus);
        }

        @Test
        @DisplayName("Should be no-op when orgId is blank")
        void shouldSkipBlankOrgId() {
            publisher.publishOrgNotification("   ", "notification.created", Map.of());
            verifyNoInteractions(eventBus);
        }

        @Test
        @DisplayName("Envelope shape matches user notification (kind + payload)")
        void envelopeShapeMatchesUserNotification() throws Exception {
            publisher.publishOrgNotification("org-acme", "notification.removed",
                    Map.of("category", "TASK_APPROVAL_PENDING"));

            ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
            verify(eventBus).publish(anyString(), messageCaptor.capture());

            Map<String, Object> envelope = objectMapper.readValue(messageCaptor.getValue(), Map.class);
            assertThat(envelope).containsEntry("type", "notification.removed");
            assertThat(envelope).containsKey("payload");
        }
    }

    @Nested
    @DisplayName("setAgentCancelSignal()")
    class SetAgentCancelSignalTests {

        @Test
        @DisplayName("Should set cancel key in KeyValueStore with TTL")
        void shouldSetCancelKey() {
            publisher.setAgentCancelSignal("run-123");

            verify(keyValueStore).set("workflow:cancel:run-123", "cancelled", Duration.ofHours(2));
        }

        @Test
        @DisplayName("Should skip when runId is null")
        void shouldSkipNullRunId() {
            publisher.setAgentCancelSignal(null);
            verifyNoInteractions(keyValueStore);
        }

        @Test
        @DisplayName("Should handle KeyValueStore errors gracefully")
        void shouldHandleKeyValueStoreError() {
            doThrow(new RuntimeException("Store down")).when(keyValueStore)
                    .set(anyString(), anyString(), any(Duration.class));

            assertDoesNotThrow(() -> publisher.setAgentCancelSignal("run-1"));
        }
    }

    @Nested
    @DisplayName("clearAgentCancelSignal()")
    class ClearAgentCancelSignalTests {

        @Test
        @DisplayName("Should delete cancel key from KeyValueStore")
        void shouldDeleteCancelKey() {
            publisher.clearAgentCancelSignal("run-123");
            verify(keyValueStore).delete("workflow:cancel:run-123");
        }

        @Test
        @DisplayName("Should skip when runId is null")
        void shouldSkipNullRunId() {
            publisher.clearAgentCancelSignal(null);
            verifyNoInteractions(keyValueStore);
        }
    }

    @Nested
    @DisplayName("F2.2 sub-workflow parent cancel cascade")
    class SubWorkflowParentCascadeTests {

        @Test
        @DisplayName("registerSubWorkflowParent writes workflow:parent:{child} → parent with TTL")
        void registersParentLink() {
            publisher.registerSubWorkflowParent("child-1", "parent-1");
            verify(keyValueStore).set("workflow:parent:child-1", "parent-1", Duration.ofHours(2));
        }

        @Test
        @DisplayName("registerSubWorkflowParent skips self-link (defensive - would create cycle)")
        void skipsSelfLink() {
            publisher.registerSubWorkflowParent("same", "same");
            verifyNoInteractions(keyValueStore);
        }

        @Test
        @DisplayName("registerSubWorkflowParent skips null inputs")
        void skipsNullInputs() {
            publisher.registerSubWorkflowParent(null, "p");
            publisher.registerSubWorkflowParent("c", null);
            verifyNoInteractions(keyValueStore);
        }

        @Test
        @DisplayName("isAgentCancelSignalSet - own key set → true (no parent walk needed)")
        void ownKeySetReturnsTrue() {
            when(keyValueStore.get("workflow:cancel:run-1")).thenReturn(java.util.Optional.of("cancelled"));

            assertThat(publisher.isAgentCancelSignalSet("run-1")).isTrue();
            // Don't walk parents when own cancel is hit - short-circuit.
            verify(keyValueStore, never()).get("workflow:parent:run-1");
        }

        @Test
        @DisplayName("isAgentCancelSignalSet - own key absent, parent cancelled → true (cascade)")
        void parentCancelCascadesToChild() {
            when(keyValueStore.get("workflow:cancel:child")).thenReturn(java.util.Optional.empty());
            when(keyValueStore.get("workflow:parent:child")).thenReturn(java.util.Optional.of("parent"));
            when(keyValueStore.get("workflow:cancel:parent")).thenReturn(java.util.Optional.of("cancelled"));

            assertThat(publisher.isAgentCancelSignalSet("child"))
                .as("parent cancel must cascade to child via workflow:parent walk")
                .isTrue();
        }

        @Test
        @DisplayName("isAgentCancelSignalSet - chain of 3 levels, deepest grandparent cancelled → true")
        void grandparentCancelCascadesTwoLevels() {
            when(keyValueStore.get("workflow:cancel:c")).thenReturn(java.util.Optional.empty());
            when(keyValueStore.get("workflow:parent:c")).thenReturn(java.util.Optional.of("p"));
            when(keyValueStore.get("workflow:cancel:p")).thenReturn(java.util.Optional.empty());
            when(keyValueStore.get("workflow:parent:p")).thenReturn(java.util.Optional.of("gp"));
            when(keyValueStore.get("workflow:cancel:gp")).thenReturn(java.util.Optional.of("cancelled"));

            assertThat(publisher.isAgentCancelSignalSet("c")).isTrue();
        }

        @Test
        @DisplayName("isAgentCancelSignalSet - no parent pointer, no cancel → false")
        void noCancelNoParentReturnsFalse() {
            when(keyValueStore.get("workflow:cancel:run-1")).thenReturn(java.util.Optional.empty());
            when(keyValueStore.get("workflow:parent:run-1")).thenReturn(java.util.Optional.empty());

            assertThat(publisher.isAgentCancelSignalSet("run-1")).isFalse();
        }

        @Test
        @DisplayName("isAgentCancelSignalSet - fail-open when KeyValueStore throws (Redis down)")
        void failOpenOnError() {
            when(keyValueStore.get(anyString())).thenThrow(new RuntimeException("redis-down"));
            assertThat(publisher.isAgentCancelSignalSet("run-1")).isFalse();
        }

        @Test
        @DisplayName("clearSubWorkflowParent deletes the pointer; tolerates errors")
        void clearParent() {
            publisher.clearSubWorkflowParent("child-1");
            verify(keyValueStore).delete("workflow:parent:child-1");

            doThrow(new RuntimeException("x")).when(keyValueStore).delete(anyString());
            assertDoesNotThrow(() -> publisher.clearSubWorkflowParent("child-2"));
        }

        @Test
        @DisplayName("isAgentCancelSignalSet - cycle a→b→a is bounded by MAX_PARENT_WALK, returns false, doesn't loop forever")
        void cycleInParentChainTerminates() {
            // No cancel set anywhere in the cycle
            when(keyValueStore.get("workflow:cancel:a")).thenReturn(java.util.Optional.empty());
            when(keyValueStore.get("workflow:parent:a")).thenReturn(java.util.Optional.of("b"));
            when(keyValueStore.get("workflow:cancel:b")).thenReturn(java.util.Optional.empty());
            when(keyValueStore.get("workflow:parent:b")).thenReturn(java.util.Optional.of("a"));

            // Must terminate (depth-bound) and return false; the test itself completing
            // proves we didn't infinite-loop.
            assertThat(publisher.isAgentCancelSignalSet("a")).isFalse();
            // Bounded GETs: at most ~22 (11 hops × 2 keys: cancel + parent)
            verify(keyValueStore, atMost(40)).get(anyString());
        }

        @Test
        @DisplayName("isAgentCancelSignalSet - cycle a→b→a still detects cancel at any node before depth cap")
        void cycleStillDetectsCancelOnAncestor() {
            when(keyValueStore.get("workflow:cancel:a")).thenReturn(java.util.Optional.empty());
            when(keyValueStore.get("workflow:parent:a")).thenReturn(java.util.Optional.of("b"));
            when(keyValueStore.get("workflow:cancel:b")).thenReturn(java.util.Optional.of("cancelled"));
            // (parent:b would loop back to a, but we should never get there because b is cancelled)

            assertThat(publisher.isAgentCancelSignalSet("a")).isTrue();
        }
    }
}

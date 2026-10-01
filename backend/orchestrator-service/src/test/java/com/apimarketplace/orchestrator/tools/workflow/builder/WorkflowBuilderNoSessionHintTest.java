package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.orchestrator.cache.RedisCacheKeys;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Regression (prod 2026-09, ~37 failures): add_node / get_plan / describe answered a bare
 * "No active session" once the conversation's builder session had closed (finish/discard) or
 * expired after its idle TTL, although the conversation had been editing a known workflow.
 * The message now names that workflow and the exact load call; it never auto-loads.
 */
@DisplayName("Builder 'no active session' names the workflow to reload")
class WorkflowBuilderNoSessionHintTest {

    private static final String TENANT = "tenant-1";
    private static final String CONV = "conv-1";
    private static final String WF_ID = "0b6f6a54-3f0e-4f67-a4f3-9f7f0c1d2e3f";

    @Nested
    @DisplayName("WorkflowBuilderSessionManager")
    class ManagerMessage {

        private WorkflowBuilderSessionStore store;
        private WorkflowBuilderSessionManager manager;

        @BeforeEach
        void setUp() {
            store = mock(WorkflowBuilderSessionStore.class);
            when(store.getSessionForConversation(TENANT, CONV)).thenReturn(Optional.empty());
            when(store.getSessionTtl()).thenReturn(Duration.ofMinutes(30));
            manager = new WorkflowBuilderSessionManager(store);
        }

        @Test
        @DisplayName("regression: a conversation that built a workflow gets its name, id and the load call")
        void namesLastWorkflowAndLoadCall() {
            when(store.getLastWorkflowForConversation(TENANT, CONV))
                    .thenReturn(Optional.of(new WorkflowBuilderSessionStore.LastWorkflow(WF_ID, "Invoice Triage")));

            var result = manager.getSession(Map.of(), TENANT, CONV);

            assertThat(result.isError()).isTrue();
            assertThat(result.error().errorCode()).isEqualTo(ToolErrorCode.RESOURCE_NOT_FOUND);
            assertThat(result.error().error())
                    .contains("'Invoice Triage' (" + WF_ID + ")")
                    .contains("If it still exists, reopen it with workflow(action='load', id='" + WF_ID + "')")
                    .contains("closes 30 minutes after its last change")
                    .contains("does not keep it open")
                    .doesNotContain("discard")
                    .contains("workflow(action='init')");
        }

        @Test
        @DisplayName("no remembered workflow: the original message, unchanged")
        void unknownLastWorkflow_keepsOriginalMessage() {
            when(store.getLastWorkflowForConversation(TENANT, CONV)).thenReturn(Optional.empty());

            var result = manager.getSession(Map.of(), TENANT, CONV);

            assertThat(result.error().error()).isEqualTo(WorkflowBuilderSessionManager.NO_SESSION_MESSAGE);
        }

        @Test
        @DisplayName("remembered workflow without a name: the id alone, still with the load call")
        void lastWorkflowWithoutName() {
            when(store.getLastWorkflowForConversation(TENANT, CONV))
                    .thenReturn(Optional.of(new WorkflowBuilderSessionStore.LastWorkflow(WF_ID, null)));

            var result = manager.getSession(Map.of(), TENANT, CONV);

            assertThat(result.error().error())
                    .contains("workflow " + WF_ID + " was closed")
                    .contains("workflow(action='load', id='" + WF_ID + "')");
        }

        @Test
        @DisplayName("an active session is returned as before and the hint is never read")
        void activeSession_noHintLookup() {
            WorkflowBuilderSession session = new WorkflowBuilderSession();
            when(store.getSessionForConversation(TENANT, CONV)).thenReturn(Optional.of(session));

            var result = manager.getSession(Map.of(), TENANT, CONV);

            assertThat(result.isSuccess()).isTrue();
            verify(store, never()).getLastWorkflowForConversation(any(), any());
        }
    }

    @Nested
    @DisplayName("WorkflowBuilderSessionStore")
    class StoreMemory {

        @SuppressWarnings("unchecked")
        private final RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        @SuppressWarnings("unchecked")
        private final ValueOperations<String, Object> values = mock(ValueOperations.class);
        @SuppressWarnings("unchecked")
        private final SetOperations<String, Object> sets = mock(SetOperations.class);
        private WorkflowBuilderSessionStore store;
        private final String lastKey = RedisCacheKeys.workflowBuilderConversationLastWorkflow(TENANT, CONV);

        @BeforeEach
        void setUp() {
            when(redis.opsForValue()).thenReturn(values);
            when(redis.opsForSet()).thenReturn(sets);
            store = new WorkflowBuilderSessionStore(redis, new ObjectMapper());
        }

        private WorkflowBuilderSession session(String loadedWorkflowId) {
            WorkflowBuilderSession s = new WorkflowBuilderSession();
            s.setSessionId("wb_1");
            s.setTenantId(TENANT);
            s.setConversationId(CONV);
            s.setWorkflowName("Invoice Triage");
            s.setLoadedWorkflowId(loadedWorkflowId);
            return s;
        }

        @Test
        @DisplayName("save remembers the conversation's workflow under a key that outlives the session")
        @SuppressWarnings("unchecked")
        void saveRemembersLastWorkflow() {
            store.save(session(WF_ID));

            var captor = org.mockito.ArgumentCaptor.forClass(Object.class);
            verify(values).set(eq(lastKey), captor.capture(), eq(Duration.ofHours(24)));
            assertThat((Map<String, Object>) captor.getValue())
                    .containsEntry("workflow_id", WF_ID)
                    .containsEntry("workflow_name", "Invoice Triage");
        }

        @Test
        @DisplayName("a session with no workflow yet writes no memory")
        void saveWithoutWorkflow_writesNothing() {
            store.save(session(null));

            verify(values, never()).set(eq(lastKey), any(), any(Duration.class));
        }

        @Test
        @DisplayName("delete (finish/discard) removes the session but keeps the memory")
        void deleteKeepsMemory() {
            when(values.get(RedisCacheKeys.workflowBuilderSession("wb_1"))).thenReturn(null);

            store.delete("wb_1");

            verify(redis, never()).delete(lastKey);
        }

        @Test
        @DisplayName("a failing memory write never fails the session save")
        void memoryWriteFailure_doesNotFailSave() {
            doThrow(new RuntimeException("redis down")).when(values)
                    .set(eq(lastKey), any(), any(Duration.class));

            store.save(session(WF_ID));

            // The session itself was written (its TTL is unset outside Spring, hence any()).
            verify(values).set(eq(RedisCacheKeys.workflowBuilderSession("wb_1")), any(), any());
        }

        @Test
        @DisplayName("forgetLastWorkflow drops the memory when it names the deleted workflow")
        void forgetDropsMatchingMemory() {
            when(values.get(lastKey)).thenReturn(Map.of("workflow_id", WF_ID));

            store.forgetLastWorkflow(TENANT, CONV, WF_ID);

            verify(redis).delete(lastKey);
        }

        @Test
        @DisplayName("forgetLastWorkflow matches the deleted id whatever its case (same UUID)")
        void forgetIsCaseInsensitive() {
            when(values.get(lastKey)).thenReturn(Map.of("workflow_id", WF_ID));

            store.forgetLastWorkflow(TENANT, CONV, WF_ID.toUpperCase());

            verify(redis).delete(lastKey);
        }

        @Test
        @DisplayName("the memory value survives the production Redis value serializer (typed JSON) and reads back")
        @SuppressWarnings("unchecked")
        void memoryValue_roundTripsThroughProductionSerializer() {
            // The serializer of the orchestrator's RedisTemplate, as configured in production
            // (default typing on), exercised without a server.
            RedisTemplate<String, Object> production = new com.apimarketplace.orchestrator.config.RedisCacheConfig()
                    .redisTemplate(mock(org.springframework.data.redis.connection.RedisConnectionFactory.class));
            var serializer = (org.springframework.data.redis.serializer.RedisSerializer<Object>) production.getValueSerializer();
            store.save(session(WF_ID));
            var captor = org.mockito.ArgumentCaptor.forClass(Object.class);
            verify(values).set(eq(lastKey), captor.capture(), eq(Duration.ofHours(24)));

            Object readBack = serializer.deserialize(serializer.serialize(captor.getValue()));
            when(values.get(lastKey)).thenReturn(readBack);

            assertThat(store.getLastWorkflowForConversation(TENANT, CONV))
                    .contains(new WorkflowBuilderSessionStore.LastWorkflow(WF_ID, "Invoice Triage"));
        }

        @Test
        @DisplayName("forgetLastWorkflow keeps the memory when another workflow was deleted")
        void forgetKeepsOtherWorkflowMemory() {
            when(values.get(lastKey)).thenReturn(Map.of("workflow_id", WF_ID));

            store.forgetLastWorkflow(TENANT, CONV, "another-workflow");

            verify(redis, never()).delete(lastKey);
        }

        @Test
        @DisplayName("reads the memory back; anything malformed is treated as unknown")
        void readsMemoryBack() {
            Map<String, Object> stored = new HashMap<>();
            stored.put("workflow_id", WF_ID);
            stored.put("workflow_name", "Invoice Triage");
            when(values.get(lastKey)).thenReturn(stored);

            assertThat(store.getLastWorkflowForConversation(TENANT, CONV))
                    .contains(new WorkflowBuilderSessionStore.LastWorkflow(WF_ID, "Invoice Triage"));

            when(values.get(lastKey)).thenReturn("not-a-map");
            assertThat(store.getLastWorkflowForConversation(TENANT, CONV)).isEmpty();
            assertThat(store.getLastWorkflowForConversation(TENANT, null)).isEmpty();
        }
    }
}

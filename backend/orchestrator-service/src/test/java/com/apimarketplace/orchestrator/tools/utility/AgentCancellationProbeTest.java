package com.apimarketplace.orchestrator.tools.utility;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.common.event.KeyValueStore;
import com.apimarketplace.orchestrator.services.streaming.redis.WorkflowRedisPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AgentCancellationProbe} - the "has my caller been
 * stopped?" check used by blocking tools (wait sleep, workflow wait_run).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentCancellationProbe")
class AgentCancellationProbeTest {

    @Mock WorkflowRedisPublisher workflowRedisPublisher;
    @Mock KeyValueStore keyValueStore;

    private AgentCancellationProbe probe;

    @BeforeEach
    void setUp() {
        probe = new AgentCancellationProbe(workflowRedisPublisher, keyValueStore);
    }

    private ToolExecutionContext contextWith(Map<String, Object> credentials) {
        return new ToolExecutionContext("tenant-1", credentials, Map.of(), Set.of(), null, null, null, null);
    }

    @Test
    @DisplayName("null context or credentials -> not cancelled, no Redis access")
    void nullContextIsNotCancelled() {
        assertThat(probe.isCallerCancelled(null)).isFalse();
        assertThat(probe.isCallerCancelled(
                new ToolExecutionContext("tenant-1", null, Map.of(), Set.of(), null, null, null, null))).isFalse();
        verifyNoInteractions(workflowRedisPublisher, keyValueStore);
    }

    @Test
    @DisplayName("no caller identity in credentials -> not cancelled")
    void noIdentityIsNotCancelled() {
        assertThat(probe.isCallerCancelled(contextWith(Map.of("other", "x")))).isFalse();
        verifyNoInteractions(workflowRedisPublisher, keyValueStore);
    }

    @Test
    @DisplayName("workflow path: run cancel signal set -> cancelled")
    void workflowRunCancelled() {
        when(workflowRedisPublisher.isAgentCancelSignalSet("run-1")).thenReturn(true);
        assertThat(probe.isCallerCancelled(contextWith(Map.of("__workflowRunId__", "run-1")))).isTrue();
        verifyNoInteractions(keyValueStore);
    }

    @Test
    @DisplayName("workflow path: no cancel signal and no conversation -> not cancelled")
    void workflowRunNotCancelled() {
        when(workflowRedisPublisher.isAgentCancelSignalSet("run-1")).thenReturn(false);
        assertThat(probe.isCallerCancelled(contextWith(Map.of("__workflowRunId__", "run-1")))).isFalse();
    }

    @Test
    @DisplayName("blank run id is ignored (no publisher call)")
    void blankRunIdIgnored() {
        assertThat(probe.isCallerCancelled(contextWith(Map.of("__workflowRunId__", "  ")))).isFalse();
        verify(workflowRedisPublisher, never()).isAgentCancelSignalSet(anyString());
    }

    @Test
    @DisplayName("chat path: stream resolved and cancel key present -> cancelled")
    void chatStreamCancelled() {
        when(keyValueStore.get("stream:conv:conv-1")).thenReturn(Optional.of("stream-9"));
        when(keyValueStore.get("agent:cancel:stream-9")).thenReturn(Optional.of("stopped_by_user"));
        assertThat(probe.isCallerCancelled(contextWith(Map.of("conversationId", "conv-1")))).isTrue();
    }

    @Test
    @DisplayName("chat path: stream resolved but no cancel key -> not cancelled")
    void chatStreamNotCancelled() {
        when(keyValueStore.get("stream:conv:conv-1")).thenReturn(Optional.of("stream-9"));
        when(keyValueStore.get("agent:cancel:stream-9")).thenReturn(Optional.empty());
        assertThat(probe.isCallerCancelled(contextWith(Map.of("conversationId", "conv-1")))).isFalse();
    }

    @Test
    @DisplayName("chat path: no active stream index -> not cancelled")
    void chatNoStreamIndex() {
        when(keyValueStore.get("stream:conv:conv-1")).thenReturn(Optional.empty());
        assertThat(probe.isCallerCancelled(contextWith(Map.of("conversationId", "conv-1")))).isFalse();
    }

    @Test
    @DisplayName("both identities present: workflow cancel wins without touching the chat keys")
    void workflowCancelShortCircuitsChatCheck() {
        when(workflowRedisPublisher.isAgentCancelSignalSet("run-1")).thenReturn(true);
        assertThat(probe.isCallerCancelled(contextWith(
                Map.of("__workflowRunId__", "run-1", "conversationId", "conv-1")))).isTrue();
        verifyNoInteractions(keyValueStore);
    }

    @Test
    @DisplayName("Redis failure on the chat path is fail-open (not cancelled)")
    void redisFailureFailsOpen() {
        when(keyValueStore.get("stream:conv:conv-1")).thenThrow(new IllegalStateException("redis down"));
        assertThat(probe.isCallerCancelled(contextWith(Map.of("conversationId", "conv-1")))).isFalse();
    }

    @Test
    @DisplayName("a throwing workflow publisher is fail-open too - the probe's guarantee must not depend on the publisher's internal catch")
    void workflowPublisherFailureFailsOpen() {
        when(workflowRedisPublisher.isAgentCancelSignalSet("run-1"))
                .thenThrow(new IllegalStateException("redis down"));
        assertThat(probe.isCallerCancelled(contextWith(Map.of("__workflowRunId__", "run-1")))).isFalse();
    }

    @Test
    @DisplayName("workflow path throwing does not prevent the chat path from detecting a cancel")
    void workflowFailureStillChecksChatPath() {
        when(workflowRedisPublisher.isAgentCancelSignalSet("run-1"))
                .thenThrow(new IllegalStateException("redis down"));
        when(keyValueStore.get("stream:conv:conv-1")).thenReturn(Optional.of("stream-9"));
        when(keyValueStore.get("agent:cancel:stream-9")).thenReturn(Optional.of("stopped_by_user"));
        assertThat(probe.isCallerCancelled(contextWith(
                Map.of("__workflowRunId__", "run-1", "conversationId", "conv-1")))).isTrue();
    }
}

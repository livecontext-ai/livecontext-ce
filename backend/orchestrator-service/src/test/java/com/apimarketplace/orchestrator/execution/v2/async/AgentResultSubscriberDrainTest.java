package com.apimarketplace.orchestrator.execution.v2.async;

import com.apimarketplace.orchestrator.domain.execution.AgentResultMessage;
import com.apimarketplace.orchestrator.lifecycle.OrchestratorLifecycleGate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A draining instance must only consume results for agents IT dispatched: consuming another
 * replica's result would stage a delivery here after the drain may have stopped counting,
 * and the shutdown would cut it.
 */
@DisplayName("AgentResultSubscriber - draining instance")
class AgentResultSubscriberDrainTest {

    private final AgentAsyncCompletionService completion = mock(AgentAsyncCompletionService.class);
    private final PendingAgentRegistry registry = mock(PendingAgentRegistry.class);
    private final OrchestratorLifecycleGate gate =
        new OrchestratorLifecycleGate(null, "test", Duration.ofSeconds(60), Clock.systemUTC());
    private final AgentResultSubscriber subscriber = new AgentResultSubscriber(
        mock(RedisMessageListenerContainer.class), completion, new ObjectMapper(), gate, registry);

    private static DefaultMessage resultFor(String correlationId) {
        return new DefaultMessage(
            ("agent:result:channel:" + correlationId).getBytes(StandardCharsets.UTF_8),
            "{\"success\":true,\"result\":{\"text\":\"ok\"}}".getBytes(StandardCharsets.UTF_8));
    }

    private static PendingAgent localPending(String correlationId) {
        return new PendingAgent(correlationId, "run_1", "agent:a", "A", "trigger:t",
            1, 0, null, "agent", "tenant-1", null, null, null, null, null, "model", null, null,
            Instant.now(), "org-1");
    }

    @Test
    @DisplayName("not draining: every result is handed to the completion service (unchanged)")
    void consumesWhenNotDraining() {
        subscriber.onMessage(resultFor("cid-foreign"), null);

        ArgumentCaptor<AgentResultMessage> captor = ArgumentCaptor.forClass(AgentResultMessage.class);
        verify(completion).onAgentResult(captor.capture());
        assertThat(captor.getValue().correlationId()).isEqualTo("cid-foreign");
    }

    @Test
    @DisplayName("draining: another replica's result is left alone (regression: staged after the drain exited)")
    void drainingSkipsForeignResult() {
        gate.enterDraining();
        when(registry.peek("cid-foreign")).thenReturn(Optional.empty());

        subscriber.onMessage(resultFor("cid-foreign"), null);

        verify(completion, never()).onAgentResult(any());
    }

    @Test
    @DisplayName("draining: a result for an agent this pod dispatched is still consumed (the drain is waiting on it)")
    void drainingConsumesOwnResult() {
        gate.enterDraining();
        when(registry.peek("cid-mine")).thenReturn(Optional.of(localPending("cid-mine")));

        subscriber.onMessage(resultFor("cid-mine"), null);

        verify(completion).onAgentResult(any());
    }
}

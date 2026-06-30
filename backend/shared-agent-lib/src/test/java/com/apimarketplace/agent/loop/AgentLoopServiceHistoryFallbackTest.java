package com.apimarketplace.agent.loop;

import com.apimarketplace.agent.domain.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pin-test for the {@code synthesiseHistoryFallback} symmetry between
 * {@link AgentLoopService} (API direct path) and
 * {@code BridgeLoopDispatcher.convertResponse} (bridge path).
 *
 * <p>Without this fallback, ~17% of guardrail/classify runs against Gemini
 * landed in {@code agent.agent_executions} with 0 rows in
 * {@code agent.agent_execution_messages} - the streaming path produced
 * visible content but never appended a {@link Message} to
 * {@link LoopExecutionState#getMessages()}, so the side-panel conversation
 * view in Agent Performance was empty.
 *
 * <p>The bridge path has had this fallback since day one
 * ({@code BridgeLoopDispatcher.java:257-260}). This test pins the API
 * direct path's matching behavior so the two paths can no longer drift.
 */
@DisplayName("AgentLoopService - history fallback (Gemini empty-history regression)")
class AgentLoopServiceHistoryFallbackTest {

    /**
     * Reflectively access the private static helper. We don't go through
     * the full {@link AgentLoopService#executeStreaming} because that
     * would require booting a full Spring context with a real provider -
     * the fallback is a pure function and is best tested directly.
     */
    @SuppressWarnings("unchecked")
    private static List<Message> invokeFallback(List<Message> history, String content) throws Exception {
        Method m = AgentLoopService.class
            .getDeclaredMethod("synthesiseHistoryFallback", List.class, String.class);
        m.setAccessible(true);
        return (List<Message>) m.invoke(null, history, content);
    }

    @Test
    @DisplayName("non-empty history is returned verbatim - no synthesis when the loop already captured messages")
    void preservesExistingHistory() throws Exception {
        List<Message> history = List.of(new Message(Message.Role.USER, "hi", null, null, null, null));
        List<Message> out = invokeFallback(history, "Hello!");
        assertThat(out).isSameAs(history);
    }

    @Test
    @DisplayName("empty history + non-blank content → synthesises a single ASSISTANT message (mirrors BridgeLoopDispatcher line 258-260)")
    void synthesisesAssistantWhenHistoryEmpty() throws Exception {
        List<Message> out = invokeFallback(Collections.emptyList(), "Final answer text");
        assertThat(out).hasSize(1);
        Message m = out.get(0);
        assertThat(m.role()).isEqualTo(Message.Role.ASSISTANT);
        assertThat(m.content()).isEqualTo("Final answer text");
    }

    @Test
    @DisplayName("null history + non-blank content → synthesises ASSISTANT (defensive null-safety)")
    void handlesNullHistory() throws Exception {
        List<Message> out = invokeFallback(null, "Output");
        assertThat(out).hasSize(1);
        assertThat(out.get(0).role()).isEqualTo(Message.Role.ASSISTANT);
    }

    @Test
    @DisplayName("empty history + blank content → empty list, no fake ASSISTANT message")
    void doesNotSynthesiseWhenContentBlank() throws Exception {
        assertThat(invokeFallback(Collections.emptyList(), "")).isEmpty();
        assertThat(invokeFallback(Collections.emptyList(), "   ")).isEmpty();
        assertThat(invokeFallback(Collections.emptyList(), null)).isEmpty();
    }

    @Test
    @DisplayName("null history + blank content → empty list (no NPE, no synthesis)")
    void handlesBothNull() throws Exception {
        assertThat(invokeFallback(null, null)).isEmpty();
    }
}

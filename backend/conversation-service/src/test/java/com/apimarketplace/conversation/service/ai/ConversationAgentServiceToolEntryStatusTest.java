package com.apimarketplace.conversation.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts the contract of {@link ConversationAgentService#buildToolCallEntry}
 * against the production method (via reflection) - NOT a re-implementation.
 *
 * <p>The frontend (MessageHistory.tsx hydratedStatus + ActivityFeed.tsx
 * lastPendingActivity) reads the {@code status} field from the persisted
 * toolCalls JSON to decide whether the chat header should show the shimmer
 * "Thinking…" or "Reasoning for X". Any drift in this method directly causes
 * UI regressions, so we exercise the real bytecode.
 */
@DisplayName("ConversationAgentService.buildToolCallEntry - status field contract")
class ConversationAgentServiceToolEntryStatusTest {

    private ConversationAgentService service;
    private Method buildToolCallEntry;

    @BeforeEach
    void setUp() throws Exception {
        // All dependencies are unused by buildToolCallEntry except objectMapper.
        // Mockito.mock keeps the constructor happy without a Spring context.
        service = new ConversationAgentService(
            Mockito.mock(com.apimarketplace.conversation.service.ai.callback.AgentContextBuilder.class),
            Mockito.mock(AgentObservabilityClient.class),
            Mockito.mock(AgentConfigProvider.class),
            Mockito.mock(com.apimarketplace.common.credit.CreditConsumptionClient.class),
            Mockito.mock(com.apimarketplace.conversation.service.MessageService.class),
            Mockito.mock(com.apimarketplace.conversation.service.PendingActionService.class),
            Mockito.mock(com.apimarketplace.conversation.service.ToolResultService.class),
            new ObjectMapper(),
            Mockito.mock(com.apimarketplace.agent.client.AgentClient.class),
            Mockito.mock(com.apimarketplace.conversation.streaming.StreamStateService.class),
            Mockito.mock(com.apimarketplace.common.event.EventBus.class),
            Mockito.mock(com.apimarketplace.conversation.service.ai.schema.HelpSeenRegistry.class),
            Mockito.mock(com.apimarketplace.conversation.repository.MessageRepository.class),
            "http://localhost:8087"
        );
        buildToolCallEntry = ConversationAgentService.class.getDeclaredMethod(
            "buildToolCallEntry", String.class, String.class, Object.class, Map.class, String.class, Object.class);
        buildToolCallEntry.setAccessible(true);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invoke(Map<String, Object> toolResult) throws Exception {
        return (Map<String, Object>) buildToolCallEntry.invoke(
            service, "call-1", "image_generation", "{}", toolResult, null, 1700000000000L);
    }

    @Test
    @DisplayName("Resolved tool result with success=true → status='success'")
    void successfulResultWritesSuccessStatus() throws Exception {
        Map<String, Object> entry = invoke(Map.of("success", true));
        assertThat(entry)
            .containsEntry("success", true)
            .containsEntry("status", "success");
    }

    @Test
    @DisplayName("Resolved tool result with success=false → status='error'")
    void failedResultWritesErrorStatus() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("error", "boom");
        Map<String, Object> entry = invoke(result);
        assertThat(entry)
            .containsEntry("success", false)
            .containsEntry("status", "error")
            .containsEntry("error", "boom");
    }

    @Test
    @DisplayName("Missing tool result (turn interrupted before tool ran) → status='interrupted', not 'pending' (regression: chat header was stuck on shimmer 'Thinking…' after refresh because frontend lastPendingActivity treated persisted 'pending' as still in-flight)")
    void interruptedTurnWritesInterruptedStatus() throws Exception {
        Map<String, Object> entry = invoke(null);
        assertThat(entry)
            .as("orphan tool_call must NOT be persisted as 'pending' - otherwise MessageHistory.tsx hydration sees status='pending' and ActivityFeed.tsx renders the shimmer header forever")
            .containsEntry("status", "interrupted")
            .containsEntry("success", false);
        assertThat(entry)
            .as("no synthetic error string - the tool was never invoked, so claiming it errored would mislead readers and trigger red-error rendering paths")
            .doesNotContainKey("error");
    }

    @Test
    @DisplayName("Missing tool result still preserves toolCallId, toolName, arguments, timestamp")
    void interruptedTurnPreservesIdentifiers() throws Exception {
        Map<String, Object> entry = invoke(null);
        assertThat(entry)
            .containsEntry("id", "call-1")
            .containsEntry("toolName", "image_generation")
            .containsEntry("arguments", "{}")
            .containsEntry("timestamp", 1700000000000L);
    }
}

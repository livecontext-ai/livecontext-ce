package com.apimarketplace.conversation.service;

import com.apimarketplace.common.classification.RestrictedDataPolicy;
import com.apimarketplace.conversation.service.ai.AgentConfigProvider;
import com.apimarketplace.conversation.service.ai.AgentObservabilityClient;
import com.apimarketplace.conversation.service.ai.ConversationAgentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CASA LC-066, re-audit 2026-09-29: a "Connect Gmail" card reads nothing, and its tool RESULT was
 * already classified NORMAL, but the assistant MESSAGE storing the card was still tagged RESTRICTED:
 * the stored tool-call entry kept the card's {@code iconSlug} and dropped its
 * {@code credentialNeeded} flag, and the message classification read text fields only. A user
 * asked to connect Gmail then had every later turn on a non-allow-listed model refused, for a
 * mailbox that was never opened. Runs the two production methods end to end: the entry the chat
 * stores, then the classification of that stored JSON.
 */
@DisplayName("A Connect card never tags the message that stores it (LC-066)")
class ConnectCardMessageSensitivityTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ConversationAgentService agentService;
    private Method buildToolCallEntry;
    private MessageService messageService;

    @BeforeEach
    void setUp() throws Exception {
        agentService = new ConversationAgentService(
            Mockito.mock(com.apimarketplace.conversation.service.ai.callback.AgentContextBuilder.class),
            Mockito.mock(AgentObservabilityClient.class),
            Mockito.mock(AgentConfigProvider.class),
            Mockito.mock(com.apimarketplace.common.credit.CreditConsumptionClient.class),
            Mockito.mock(MessageService.class),
            Mockito.mock(PendingActionService.class),
            Mockito.mock(ToolResultService.class),
            objectMapper,
            Mockito.mock(com.apimarketplace.agent.client.AgentClient.class),
            Mockito.mock(com.apimarketplace.conversation.streaming.StreamStateService.class),
            Mockito.mock(com.apimarketplace.common.event.EventBus.class),
            Mockito.mock(com.apimarketplace.conversation.service.ai.schema.HelpSeenRegistry.class),
            Mockito.mock(com.apimarketplace.conversation.repository.MessageRepository.class),
            "http://localhost:8087");
        buildToolCallEntry = ConversationAgentService.class.getDeclaredMethod(
            "buildToolCallEntry", String.class, String.class, Object.class, Map.class, String.class, Object.class);
        buildToolCallEntry.setAccessible(true);
        messageService = new MessageService(null, null, null, null, null, objectMapper, null, null, null);
    }

    private String storedToolCalls(Map<String, Object> resultMetadata) throws Exception {
        Object entry = buildToolCallEntry.invoke(agentService, "call-1", "credential", "{}",
            Map.of("success", true, "metadata", resultMetadata), null, 1700000000000L);
        return objectMapper.writeValueAsString(List.of(entry));
    }

    @Test
    @DisplayName("regression: the message storing a Gmail Connect card is not classified restricted")
    void connectCardMessageStaysNormal() throws Exception {
        String toolCalls = storedToolCalls(Map.of(
            "serviceApprovalRequested", true,
            RestrictedDataPolicy.CREDENTIAL_NEEDED_KEY, true,
            "iconSlug", "gmail",
            "toolName", "Gmail"));

        assertThat(toolCalls).contains(RestrictedDataPolicy.CREDENTIAL_NEEDED_KEY);
        assertThat(messageService.toolCallsMentionRestricted(toolCalls)).isFalse();
    }

    @Test
    @DisplayName("a Gmail tool call that did read the mailbox still tags its message")
    void gmailReadStillTagsTheMessage() throws Exception {
        String toolCalls = storedToolCalls(Map.of("iconSlug", "gmail", "toolName", "list_messages"));

        assertThat(messageService.toolCallsMentionRestricted(toolCalls)).isTrue();
    }

    @Test
    @DisplayName("only a true flag exempts: false, or a string, keeps the entry restricted")
    void onlyATrueFlagExempts() {
        assertThat(messageService.toolCallsMentionRestricted(
            "[{\"iconSlug\":\"gmail\",\"credentialNeeded\":false}]")).isTrue();
        assertThat(messageService.toolCallsMentionRestricted(
            "[{\"iconSlug\":\"gmail\",\"credentialNeeded\":\"true\"}]")).isTrue();
    }
}

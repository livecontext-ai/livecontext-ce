package com.apimarketplace.conversation.service.ai;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.client.dto.execution.AgentExecutionResponseDto;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.common.event.EventBus;
import com.apimarketplace.conversation.repository.MessageRepository;
import com.apimarketplace.conversation.service.MessageService;
import com.apimarketplace.conversation.service.PendingActionService;
import com.apimarketplace.conversation.service.ToolResultService;
import com.apimarketplace.conversation.service.ai.callback.AgentContextBuilder;
import com.apimarketplace.conversation.service.ai.schema.HelpSeenRegistry;
import com.apimarketplace.conversation.streaming.StreamStateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Regression for the bridge-path live approval/authorization WS events
 * ({@code ConversationAgentService.emitPendingApprovalIfPresent}).
 *
 * <p>The bridge path persisted the pending action for BOTH service approval and tool
 * authorization (so both cards showed on refresh), but only EMITTED the real-time
 * {@code service_approval_required} event - the {@code tool_authorization_required}
 * live emit was missing, so the ToolAuthorizationCard only appeared after a refresh on
 * the bridge (Claude Code) path. The frontend types these events purely from the payload
 * shape: {@code 'toolAuthorization' in data} → tool_authorization_required;
 * {@code 'services' in data && 'reason' in data} → service_approval_required
 * (see frontend streamHelpers.ts). These tests pin both payload shapes + the channel.</p>
 */
@DisplayName("ConversationAgentService.emitPendingApprovalIfPresent - live bridge WS events")
class ConversationAgentServiceToolAuthorizationEmitTest {

    private EventBus eventBus;
    private ConversationAgentService service;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        eventBus = mock(EventBus.class);
        service = new ConversationAgentService(
            mock(AgentContextBuilder.class),
            mock(AgentObservabilityClient.class),
            mock(AgentConfigProvider.class),
            mock(CreditConsumptionClient.class),
            mock(MessageService.class),
            mock(PendingActionService.class),
            mock(ToolResultService.class),
            new ObjectMapper(),
            mock(AgentClient.class),
            mock(StreamStateService.class),
            eventBus,
            mock(HelpSeenRegistry.class),
            mock(MessageRepository.class),
            "http://localhost:8087"
        );
    }

    private static AgentExecutionResponseDto responseWithMetadata(Map<String, Object> metadata) {
        Map<String, Object> toolResult = new HashMap<>();
        toolResult.put("metadata", metadata);
        return responseWithToolResults(List.of(toolResult));
    }

    private static AgentExecutionResponseDto responseWithToolResults(List<Map<String, Object>> toolResults) {
        return new AgentExecutionResponseDto(
            true, null, null, toolResults, 0, null, null, 0L, null, null,
            null, null, null, null, null, null, null, null, null);
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("toolAuthorizationRequired → emits a live tool_authorization_required payload (the missing bridge emit)")
    void emitsToolAuthorizationLive() throws Exception {
        Map<String, Object> meta = new HashMap<>();
        meta.put("toolAuthorizationRequired", true);
        meta.put("rule", "application:acquire");
        meta.put("toolName", "application");
        meta.put("action", "acquire");
        meta.put("toolCallId", "call_42");
        meta.put("argsSummary", "acquire publication 42");
        meta.put("applicationId", "pub-42");

        service.emitPendingApprovalIfPresent("conv-1", responseWithMetadata(meta));

        ArgumentCaptor<String> channel = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(eventBus).publish(channel.capture(), payload.capture());

        assertThat(channel.getValue()).isEqualTo("ws:conversation:conv-1");
        Map<String, Object> event = mapper.readValue(payload.getValue(), Map.class);
        // Frontend discriminant for tool_authorization_required + NOT service approval.
        assertThat(event).containsKey("toolAuthorization").doesNotContainKey("services");
        Map<String, Object> auth = (Map<String, Object>) event.get("toolAuthorization");
        assertThat(auth.get("rule")).isEqualTo("application:acquire");
        assertThat(auth.get("toolName")).isEqualTo("application");
        assertThat(auth.get("action")).isEqualTo("acquire");
        assertThat(auth.get("toolCallId")).isEqualTo("call_42");
        assertThat(auth.get("argsSummary")).isEqualTo("acquire publication 42");
        assertThat(auth.get("applicationId")).isEqualTo("pub-42");
        assertThat(event).containsKey("timestamp");
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("both flags on one result → service approval wins (matches persist ordering), exactly one event")
    void bothFlagsServiceApprovalWins() throws Exception {
        Map<String, Object> meta = new HashMap<>();
        meta.put("serviceApprovalRequested", true);
        meta.put("services", List.of(Map.of("serviceType", "gmail", "serviceName", "Gmail")));
        meta.put("reason", "Connect Gmail");
        meta.put("toolAuthorizationRequired", true);
        meta.put("rule", "application:acquire");

        service.emitPendingApprovalIfPresent("conv-5", responseWithMetadata(meta));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        // Exactly one event, and it is the service-approval one (checked first, like persist).
        verify(eventBus, times(1)).publish(eq("ws:conversation:conv-5"), payload.capture());
        Map<String, Object> event = mapper.readValue(payload.getValue(), Map.class);
        assertThat(event).containsKey("services").doesNotContainKey("toolAuthorization");
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("regression: serviceApprovalRequested still emits a live service_approval_required payload")
    void emitsServiceApprovalLive() throws Exception {
        Map<String, Object> meta = new HashMap<>();
        meta.put("serviceApprovalRequested", true);
        meta.put("services", List.of(Map.of("serviceType", "gmail", "serviceName", "Gmail")));
        meta.put("reason", "Connect Gmail to continue");

        service.emitPendingApprovalIfPresent("conv-2", responseWithMetadata(meta));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(eventBus).publish(eq("ws:conversation:conv-2"), payload.capture());
        Map<String, Object> event = mapper.readValue(payload.getValue(), Map.class);
        // Frontend discriminant for service_approval_required + NOT tool authorization.
        assertThat(event).containsKey("services").containsKey("reason").doesNotContainKey("toolAuthorization");
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("distinct credential approvals in one bridge response all emit so the frontend can merge them")
    void emitsDistinctCredentialApprovalsForFrontendMerge() throws Exception {
        Map<String, Object> gmail = new HashMap<>();
        gmail.put("serviceApprovalRequested", true);
        gmail.put("services", List.of(Map.of("serviceType", "gmail", "serviceName", "Gmail")));
        gmail.put("reason", "Connect Gmail");

        Map<String, Object> slack = new HashMap<>();
        slack.put("serviceApprovalRequested", true);
        slack.put("services", List.of(Map.of("serviceType", "slack", "serviceName", "Slack")));
        slack.put("reason", "Connect Slack");

        service.emitPendingApprovalIfPresent("conv-6", responseWithToolResults(List.of(
            Map.of("metadata", gmail),
            Map.of("metadata", slack)
        )));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(eventBus, times(2)).publish(eq("ws:conversation:conv-6"), payload.capture());

        List<String> serviceTypes = payload.getAllValues().stream()
            .map(json -> {
                try {
                    Map<String, Object> event = mapper.readValue(json, Map.class);
                    List<Map<String, Object>> services = (List<Map<String, Object>>) event.get("services");
                    return String.valueOf(services.get(0).get("serviceType"));
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            })
            .toList();
        assertThat(serviceTypes).containsExactlyInAnyOrder("gmail", "slack");
    }

    @Test
    @DisplayName("toolAuthorizationRequired without a rule -> no event (incomplete metadata)")
    void noEventWhenRuleMissing() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("toolAuthorizationRequired", true); // no rule

        service.emitPendingApprovalIfPresent("conv-3", responseWithMetadata(meta));
        verifyNoInteractions(eventBus);
    }

    @Test
    @DisplayName("no approval/authorization metadata → no event")
    void noEventWhenNoApprovalMetadata() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("somethingElse", true);

        service.emitPendingApprovalIfPresent("conv-4", responseWithMetadata(meta));
        verifyNoInteractions(eventBus);
    }

    @Test
    @DisplayName("null conversationId → no event (cannot address a WS channel)")
    void noEventWhenConversationIdNull() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("toolAuthorizationRequired", true);
        meta.put("rule", "application:acquire");

        service.emitPendingApprovalIfPresent(null, responseWithMetadata(meta));
        verifyNoInteractions(eventBus);
    }
}

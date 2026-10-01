package com.apimarketplace.conversation.service.ai;

import com.apimarketplace.agent.client.dto.execution.AgentExecutionRequestDto;
import com.apimarketplace.agent.loop.AgentLoopContext;
import com.apimarketplace.conversation.service.approval.ToolApprovalGateResolver;
import com.apimarketplace.conversation.service.approval.ToolAuthorizationApprovalService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A card's "don't ask again" lifts the gate for the turn that was running when it was ticked.
 * The NEXT turn must drop that running-turn grant and live by the persisted setting only,
 * which is what lets switching the toggle off afterwards actually bring the cards back.
 */
@DisplayName("ConversationAgentService - a new turn drops the running-turn \"don't ask again\" grant")
class ConversationAgentServiceConversationWideGrantTest {

    private static AgentExecutionRequestDto buildRequest(ConversationAgentService service) throws Exception {
        Method m = ConversationAgentService.class.getDeclaredMethod(
            "buildExecutionRequest", AgentLoopContext.class, String.class, String.class,
            Double.class, String.class, String.class, String.class);
        m.setAccessible(true);
        AgentLoopContext context = AgentLoopContext.builder().userPrompt("hi").build();
        return (AgentExecutionRequestDto) m.invoke(service, context, "stream-1", "conv-1", null, null, "chat", "exec-1");
    }

    @Test
    @DisplayName("the turn reads its grants, THEN deletes the running-turn key")
    void newTurnClearsTheRunningTurnGrantAfterReadingItsOwn() throws Exception {
        ConversationAgentService service = mock(ConversationAgentService.class, CALLS_REAL_METHODS);
        ToolAuthorizationApprovalService approvals = mock(ToolAuthorizationApprovalService.class);
        ToolApprovalGateResolver resolver = mock(ToolApprovalGateResolver.class);
        when(approvals.resolveAndConsumeForTurn("conv-1")).thenReturn(List.of("*"));
        ReflectionTestUtils.setField(service, "toolAuthorizationApprovalService", approvals);
        ReflectionTestUtils.setField(service, "toolApprovalGateResolver", resolver);

        AgentExecutionRequestDto dto = buildRequest(service);

        InOrder order = inOrder(approvals, resolver);
        order.verify(approvals).resolveAndConsumeForTurn("conv-1");
        order.verify(resolver).clearConversationWideForRunningTurn("conv-1");
        // The persisted setting still reaches this turn: clearing the key costs it nothing.
        assertThat(dto.credentials()).containsEntry("__approvedToolActions__", List.of("*"));
    }

    @Test
    @DisplayName("without a resolver (no Redis) the turn is built as before")
    void noResolverIsTolerated() throws Exception {
        ConversationAgentService service = mock(ConversationAgentService.class, CALLS_REAL_METHODS);
        ToolAuthorizationApprovalService approvals = mock(ToolAuthorizationApprovalService.class);
        when(approvals.resolveAndConsumeForTurn("conv-1")).thenReturn(List.of());
        ReflectionTestUtils.setField(service, "toolAuthorizationApprovalService", approvals);

        AgentExecutionRequestDto dto = buildRequest(service);

        assertThat(dto).isNotNull();
    }

    @Test
    @DisplayName("no approval service wired: nothing is read and nothing is cleared")
    void noApprovalServiceClearsNothing() throws Exception {
        ConversationAgentService service = mock(ConversationAgentService.class, CALLS_REAL_METHODS);
        ToolApprovalGateResolver resolver = mock(ToolApprovalGateResolver.class);
        ReflectionTestUtils.setField(service, "toolApprovalGateResolver", resolver);

        buildRequest(service);

        verify(resolver, never()).clearConversationWideForRunningTurn(anyString());
    }
}

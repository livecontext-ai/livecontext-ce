package com.apimarketplace.monolith.chat;

import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.conversation.dto.ChatRequest;
import com.apimarketplace.conversation.dto.MessageDto;
import com.apimarketplace.conversation.service.ConversationQueryService;
import com.apimarketplace.conversation.service.MessageService;
import com.apimarketplace.conversation.service.ai.AgentObservabilityClient;
import com.apimarketplace.conversation.service.ai.ConversationAgentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("MonolithInternalChatController")
class MonolithInternalChatControllerTest {

    private final MessageService messageService = mock(MessageService.class);
    private final ConversationAgentService agentService = mock(ConversationAgentService.class);
    private final CreditConsumptionClient creditClient = mock(CreditConsumptionClient.class);
    private final AgentObservabilityClient observabilityClient = mock(AgentObservabilityClient.class);
    private final ConversationQueryService conversationQueryService = mock(ConversationQueryService.class);
    private final MonolithInternalChatController controller =
        new MonolithInternalChatController(messageService, agentService, creditClient, observabilityClient,
            conversationQueryService);

    @Test
    @DisplayName("CE sync chat endpoint preserves ConversationClient task execution contract")
    void ceSyncChatEndpointPreservesConversationClientTaskExecutionContract() {
        ChatRequest request = new ChatRequest();
        request.setConversationId("conv-1");
        request.setMessage("Run the CE task");
        request.setModel("deepseek-chat");
        request.setProvider("deepseek");
        request.setSource("TASK");
        request.setTaskId("task-1");

        // The controller asks the source-type-scoped 4-arg gate (provider + model), not the
        // 2-arg overload this test used to stub (a mock answers false to the unstubbed call).
        when(creditClient.checkCredits("tenant-42", "CHAT_CONVERSATION", "deepseek", "deepseek-chat")).thenReturn(true);
        when(conversationQueryService.isConversationInStrictScope("conv-1", "tenant-42", "org-1")).thenReturn(true);
        when(agentService.executeSync(request, "conv-1"))
            .thenReturn(Map.of("success", true, "content", "done", "conversationId", "conv-1"));

        var response = controller.chatSync(request, "tenant-42", "org-1", null, null);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody())
            .containsEntry("success", true)
            .containsEntry("content", "done")
            .containsEntry("conversationId", "conv-1");
        assertThat(request.getUserId()).isEqualTo("tenant-42");
        assertThat(request.getOrgId()).isEqualTo("org-1");

        ArgumentCaptor<MessageDto> messageCaptor = ArgumentCaptor.forClass(MessageDto.class);
        verify(messageService).addMessage(eq("conv-1"), messageCaptor.capture());
        assertThat(messageCaptor.getValue().getConversationId()).isEqualTo("conv-1");
        assertThat(messageCaptor.getValue().getRole()).isEqualTo("user");
        assertThat(messageCaptor.getValue().getContent()).isEqualTo("Run the CE task");
        assertThat(messageCaptor.getValue().getTimestamp()).isNotBlank();
        verify(agentService).executeSync(request, "conv-1");
    }

    @Test
    @DisplayName("CE sync chat endpoint rejects missing conversationId before executing")
    void ceSyncChatEndpointRejectsMissingConversationIdBeforeExecuting() {
        ChatRequest request = new ChatRequest();
        request.setMessage("missing conversation");
        when(creditClient.checkCredits("tenant-42", "CHAT_CONVERSATION")).thenReturn(true);

        var response = controller.chatSync(request, "tenant-42", null, null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody())
            .containsEntry("success", false)
            .containsEntry("error", "conversationId is required");
        verify(messageService, never()).addMessage(org.mockito.Mockito.any(), org.mockito.Mockito.any());
        verify(agentService, never()).executeSync(org.mockito.Mockito.any(), org.mockito.Mockito.any());
    }

    @Test
    @DisplayName("regression: orgRole / userRoles come from the headers; roles forged in the JSON body never reach the agent")
    void rolesComeFromHeadersNotFromBody() throws Exception {
        // Exploit body of the CE finding: a VIEWER posting orgRole=OWNER / userRoles=admin.
        ChatRequest request = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
            "{\"conversationId\":\"conv-1\",\"message\":\"hi\",\"orgRole\":\"OWNER\",\"userRoles\":\"admin\"}",
            ChatRequest.class);
        when(creditClient.checkCredits(org.mockito.Mockito.any(), org.mockito.Mockito.any(),
            org.mockito.Mockito.any(), org.mockito.Mockito.any())).thenReturn(true);
        when(conversationQueryService.isConversationInStrictScope("conv-1", "tenant-42", "org-1")).thenReturn(true);
        when(agentService.executeSync(org.mockito.Mockito.any(), eq("conv-1"))).thenReturn(Map.of("success", true));

        controller.chatSync(request, "tenant-42", "org-1", "VIEWER", "user");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(agentService).executeSync(captor.capture(), eq("conv-1"));
        assertThat(captor.getValue().getOrgRole()).isEqualTo("VIEWER");
        assertThat(captor.getValue().getUserRoles()).isEqualTo("user");
    }

    @Test
    @DisplayName("regression: a conversation the caller may not write is refused with 404, nothing written or executed")
    void foreignConversationIsRefused() {
        ChatRequest request = new ChatRequest();
        request.setConversationId("victim-conv");
        request.setMessage("injected");
        when(creditClient.checkCredits(org.mockito.Mockito.any(), org.mockito.Mockito.any(),
            org.mockito.Mockito.any(), org.mockito.Mockito.any())).thenReturn(false);
        when(conversationQueryService.isConversationInStrictScope("victim-conv", "tenant-42", "org-1")).thenReturn(false);

        var response = controller.chatSync(request, "tenant-42", "org-1", null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).containsEntry("success", false);
        // Neither the normal path nor the 402 audit-trail path may write into the conversation.
        verify(messageService, never()).addMessage(org.mockito.Mockito.any(), org.mockito.Mockito.any());
        verify(messageService, never()).persistAttemptAndError(org.mockito.Mockito.any(),
            org.mockito.Mockito.any(), org.mockito.Mockito.any());
        verify(agentService, never()).executeSync(org.mockito.Mockito.any(), org.mockito.Mockito.any());
    }
}

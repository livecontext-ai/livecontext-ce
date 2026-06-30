package com.apimarketplace.monolith.chat;

import com.apimarketplace.agent.factory.LLMProviderFactory;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.conversation.dto.ChatRequest;
import com.apimarketplace.conversation.service.ConversationHistoryService;
import com.apimarketplace.conversation.service.ai.ChatStreamingService;
import com.apimarketplace.conversation.streaming.StreamingOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("MonolithChatController")
class MonolithChatControllerTest {

    private final ConversationHistoryService conversationHistoryService = mock(ConversationHistoryService.class);
    private final ChatStreamingService chatStreamingService = mock(ChatStreamingService.class);
    private final CreditConsumptionClient creditClient = mock(CreditConsumptionClient.class);
    private final LLMProviderFactory llmProviderFactory = mock(LLMProviderFactory.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final MonolithChatController controller = new MonolithChatController(
            conversationHistoryService,
            chatStreamingService,
            creditClient,
            llmProviderFactory,
            redisTemplate,
            new ObjectMapper());

    @Test
    @DisplayName("creates new CE chat conversations in the active organization scope")
    void createsNewCeChatConversationInActiveOrganizationScope() {
        ChatRequest request = new ChatRequest();
        request.setMessage("Ping the CE agent");
        request.setAgentId("agent-1");
        request.setProvider("deepseek");
        request.setModel("deepseek-chat");

        when(llmProviderFactory.getAllModelsInfo()).thenReturn(Map.of(
                "defaultProvider", "deepseek",
                "defaultModel", "deepseek-chat"));
        when(creditClient.checkCredits("user-7")).thenReturn(true);
        // No chatConfig and no skill selection on the request → the mapper yields
        // null and the create is called with a null config (column skipped).
        when(conversationHistoryService.createConversation(
                "user-7", "org-7", "Generating Title...", "deepseek-chat", "deepseek", "agent-1", null))
                .thenReturn("conv-1");

        // Fifth arg = X-User-Roles header (platform roles); required=false, absent here.
        var response = controller.chat(request, "user-7", "org-7", "OWNER", null);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody())
                .containsEntry("conversationId", "conv-1")
                .containsEntry("model", "deepseek-chat");
        assertThat(request.getUserId()).isEqualTo("user-7");
        assertThat(request.getOrgId()).isEqualTo("org-7");
        assertThat(request.getOrgRole()).isEqualTo("OWNER");

        verify(conversationHistoryService).createConversation(
                "user-7", "org-7", "Generating Title...", "deepseek-chat", "deepseek", "agent-1", null);

        ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatStreamingService, timeout(1000))
                .processStreamingRequest(requestCaptor.capture(), any(StreamingOutput.class), any(String.class));
        assertThat(requestCaptor.getValue().getConversationId()).isEqualTo("conv-1");
        assertThat(requestCaptor.getValue().getOrgId()).isEqualTo("org-7");
    }

    @Test
    @DisplayName("keeps existing conversation ids without creating a scoped conversation")
    void keepsExistingConversationIdWithoutCreatingConversation() {
        ChatRequest request = new ChatRequest();
        request.setConversationId("conv-existing");
        request.setMessage("Continue");
        request.setProvider("deepseek");
        request.setModel("deepseek-chat");

        when(llmProviderFactory.getAllModelsInfo()).thenReturn(Map.of(
                "defaultProvider", "deepseek",
                "defaultModel", "deepseek-chat"));
        when(creditClient.checkCredits("user-7")).thenReturn(true);

        // Fifth arg = X-User-Roles header (platform roles); required=false, absent here.
        var response = controller.chat(request, "user-7", "org-7", "MEMBER", null);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).containsEntry("conversationId", "conv-existing");
        verify(conversationHistoryService, org.mockito.Mockito.never())
                .createConversation(eq("user-7"), eq("org-7"), any(), any(), any(), any());
        verify(conversationHistoryService, org.mockito.Mockito.never())
                .createConversation(eq("user-7"), eq("org-7"), any(), any(), any(), any(), any());
    }

    // ===== chatConfig threading (CE↔cloud parity regression, 2026-06-11) =====
    //
    // The CE controller used the chatConfig-less create overload while the cloud
    // ChatStreamInitializer persisted ChatRequestConfigMapper.initialChatConfig().
    // Result on CE: the per-(user, workspace) defaults (V312) the composer sends
    // with the FIRST message (temperature / systemPrompt / maxIterations) and the
    // composer's initial skill selection were silently dropped - the Options
    // panel was cosmetic for every new CE conversation.

    @Test
    @DisplayName("threads the request's chatConfig + initial skill selection into the conversation create (pre-fix CE dropped both while cloud persisted them)")
    void threadsChatConfigIntoConversationCreate() {
        ChatRequest request = new ChatRequest();
        request.setMessage("First message with defaults");
        request.setProvider("deepseek");
        request.setModel("deepseek-chat");
        request.setChatConfig(Map.of("temperature", 0.42, "systemPrompt", "Be terse.", "maxIterations", 12));
        request.setDefaultSkillIds(java.util.List.of("skill-1"));

        when(llmProviderFactory.getAllModelsInfo()).thenReturn(Map.of(
                "defaultProvider", "deepseek",
                "defaultModel", "deepseek-chat"));
        when(creditClient.checkCredits("user-7")).thenReturn(true);
        when(conversationHistoryService.createConversation(
                eq("user-7"), eq("org-7"), eq("Generating Title..."), eq("deepseek-chat"), eq("deepseek"),
                eq(null), any(Map.class)))
                .thenReturn("conv-2");

        var response = controller.chat(request, "user-7", "org-7", "OWNER", null);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> configCaptor = ArgumentCaptor.forClass(Map.class);
        verify(conversationHistoryService).createConversation(
                eq("user-7"), eq("org-7"), eq("Generating Title..."), eq("deepseek-chat"), eq("deepseek"),
                eq(null), configCaptor.capture());
        assertThat(configCaptor.getValue())
                .containsEntry("temperature", 0.42)
                .containsEntry("systemPrompt", "Be terse.")
                .containsEntry("maxIterations", 12)
                .containsEntry("defaultSkillIds", java.util.List.of("skill-1"));
    }

    @Test
    @DisplayName("persists an explicit composer skill selection onto an EXISTING conversation (cloud parity: ChatStreamInitializer.persistDefaultSkillIds)")
    void persistsSkillSelectionOnExistingConversation() {
        ChatRequest request = new ChatRequest();
        request.setConversationId("conv-existing");
        request.setMessage("Continue with new skill set");
        request.setProvider("deepseek");
        request.setModel("deepseek-chat");
        request.setDefaultSkillIds(java.util.List.of("skill-2"));

        when(llmProviderFactory.getAllModelsInfo()).thenReturn(Map.of(
                "defaultProvider", "deepseek",
                "defaultModel", "deepseek-chat"));
        when(creditClient.checkCredits("user-7")).thenReturn(true);

        var response = controller.chat(request, "user-7", "org-7", "MEMBER", null);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(conversationHistoryService).persistDefaultSkillIds(
                "conv-existing", "user-7", "org-7", java.util.List.of("skill-2"));
        verify(conversationHistoryService, org.mockito.Mockito.never())
                .createConversation(any(), any(), any(), any(), any(), any(), any());
    }
}

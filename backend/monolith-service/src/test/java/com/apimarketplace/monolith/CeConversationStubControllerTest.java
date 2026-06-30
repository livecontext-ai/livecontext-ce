package com.apimarketplace.monolith;

import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.domain.ToolResult;
import com.apimarketplace.agent.factory.LLMProviderFactory;
import com.apimarketplace.agent.service.ModelCatalogService;
import com.apimarketplace.agent.tool.ToolExecutionService;
import com.apimarketplace.conversation.dto.ConversationDto;
import com.apimarketplace.conversation.service.ConversationQueryService;
import com.apimarketplace.conversation.streaming.StreamStateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("CeConversationStubController")
class CeConversationStubControllerTest {

    private final LLMProviderFactory llmProviderFactory = mock(LLMProviderFactory.class);
    private final ToolExecutionService toolExecutionService = mock(ToolExecutionService.class);
    private final ConversationQueryService conversationQueryService = mock(ConversationQueryService.class);
    private final StreamStateService streamStateService = mock(StreamStateService.class);
    private final CeConversationStubController controller =
        new CeConversationStubController(llmProviderFactory, toolExecutionService, conversationQueryService, streamStateService);

    @Test
    @DisplayName("anonymous model catalog uses the public service path, authenticated picker stays strict")
    void anonymousModelCatalogUsesPublicServicePath() {
        ModelCatalogService modelCatalogService = mock(ModelCatalogService.class);
        Map<String, Object> publicCatalog = Map.of("providers", java.util.List.of(Map.of("name", "openai")));
        Map<String, Object> strictCatalog = Map.of("providers", java.util.List.of());
        when(modelCatalogService.getPublicModelsForCategory(null)).thenReturn(publicCatalog);
        when(modelCatalogService.getModelsForCategory(null, "tenant-42")).thenReturn(strictCatalog);
        ReflectionTestUtils.setField(controller, "modelCatalogService", modelCatalogService);

        assertThat(controller.getAvailableModels(null).getBody()).isSameAs(publicCatalog);
        assertThat(controller.getAvailableModels("tenant-42").getBody()).isSameAs(strictCatalog);

        verify(modelCatalogService).getPublicModelsForCategory(null);
        verify(modelCatalogService).getModelsForCategory(null, "tenant-42");
    }

    @Test
    @DisplayName("cloud-shaped conversation callback forwards authenticated organization credentials")
    void cloudShapedConversationCallbackBuildsToolDefinitionAndCredentials() {
        when(toolExecutionService.executeTool(org.mockito.Mockito.any(), org.mockito.Mockito.any(), eq("tenant-42"), org.mockito.Mockito.any()))
            .thenReturn(ToolResult.builder().success(false).error("conversationId not found").build());
        allowConversation("conv-1", "tenant-42", "org-42");

        controller.executeConversationTool("tenant-42", "org-42", Map.of(
            "tool", "set_conversation_title",
            "toolCallId", "call-1",
            "tenantId", "forged-tenant",
            "orgId", "forged-org",
            "parameters", Map.of("title", "CE Conversation"),
            "conversationId", "conv-1",
            "turnId", "turn-1",
            "streamId", "stream-1"
        ));

        ArgumentCaptor<ToolCall> callCaptor = ArgumentCaptor.forClass(ToolCall.class);
        ArgumentCaptor<ToolDefinition> definitionCaptor = ArgumentCaptor.forClass(ToolDefinition.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> credentialsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(toolExecutionService).executeTool(
            callCaptor.capture(),
            definitionCaptor.capture(),
            eq("tenant-42"),
            credentialsCaptor.capture());

        assertThat(callCaptor.getValue().id()).isEqualTo("call-1");
        assertThat(callCaptor.getValue().toolName()).isEqualTo("set_conversation_title");
        assertThat(callCaptor.getValue().arguments()).containsEntry("title", "CE Conversation");
        assertThat(definitionCaptor.getValue()).isNotNull();
        assertThat(definitionCaptor.getValue().name()).isEqualTo("set_conversation_title");
        assertThat(credentialsCaptor.getValue())
            .containsEntry("conversationId", "conv-1")
            .containsEntry("turnId", "turn-1")
            .containsEntry("__streamId__", "stream-1")
            .containsEntry("__orgId__", "org-42");
    }

    @Test
    @DisplayName("legacy CE callback shape still forwards nested arguments and credentials")
    void legacyCeConversationCallbackShapeStillWorks() {
        when(toolExecutionService.executeTool(org.mockito.Mockito.any(), org.mockito.Mockito.any(), eq("tenant-42"), org.mockito.Mockito.any()))
            .thenReturn(ToolResult.success(null, "Title set"));
        allowConversation("conv-legacy", "tenant-42", null);

        controller.executeConversationTool("tenant-42", null, Map.of(
            "toolName", "set_conversation_title",
            "toolCallId", "call-legacy",
            "tenantId", "tenant-42",
            "arguments", Map.of("title", "Legacy Title"),
            "credentials", Map.of("conversationId", "conv-legacy")
        ));

        ArgumentCaptor<ToolCall> callCaptor = ArgumentCaptor.forClass(ToolCall.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> credentialsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(toolExecutionService).executeTool(
            callCaptor.capture(),
            org.mockito.Mockito.any(),
            eq("tenant-42"),
            credentialsCaptor.capture());

        assertThat(callCaptor.getValue().toolName()).isEqualTo("set_conversation_title");
        assertThat(callCaptor.getValue().arguments()).containsEntry("title", "Legacy Title");
        assertThat(credentialsCaptor.getValue()).containsEntry("conversationId", "conv-legacy");
    }

    @Test
    @DisplayName("cloud-shaped conversation callback forwards get_tool_result and request_credential locally")
    void cloudShapedConversationCallbackForwardsAllConversationLocalTools() {
        when(toolExecutionService.executeTool(org.mockito.Mockito.any(), org.mockito.Mockito.any(), eq("tenant-42"), org.mockito.Mockito.any()))
            .thenReturn(ToolResult.builder()
                .success(true)
                .content("{\"status\":\"ok\"}")
                .metadata(Map.of("serviceApprovalRequested", true))
                .build());
        allowConversation("conv-1", "tenant-42", null);

        controller.executeConversationTool("tenant-42", null, Map.of(
            "tool", "get_tool_result",
            "toolCallId", "call-get-result",
            "tenantId", "tenant-42",
            "parameters", Map.of("tool_call_id", "tool-call-1"),
            "conversationId", "conv-1",
            "streamId", "stream-1"
        ));
        controller.executeConversationTool("tenant-42", null, Map.of(
            "tool", "request_credential",
            "toolCallId", "call-request-credential",
            "tenantId", "tenant-42",
            "parameters", Map.of(
                "services", java.util.List.of("gmail"),
                "reason", "CE callback contract"
            ),
            "conversationId", "conv-1",
            "streamId", "stream-1"
        ));

        ArgumentCaptor<ToolCall> callCaptor = ArgumentCaptor.forClass(ToolCall.class);
        ArgumentCaptor<ToolDefinition> definitionCaptor = ArgumentCaptor.forClass(ToolDefinition.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> credentialsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(toolExecutionService, times(2)).executeTool(
            callCaptor.capture(),
            definitionCaptor.capture(),
            eq("tenant-42"),
            credentialsCaptor.capture());

        assertThat(callCaptor.getAllValues())
            .extracting(ToolCall::toolName)
            .containsExactly("get_tool_result", "request_credential");
        assertThat(definitionCaptor.getAllValues())
            .extracting(ToolDefinition::name)
            .containsExactly("get_tool_result", "request_credential");
        assertThat(credentialsCaptor.getAllValues()).allSatisfy(credentials ->
            assertThat(credentials)
                .containsEntry("conversationId", "conv-1")
                .containsEntry("__streamId__", "stream-1"));
    }

    @Test
    @DisplayName("conversation callback rejects unauthenticated callers and never trusts body tenantId")
    void conversationCallbackRequiresAuthenticatedUserHeader() {
        var response = controller.executeConversationTool(null, null, Map.of(
            "tool", "set_conversation_title",
            "toolCallId", "call-unauthenticated",
            "tenantId", "tenant-42",
            "parameters", Map.of("title", "Should not run"),
            "conversationId", "conv-1"
        ));

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody())
            .containsEntry("success", false)
            .containsEntry("error", "Authentication required");
        verify(toolExecutionService, never()).executeTool(
            org.mockito.Mockito.any(),
            org.mockito.Mockito.any(),
            org.mockito.Mockito.any(),
            org.mockito.Mockito.any());
    }

    @Test
    @DisplayName("conversation callback rejects out-of-scope conversation IDs")
    void conversationCallbackRejectsOutOfScopeConversationId() {
        when(conversationQueryService.getConversationById("victim-conversation", "tenant-42", null))
            .thenReturn(Optional.empty());

        var response = controller.executeConversationTool("tenant-42", null, Map.of(
            "tool", "set_conversation_title",
            "toolCallId", "call-cross-tenant",
            "parameters", Map.of("title", "Should not run"),
            "conversationId", "victim-conversation"
        ));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody())
            .containsEntry("success", false)
            .containsEntry("error", "Conversation not found");
        verify(toolExecutionService, never()).executeTool(
            org.mockito.Mockito.any(),
            org.mockito.Mockito.any(),
            org.mockito.Mockito.any(),
            org.mockito.Mockito.any());
    }

    @Test
    @DisplayName("CE stream recovery endpoints return stable no-active-stream responses")
    void ceStreamRecoveryEndpointsReturnStableNoActiveStreamResponses() {
        Map<String, Object> state = controller.getStreamState("conv-1").getBody();
        Map<String, Object> status = controller.getStreamStatusByConversation("conv-1").getBody();
        Map<String, Object> streamStatus = controller.getStreamStatus("stream-1").getBody();

        assertThat(controller.getActiveStreams().getBody()).isEmpty();
        assertThat(state)
            .containsEntry("conversationId", "conv-1")
            .containsEntry("content", "")
            .containsEntry("hasActiveStream", false);
        assertThat(state.get("toolEvents")).isEqualTo(java.util.List.of());
        assertThat(status)
            .containsEntry("conversationId", "conv-1")
            .containsEntry("contentLength", 0)
            .containsEntry("hasActiveStream", false);
        assertThat(streamStatus)
            .containsEntry("streamId", "stream-1")
            .containsEntry("hasActiveStream", false);
        assertThat(controller.stopStream("stream-1").getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(controller.stopStreamByConversation("conv-1").getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    @DisplayName("CE stream lifecycle endpoints acknowledge ConversationClient internal callbacks")
    void ceStreamLifecycleEndpointsAcknowledgeConversationClientInternalCallbacks() {
        when(streamStateService.registerExternalStream(
                org.mockito.Mockito.any(), org.mockito.Mockito.any(), org.mockito.Mockito.any(), org.mockito.Mockito.any()))
            .thenReturn(Mono.empty());
        when(streamStateService.error(org.mockito.Mockito.any(), org.mockito.Mockito.any())).thenReturn(Mono.just(true));

        var registerResponse = controller.registerInternalStream(Map.of(
            "streamId", "stream-1",
            "conversationId", "conv-1",
            "model", "deepseek-chat",
            "provider", "workflow"
        ));
        var finalizeResponse = controller.finalizeInternalStream("stream-1", Map.of("state", "ERROR"));

        assertThat(registerResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(finalizeResponse.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    @DisplayName("CE stream lifecycle registration rejects missing stream metadata")
    void ceStreamLifecycleRegistrationRejectsMissingStreamMetadata() {
        assertThat(controller.registerInternalStream(Map.of("streamId", "stream-1")).getStatusCode().value())
            .isEqualTo(400);
        assertThat(controller.registerInternalStream(Map.of("conversationId", "conv-1")).getStatusCode().value())
            .isEqualTo(400);
        assertThat(controller.registerInternalStream(null).getStatusCode().value())
            .isEqualTo(400);
    }

    private void allowConversation(String conversationId, String userId, String organizationId) {
        ConversationDto dto = new ConversationDto();
        dto.setId(conversationId);
        dto.setUserId(userId);
        dto.setOrganizationId(organizationId);
        when(conversationQueryService.getConversationById(conversationId, userId, organizationId))
            .thenReturn(Optional.of(dto));
    }
}

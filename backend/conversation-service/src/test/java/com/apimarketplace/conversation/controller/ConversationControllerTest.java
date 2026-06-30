package com.apimarketplace.conversation.controller;

import com.apimarketplace.conversation.dto.ConversationDto;
import com.apimarketplace.conversation.dto.CreateConversationDto;
import com.apimarketplace.conversation.dto.MessageDto;
import com.apimarketplace.conversation.exception.ConversationInactiveException;
import com.apimarketplace.conversation.exception.GlobalExceptionHandler;
import com.apimarketplace.conversation.exception.InvalidMessageException;
import com.apimarketplace.conversation.service.ConversationCommandService;
import com.apimarketplace.conversation.service.ConversationQueryService;
import com.apimarketplace.conversation.service.MessageService;
import com.apimarketplace.conversation.service.PendingActionService;
import com.apimarketplace.conversation.service.PendingActionResumeService;
import com.apimarketplace.conversation.service.approval.ServiceApprovalService;
import com.apimarketplace.conversation.service.approval.ToolAuthorizationApprovalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ConversationControllerTest {

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private ConversationCommandService conversationCommandService;

    @Mock
    private ConversationQueryService conversationQueryService;

    @Mock
    private MessageService messageService;

    @Mock
    private PendingActionService pendingActionService;

    @Mock
    private PendingActionResumeService pendingActionResumeService;

    @Mock
    private ServiceApprovalService serviceApprovalService;

    @Mock
    private ToolAuthorizationApprovalService toolAuthorizationApprovalService;

    @InjectMocks
    private ConversationController conversationController;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(conversationController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void createConversationReturnsCreated() throws Exception {
        ConversationDto dto = new ConversationDto();
        dto.setId("conv-123");
        dto.setUserId("user-1");
        when(conversationCommandService.createConversation(any())).thenReturn(dto);

        CreateConversationDto payload = new CreateConversationDto("title", "model", "provider");

        mockMvc.perform(post("/api/conversations")
                        .header("X-User-ID", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("conv-123"));
    }

    @Test
    void createConversationReturnsUnauthorizedWithoutUserHeader() throws Exception {
        CreateConversationDto payload = new CreateConversationDto("title", "model", "provider");

        mockMvc.perform(post("/api/conversations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isUnauthorized());

        verify(conversationCommandService, never()).createConversation(any());
    }

    @Test
    void getConversationReturns404WhenMissing() throws Exception {
        when(conversationQueryService.getConversationById("missing", "user-1", null))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/conversations/{conversationId}", "missing")
                        .header("X-User-ID", "user-1"))
                .andExpect(status().isNotFound());
    }

    @Test
    void addMessageReturnsConflictWhenConversationInactive() throws Exception {
        ConversationDto dto = new ConversationDto();
        dto.setId("conv-1");
        dto.setUserId("user-1");
        when(conversationQueryService.getConversationById("conv-1", "user-1", null))
                .thenReturn(Optional.of(dto));
        when(messageService.addMessage(eq("conv-1"), any(MessageDto.class)))
                .thenThrow(new ConversationInactiveException("conv-1"));

        mockMvc.perform(post("/api/conversations/{conversationId}/messages", "conv-1")
                        .header("X-User-ID", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(messagePayload()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONVERSATION_INACTIVE"));
    }

    @Test
    void addMessageReturnsBadRequestWhenPayloadInvalid() throws Exception {
        ConversationDto dto = new ConversationDto();
        dto.setId("conv-1");
        dto.setUserId("user-1");
        when(conversationQueryService.getConversationById("conv-1", "user-1", null))
                .thenReturn(Optional.of(dto));
        when(messageService.addMessage(eq("conv-1"), any(MessageDto.class)))
                .thenThrow(new InvalidMessageException("missing role"));

        mockMvc.perform(post("/api/conversations/{conversationId}/messages", "conv-1")
                        .header("X-User-ID", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(messagePayload()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_MESSAGE"));
    }

    @Test
    void approveToolAuthorizationPersistsAndClearsPending() throws Exception {
        ConversationDto dto = new ConversationDto();
        dto.setId("conv-1");
        dto.setUserId("user-1");
        when(conversationQueryService.getConversationById("conv-1", "user-1", null))
                .thenReturn(Optional.of(dto));

        mockMvc.perform(post("/api/conversations/{conversationId}/tool-authorization/approve", "conv-1")
                        .header("X-User-ID", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "rule", "application:acquire",
                                "remember", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rule").value("application:acquire"))
                .andExpect(jsonPath("$.remembered").value(true));

        verify(toolAuthorizationApprovalService).approve("conv-1", "application:acquire", true);
        // Only this rule's card is cleared so other parallel cards stay pending.
        verify(pendingActionService).clearOnePendingAction("conv-1", "auth:application:acquire");
    }

    @Test
    void approveToolAuthorizationRejectsMissingRule() throws Exception {
        ConversationDto dto = new ConversationDto();
        dto.setId("conv-1");
        dto.setUserId("user-1");
        when(conversationQueryService.getConversationById("conv-1", "user-1", null))
                .thenReturn(Optional.of(dto));

        mockMvc.perform(post("/api/conversations/{conversationId}/tool-authorization/approve", "conv-1")
                        .header("X-User-ID", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("remember", true))))
                .andExpect(status().isBadRequest());

        verify(toolAuthorizationApprovalService, never()).approve(any(), any(), anyBoolean());
    }

    @Test
    void denyToolAuthorizationClearsPendingWithoutResuming() throws Exception {
        ConversationDto dto = new ConversationDto();
        dto.setId("conv-1");
        dto.setUserId("user-1");
        when(conversationQueryService.getConversationById("conv-1", "user-1", null))
                .thenReturn(Optional.of(dto));

        mockMvc.perform(post("/api/conversations/{conversationId}/tool-authorization/deny", "conv-1")
                        .header("X-User-ID", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("rule", "application:acquire"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.denied").value(true));

        // Deny clears only the named rule's card (rule supplied), leaving siblings pending.
        verify(pendingActionService).clearOnePendingAction("conv-1", "auth:application:acquire");
        verify(toolAuthorizationApprovalService, never()).approve(any(), any(), anyBoolean());
    }

    private String messagePayload() throws Exception {
        Map<String, Object> payload = Map.of(
                "role", "user",
                "content", "hello",
                "model", "model-x",
                "timestamp", "now",
                "toolCalls", "[]"
        );
        return objectMapper.writeValueAsString(payload);
    }
}

package com.apimarketplace.conversation.controller;

import com.apimarketplace.conversation.dto.ConversationDto;
import com.apimarketplace.conversation.exception.GlobalExceptionHandler;
import com.apimarketplace.conversation.service.ConversationCommandService;
import com.apimarketplace.conversation.service.ConversationQueryService;
import com.apimarketplace.conversation.service.MessageService;
import com.apimarketplace.conversation.service.PendingActionService;
import com.apimarketplace.conversation.service.PendingActionResumeService;
import com.apimarketplace.conversation.service.approval.ServiceApprovalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for parentConversationId support in ConversationController.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConversationController - parentConversationId")
class ConversationControllerParentTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock private ConversationCommandService conversationCommandService;
    @Mock private ConversationQueryService conversationQueryService;
    @Mock private MessageService messageService;
    @Mock private PendingActionService pendingActionService;
    @Mock private PendingActionResumeService pendingActionResumeService;
    @Mock private ServiceApprovalService serviceApprovalService;

    @InjectMocks
    private ConversationController conversationController;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(conversationController)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    @DisplayName("should pass parentConversationId from request to service")
    void shouldPassParentConversationIdToService() throws Exception {
        ConversationDto dto = new ConversationDto();
        dto.setId("new-conv-123");
        dto.setUserId("user-1");
        dto.setParentConversationId("parent-conv-abc");
        when(conversationCommandService.createConversation(any())).thenReturn(dto);

        Map<String, String> payload = Map.of(
            "title", "Sub-Agent",
            "model", "gpt-4",
            "provider", "openai",
            "agentId", "agent-uuid",
            "parentConversationId", "parent-conv-abc"
        );

        mockMvc.perform(post("/api/conversations")
                .header("X-User-ID", "user-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value("new-conv-123"))
            .andExpect(jsonPath("$.parentConversationId").value("parent-conv-abc"));

        ArgumentCaptor<ConversationDto> captor = ArgumentCaptor.forClass(ConversationDto.class);
        verify(conversationCommandService).createConversation(captor.capture());

        assertThat(captor.getValue().getParentConversationId()).isEqualTo("parent-conv-abc");
        assertThat(captor.getValue().getAgentId()).isEqualTo("agent-uuid");
        assertThat(captor.getValue().getUserId()).isEqualTo("user-1");
    }

    @Test
    @DisplayName("should accept request without parentConversationId")
    void shouldAcceptWithoutParentConversationId() throws Exception {
        ConversationDto dto = new ConversationDto();
        dto.setId("new-conv-456");
        dto.setUserId("user-1");
        when(conversationCommandService.createConversation(any())).thenReturn(dto);

        Map<String, String> payload = Map.of(
            "title", "Normal Chat",
            "model", "gpt-4",
            "provider", "openai"
        );

        mockMvc.perform(post("/api/conversations")
                .header("X-User-ID", "user-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value("new-conv-456"));

        ArgumentCaptor<ConversationDto> captor = ArgumentCaptor.forClass(ConversationDto.class);
        verify(conversationCommandService).createConversation(captor.capture());

        assertThat(captor.getValue().getParentConversationId()).isNull();
    }
}

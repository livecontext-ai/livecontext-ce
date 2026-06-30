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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc coverage for the agent-conversation endpoints of {@link ConversationController}
 * ({@code GET/POST /conversations/agent/{agentId}} and {@code DELETE /conversations/by-agent/{agentId}}),
 * which previously had no controller-level tests. Asserts status mapping, body shape, and that
 * the workspace org header is threaded into the services.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConversationController - agent endpoints")
class ConversationControllerAgentEndpointsTest {

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

    private static final String USER = "user-1";
    private static final String ORG = "org-1";
    private static final String AGENT = "agent-uuid";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(conversationController)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    @DisplayName("GET /agent/{id} returns 404 when no conversation exists (does not create)")
    void getAgentConversationReturns404WhenAbsent() throws Exception {
        when(conversationQueryService.findByUserIdAndAgentId(USER, ORG, AGENT))
            .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/conversations/agent/{agentId}", AGENT)
                .header("X-User-ID", USER)
                .header("X-Organization-ID", ORG))
            .andExpect(status().isNotFound());

        verify(conversationQueryService).findByUserIdAndAgentId(USER, ORG, AGENT);
    }

    @Test
    @DisplayName("GET /agent/{id} returns 200 + the conversation when one exists")
    void getAgentConversationReturnsExisting() throws Exception {
        ConversationDto dto = new ConversationDto();
        dto.setId("conv-1");
        dto.setAgentId(AGENT);
        when(conversationQueryService.findByUserIdAndAgentId(USER, ORG, AGENT))
            .thenReturn(Optional.of(dto));

        mockMvc.perform(get("/api/conversations/agent/{agentId}", AGENT)
                .header("X-User-ID", USER)
                .header("X-Organization-ID", ORG))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("conv-1"))
            .andExpect(jsonPath("$.agentId").value(AGENT));
    }

    @Test
    @DisplayName("POST /agent/{id} find-or-creates and threads the org + title into the service")
    void postAgentConversationForwardsOrgAndTitle() throws Exception {
        ConversationDto dto = new ConversationDto();
        dto.setId("conv-new");
        dto.setAgentId(AGENT);
        when(conversationCommandService.createAgentConversation(
                eq(USER), eq(ORG), eq(AGENT), any(), any(), eq("My Agent Chat")))
            .thenReturn(dto);

        mockMvc.perform(post("/api/conversations/agent/{agentId}", AGENT)
                .header("X-User-ID", USER)
                .header("X-Organization-ID", ORG)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("title", "My Agent Chat"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("conv-new"));

        verify(conversationCommandService)
            .createAgentConversation(USER, ORG, AGENT, null, null, "My Agent Chat");
    }

    @Test
    @DisplayName("DELETE /by-agent/{id} returns the deleted count and threads the org into the cascade")
    void deleteByAgentReturnsCountAndForwardsOrg() throws Exception {
        when(conversationCommandService.deleteConversationsByAgentId(AGENT, USER, ORG)).thenReturn(3);

        mockMvc.perform(delete("/api/conversations/by-agent/{agentId}", AGENT)
                .header("X-User-ID", USER)
                .header("X-Organization-ID", ORG))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.deletedCount").value(3))
            .andExpect(jsonPath("$.agentId").value(AGENT));

        verify(conversationCommandService).deleteConversationsByAgentId(AGENT, USER, ORG);
    }
}

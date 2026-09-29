package com.apimarketplace.conversation.controller;

import com.apimarketplace.conversation.dto.ConversationDto;
import com.apimarketplace.conversation.service.ConversationQueryService;
import com.apimarketplace.conversation.service.PendingActionService;
import com.apimarketplace.conversation.service.approval.ToolApprovalGateResolver;
import com.apimarketplace.conversation.service.approval.ToolAuthorizationApprovalService;
import com.apimarketplace.conversation.service.approval.UserQuestionAnswerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression: a read-only VIEWER could APPROVE a held tool action from a chat authorization
 * card (or release a connect-a-service card as approved), which runs the action with the
 * workspace credentials. Approving is now refused to the VIEWER; declining, dismissing and
 * answering a question stay open because they never run anything.
 */
@DisplayName("chat tool-authorization cards: a VIEWER cannot approve, can still decline")
class ToolApprovalViewerGateWebMvcTest {

    private static final String BASE = "/api/conversations/c-1";

    private ToolApprovalGateResolver gateResolver;
    private ToolAuthorizationApprovalService approvalService;
    private PendingActionService pendingActionService;
    private UserQuestionAnswerService questionService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        ConversationQueryService queryService = mock(ConversationQueryService.class);
        when(queryService.getConversationById(eq("c-1"), anyString(), any()))
                .thenReturn(Optional.of(new ConversationDto()));
        gateResolver = mock(ToolApprovalGateResolver.class);
        approvalService = mock(ToolAuthorizationApprovalService.class);
        pendingActionService = mock(PendingActionService.class);
        questionService = mock(UserQuestionAnswerService.class);
        Map<Class<?>, Object> deps = new HashMap<>();
        deps.put(ConversationQueryService.class, queryService);
        deps.put(ToolApprovalGateResolver.class, gateResolver);
        deps.put(ToolAuthorizationApprovalService.class, approvalService);
        deps.put(PendingActionService.class, pendingActionService);
        deps.put(UserQuestionAnswerService.class, questionService);
        Constructor<?> ctor = Arrays.stream(ConversationController.class.getConstructors())
                .max(Comparator.comparingInt(Constructor::getParameterCount)).orElseThrow();
        Object[] args = Arrays.stream(ctor.getParameterTypes())
                .map(t -> deps.containsKey(t) ? deps.get(t) : mock(t)).toArray();
        mockMvc = MockMvcBuilders.standaloneSetup(ctor.newInstance(args)).build();
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String role) {
        return b.header("X-User-ID", "7").header("X-Organization-ID", "org-c")
                .header("X-Organization-Role", role).contentType(MediaType.APPLICATION_JSON);
    }

    @Test
    @DisplayName("VIEWER: tool-authorization approve, approval-gate approve and services approve are 403, nothing released or granted")
    void viewerCannotApprove() throws Exception {
        mockMvc.perform(as(post(BASE + "/tool-authorization/approve"), "VIEWER")
                        .content("{\"rule\":\"gmail:send\",\"toolCallId\":\"tc-1\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("org_role_read_only"));
        mockMvc.perform(as(post(BASE + "/approval-gate/resolve"), "VIEWER")
                        .content("{\"gateKey\":\"tc-1\",\"approved\":true}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(post(BASE + "/approval-gate/resolve"), "VIEWER")
                        .content("{\"gateKey\":\"tc-1\",\"approved\":\"true\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(post(BASE + "/services/approve"), "VIEWER")
                        .content("{\"services\":[\"gmail\"]}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(gateResolver, approvalService, pendingActionService);
    }

    @Test
    @DisplayName("VIEWER: declining (deny, gate released as refused) and dismissing a question stay open")
    void viewerCanStillDecline() throws Exception {
        mockMvc.perform(as(post(BASE + "/tool-authorization/deny"), "VIEWER")
                        .content("{\"rule\":\"gmail:send\",\"toolCallId\":\"tc-1\"}"))
                .andExpect(status().isOk());
        verify(gateResolver).resolve("c-1", "tc-1", false);

        mockMvc.perform(as(post(BASE + "/approval-gate/resolve"), "VIEWER")
                        .content("{\"gateKey\":\"tc-2\",\"approved\":false}"))
                .andExpect(status().isOk());
        verify(gateResolver).resolve("c-1", "tc-2", false);

        mockMvc.perform(as(post(BASE + "/ask-user/dismiss"), "VIEWER")
                        .content("{\"toolCallId\":\"tc-3\"}"))
                .andExpect(status().isOk());
        verify(questionService).dismiss(eq("c-1"), eq("tc-3"), any());
        verify(gateResolver, never()).resolve(anyString(), anyString(), eq(true));
    }

    @Test
    @DisplayName("MEMBER: approving releases the held call as approved")
    void memberApproves() throws Exception {
        when(gateResolver.resolve(anyString(), anyString(), anyBoolean())).thenReturn(true);

        mockMvc.perform(as(post(BASE + "/tool-authorization/approve"), "MEMBER")
                        .content("{\"rule\":\"gmail:send\",\"toolCallId\":\"tc-1\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(as(post(BASE + "/approval-gate/resolve"), "MEMBER")
                        .content("{\"gateKey\":\"tc-2\",\"approved\":true}"))
                .andExpect(status().isOk());

        verify(gateResolver).resolve("c-1", "tc-1", true);
        verify(gateResolver).resolve("c-1", "tc-2", true);
    }
}

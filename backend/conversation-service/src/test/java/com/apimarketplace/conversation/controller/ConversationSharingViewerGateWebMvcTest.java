package com.apimarketplace.conversation.controller;

import com.apimarketplace.conversation.service.ConversationSharingService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression for conversation sharing: turning public sharing on, changing its mode and
 * revoking it were ungated, so a read-only VIEWER could publish a workspace conversation to
 * anyone with the link (or revoke the owners' link). They now bind X-Organization-Role.
 */
@DisplayName("conversation sharing binds X-Organization-Role and refuses VIEWER")
class ConversationSharingViewerGateWebMvcTest {

    private ConversationSharingService sharingService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        sharingService = mock(ConversationSharingService.class);
        Constructor<?> ctor = Arrays.stream(ConversationController.class.getConstructors())
                .max(Comparator.comparingInt(Constructor::getParameterCount)).orElseThrow();
        Object[] args = Arrays.stream(ctor.getParameterTypes())
                .map(t -> t == ConversationSharingService.class ? sharingService : mock(t)).toArray();
        mockMvc = MockMvcBuilders.standaloneSetup(ctor.newInstance(args)).build();
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String role) {
        return b.header("X-User-ID", "7").header("X-Organization-ID", "org-c")
                .header("X-Organization-Role", role).contentType(MediaType.APPLICATION_JSON);
    }

    @Test
    @DisplayName("VIEWER: share on / update / off are 403 and the sharing service is never called")
    void viewerRefused() throws Exception {
        mockMvc.perform(as(post("/api/conversations/c-1/share"), "VIEWER").content("{}")).andExpect(status().isForbidden());
        mockMvc.perform(as(patch("/api/conversations/c-1/share"), "VIEWER").content("{}")).andExpect(status().isForbidden());
        mockMvc.perform(as(delete("/api/conversations/c-1/share"), "VIEWER")).andExpect(status().isForbidden());
        verifyNoInteractions(sharingService);
    }

    @Test
    @DisplayName("MEMBER: revoking a share reaches the sharing service")
    void memberProceeds() throws Exception {
        mockMvc.perform(as(delete("/api/conversations/c-1/share"), "MEMBER"))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isNotEqualTo(403));
        verify(sharingService).disableSharing("c-1", "7", "org-c");
    }
}

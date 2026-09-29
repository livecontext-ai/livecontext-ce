package com.apimarketplace.trigger.controller;

import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.trigger.repository.StandaloneWebhookRepository;
import com.apimarketplace.trigger.service.PlanLimitHelper;
import com.apimarketplace.trigger.service.StandaloneChatEndpointService;
import com.apimarketplace.trigger.service.StandaloneFormEndpointService;
import com.apimarketplace.trigger.service.StandaloneWebhookService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc proof (real TenantResolver) that the webhook, chat-endpoint and form-endpoint
 * writes bind {@code X-Organization-Role} and refuse a VIEWER, while reads stay open.
 */
@DisplayName("trigger-service endpoint writes bind X-Organization-Role and refuse VIEWER")
class TriggerEndpointsViewerGateWebMvcTest {

    private static final String ID = "11111111-2222-3333-4444-555555555555";

    private StandaloneWebhookService webhookService;
    private StandaloneChatEndpointService chatService;
    private StandaloneFormEndpointService formService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        webhookService = mock(StandaloneWebhookService.class);
        chatService = mock(StandaloneChatEndpointService.class);
        formService = mock(StandaloneFormEndpointService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                new StandaloneWebhookController(webhookService, mock(StandaloneWebhookRepository.class),
                        new TenantResolver(), mock(PlanLimitHelper.class)),
                new StandaloneChatEndpointController(chatService),
                new StandaloneFormEndpointController(formService)).build();
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String role) {
        return b.header("X-User-ID", "u-1").header("X-Organization-ID", "org-t")
                .header("X-Organization-Role", role).contentType(MediaType.APPLICATION_JSON);
    }

    @Test
    @DisplayName("VIEWER: regenerate-token and delete on all three are 403 and reach no service")
    void viewerRefused() throws Exception {
        for (String base : new String[] {"/api/webhooks/", "/api/chat-endpoints/", "/api/form-endpoints/"}) {
            mockMvc.perform(as(post(base + ID + "/regenerate-token"), "VIEWER")).andExpect(status().isForbidden());
            mockMvc.perform(as(delete(base + ID), "VIEWER")).andExpect(status().isForbidden());
        }
        verifyNoInteractions(webhookService, chatService, formService);
    }

    @Test
    @DisplayName("MEMBER: regenerate-token reaches the service; VIEWER can still list")
    void memberProceedsViewerReads() throws Exception {
        mockMvc.perform(as(post("/api/webhooks/" + ID + "/regenerate-token"), "MEMBER")).andExpect(status().isOk());
        verify(webhookService).regenerateToken("u-1", "org-t", java.util.UUID.fromString(ID));
        mockMvc.perform(as(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/webhooks"),
                "VIEWER")).andExpect(status().isOk());
    }
}

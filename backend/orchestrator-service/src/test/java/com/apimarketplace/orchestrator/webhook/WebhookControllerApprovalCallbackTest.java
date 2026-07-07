package com.apimarketplace.orchestrator.webhook;

import com.apimarketplace.orchestrator.services.approvalchannel.ApprovalCallbackInterceptor;
import com.apimarketplace.trigger.client.webhook.WebhookAuthService;
import com.apimarketplace.trigger.client.webhook.WebhookConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the delegated-approval callback diversion in
 * {@link WebhookController}: an {@code lcapr:} callback_query arriving on a
 * workflow's webhook URL must be handled async and NEVER dispatched (a dispatch
 * would open a spurious epoch on the host workflow), while every other payload,
 * including ordinary callback_query buttons, keeps the normal dispatch path.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WebhookController - approval callback diversion")
class WebhookControllerApprovalCallbackTest {

    private static final String TOKEN = "wh_token_1234567890";

    @Mock private WebhookDispatchService dispatchService;
    @Mock private WebhookAuthService authService;
    @Mock private WebhookResponseRegistry webhookResponseRegistry;
    @Mock private ApprovalCallbackInterceptor interceptor;

    private WebhookController controller;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        controller = new WebhookController(
                dispatchService, authService, webhookResponseRegistry, interceptor);
        request = new MockHttpServletRequest();
        request.setMethod("POST");
    }

    private Map<String, Object> approvalCallbackPayload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("update_id", 1001);
        payload.put("callback_query", Map.of(
                "id", "cbq-1",
                "data", "lcapr:AbCdEfGhIjKlMnOpQrStUv:a",
                "from", Map.of("id", 777)));
        return payload;
    }

    private Map<String, Object> ordinaryCallbackPayload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("update_id", 1002);
        payload.put("callback_query", Map.of(
                "id", "cbq-2",
                "data", "my_workflow_button",
                "from", Map.of("id", 777)));
        return payload;
    }

    private Object invoke(Map<String, Object> payload) {
        return controller.handleWebhook(
                TOKEN, payload, Map.of(), new HttpHeaders(), request, false);
    }

    @Test
    @DisplayName("lcapr callback_query returns 200 approval_callback_handled and is handled async")
    @SuppressWarnings("unchecked")
    void approvalCallbackReturnsHandledStatus() {
        Map<String, Object> payload = approvalCallbackPayload();
        when(interceptor.isApprovalCallback(payload)).thenReturn(true);

        Object response = invoke(payload);

        assertThat(response).isInstanceOf(ResponseEntity.class);
        ResponseEntity<Map<String, String>> entity = (ResponseEntity<Map<String, String>>) response;
        assertThat(entity.getStatusCode().value()).isEqualTo(200);
        assertThat(entity.getBody()).containsEntry("status", "approval_callback_handled");
        verify(interceptor).handleAsync(payload);
    }

    @Test
    @DisplayName("lcapr callback_query is NEVER dispatched to the workflow (no spurious epoch)")
    void approvalCallbackNeverDispatches() {
        Map<String, Object> payload = approvalCallbackPayload();
        when(interceptor.isApprovalCallback(payload)).thenReturn(true);

        invoke(payload);

        verify(dispatchService, never()).getWebhookConfigByToken(anyString());
        verify(dispatchService, never()).dispatch(anyString(), anyMap(), anyBoolean());
    }

    @Test
    @DisplayName("regression: an ordinary callback_query (no lcapr prefix) still dispatches to the workflow")
    void ordinaryCallbackStillDispatches() {
        Map<String, Object> payload = ordinaryCallbackPayload();
        when(interceptor.isApprovalCallback(payload)).thenReturn(false);
        when(dispatchService.getWebhookConfigByToken(TOKEN)).thenReturn(WebhookConfig.defaults());
        when(dispatchService.dispatch(eq(TOKEN), anyMap(), eq(false)))
                .thenReturn(WebhookResponse.accepted("run-1"));

        invoke(payload);

        verify(dispatchService).dispatch(eq(TOKEN), anyMap(), eq(false));
        verify(interceptor, never()).handleAsync(any());
    }

    @Test
    @DisplayName("regression: a plain message payload still dispatches to the workflow")
    void plainMessagePayloadStillDispatches() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("update_id", 1003);
        payload.put("message", Map.of("text", "hello"));
        when(interceptor.isApprovalCallback(payload)).thenReturn(false);
        when(dispatchService.getWebhookConfigByToken(TOKEN)).thenReturn(WebhookConfig.defaults());
        when(dispatchService.dispatch(eq(TOKEN), anyMap(), eq(false)))
                .thenReturn(WebhookResponse.accepted("run-1"));

        invoke(payload);

        verify(dispatchService).dispatch(eq(TOKEN), anyMap(), eq(false));
        verify(interceptor, never()).handleAsync(any());
    }
}

package com.apimarketplace.trigger.controller;

import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.trigger.client.dto.StandaloneWebhookRequest;
import com.apimarketplace.trigger.client.dto.WorkflowReferenceRequest;
import com.apimarketplace.trigger.repository.StandaloneWebhookRepository;
import com.apimarketplace.trigger.service.PlanLimitHelper;
import com.apimarketplace.trigger.service.StandaloneWebhookService;
import com.apimarketplace.trigger.service.WorkflowReferenceImmutableException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("StandaloneWebhookController org scope")
class StandaloneWebhookControllerOrgScopeTest {

    private static final String TENANT_ID = "user-1";
    private static final String ORG_ID = "org-1";
    private static final UUID WEBHOOK_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private StandaloneWebhookService webhookService;
    private TenantResolver tenantResolver;
    private HttpServletRequest request;
    private StandaloneWebhookController controller;

    @BeforeEach
    void setUp() {
        webhookService = mock(StandaloneWebhookService.class);
        StandaloneWebhookRepository webhookRepository = mock(StandaloneWebhookRepository.class);
        tenantResolver = mock(TenantResolver.class);
        PlanLimitHelper planLimitHelper = mock(PlanLimitHelper.class);
        request = mock(HttpServletRequest.class);
        controller = new StandaloneWebhookController(webhookService, webhookRepository, tenantResolver, planLimitHelper);

        when(tenantResolver.resolve(request)).thenReturn(TENANT_ID);
        when(tenantResolver.resolveOrgId(request)).thenReturn(ORG_ID);
    }

    @Test
    @DisplayName("cross-org update returns 404 instead of leaking Webhook not found as 500")
    void crossOrgUpdateReturnsNotFoundInsteadOfServerError() {
        StandaloneWebhookRequest body = new StandaloneWebhookRequest(
                "hook", null, null, null, Map.of(), null, null, null);
        when(webhookService.update(TENANT_ID, ORG_ID, WEBHOOK_ID, body))
                .thenThrow(new IllegalArgumentException("Webhook not found"));

        ResponseEntity<?> response = controller.update(WEBHOOK_ID, body, request);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("cross-org delete returns 404 instead of leaking Webhook not found as 500")
    void crossOrgDeleteReturnsNotFoundInsteadOfServerError() {
        doThrow(new IllegalArgumentException("Webhook not found"))
                .when(webhookService)
                .delete(TENANT_ID, ORG_ID, WEBHOOK_ID);

        ResponseEntity<Void> response = controller.delete(WEBHOOK_ID, request);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("immutable workflow reference violations return 400 instead of leaking a persistence 500")
    void immutableWorkflowReferenceReturnsBadRequest() {
        WorkflowReferenceRequest body = new WorkflowReferenceRequest(null, null, null);
        when(webhookService.updateWorkflowReference(TENANT_ID, ORG_ID, WEBHOOK_ID, null, null))
                .thenThrow(new WorkflowReferenceImmutableException("workflowId is immutable"));

        ResponseEntity<?> response = controller.updateWorkflowReference(WEBHOOK_ID, body, request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }
}

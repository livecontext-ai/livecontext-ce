package com.apimarketplace.trigger.controller;

import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.trigger.client.dto.StandaloneChatEndpointRequest;
import com.apimarketplace.trigger.client.dto.StandaloneFormEndpointRequest;
import com.apimarketplace.trigger.client.dto.StandaloneWebhookRequest;
import com.apimarketplace.trigger.client.dto.WorkflowReferenceRequest;
import com.apimarketplace.trigger.repository.StandaloneWebhookRepository;
import com.apimarketplace.trigger.service.PlanLimitHelper;
import com.apimarketplace.trigger.service.StandaloneChatEndpointService;
import com.apimarketplace.trigger.service.StandaloneFormEndpointService;
import com.apimarketplace.trigger.service.StandaloneWebhookService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Regression for the standalone trigger-endpoint write gap: webhooks, chat endpoints and
 * form endpoints could be created, rewired, deleted or re-keyed (regenerate-token) by a
 * read-only VIEWER, because the controllers checked the workspace but never the ROLE.
 * A re-key silently breaks every caller of the old URL; a relink points a public URL at a
 * different workspace workflow.
 */
@DisplayName("Standalone webhook / chat / form endpoints refuse writes from the VIEWER role")
class StandaloneEndpointsViewerGateTest {

    private static final String TENANT = "user-t";
    private static final String ORG = "org-t";
    private static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static void assertForbidden(ResponseEntity<?> response) {
        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }

    @Nested
    @DisplayName("webhooks")
    class Webhooks {

        private StandaloneWebhookService webhookService;
        private TenantResolver tenantResolver;
        private HttpServletRequest request;
        private StandaloneWebhookController controller;

        @BeforeEach
        void setUp() {
            webhookService = mock(StandaloneWebhookService.class);
            tenantResolver = mock(TenantResolver.class);
            request = mock(HttpServletRequest.class);
            controller = new StandaloneWebhookController(webhookService, mock(StandaloneWebhookRepository.class),
                    tenantResolver, mock(PlanLimitHelper.class));
            when(tenantResolver.resolve(request)).thenReturn(TENANT);
            when(tenantResolver.resolveOrgId(request)).thenReturn(ORG);
        }

        @Test
        @DisplayName("create / update / delete / regenerate-token / relink by a VIEWER are 403 and reach no service")
        void viewerWritesRefused() {
            when(tenantResolver.resolveOrgRole(request)).thenReturn("VIEWER");
            StandaloneWebhookRequest body = new StandaloneWebhookRequest(
                    "hook", null, null, null, Map.of(), null, null, null);

            assertForbidden(controller.create(body, request));
            assertForbidden(controller.update(ID, body, request));
            assertForbidden(controller.delete(ID, request));
            assertForbidden(controller.regenerateToken(ID, request));
            assertForbidden(controller.updateWorkflowReference(ID, new WorkflowReferenceRequest(null, null, null), request));
            verifyNoInteractions(webhookService);
        }

        @Test
        @DisplayName("a MEMBER still re-keys a webhook")
        void memberRegenerates() {
            when(tenantResolver.resolveOrgRole(request)).thenReturn("MEMBER");

            controller.regenerateToken(ID, request);

            verify(webhookService).regenerateToken(TENANT, ORG, ID);
        }

        @Test
        @DisplayName("a VIEWER can still list webhooks")
        void viewerLists() {
            when(tenantResolver.resolveOrgRole(request)).thenReturn("VIEWER");

            assertThat(controller.getAll(request).getStatusCode().value()).isEqualTo(200);
            verify(webhookService).getAll(TENANT, ORG);
        }
    }

    @Nested
    @DisplayName("chat endpoints")
    class ChatEndpoints {

        @Test
        @DisplayName("create / update / delete / regenerate-token / relink by a VIEWER are 403 and reach no service")
        void viewerWritesRefused() {
            StandaloneChatEndpointService service = mock(StandaloneChatEndpointService.class);
            StandaloneChatEndpointController controller = new StandaloneChatEndpointController(service);
            StandaloneChatEndpointRequest body = mock(StandaloneChatEndpointRequest.class);

            assertForbidden(controller.create(TENANT, ORG, "VIEWER", "PRO", body));
            assertForbidden(controller.update(TENANT, ORG, "VIEWER", ID, body));
            assertForbidden(controller.delete(TENANT, ORG, "viewer", ID));
            assertForbidden(controller.regenerateToken(TENANT, ORG, "VIEWER", ID));
            assertForbidden(controller.updateWorkflowReference(TENANT, ORG, "VIEWER", ID,
                    new WorkflowReferenceRequest(null, null, null)));
            verifyNoInteractions(service);
        }

        @Test
        @DisplayName("a MEMBER still deletes a chat endpoint")
        void memberDeletes() {
            StandaloneChatEndpointService service = mock(StandaloneChatEndpointService.class);
            StandaloneChatEndpointController controller = new StandaloneChatEndpointController(service);

            assertThat(controller.delete(TENANT, ORG, "MEMBER", ID).getStatusCode().value()).isEqualTo(204);
            verify(service).delete(TENANT, ORG, ID);
        }
    }

    @Nested
    @DisplayName("form endpoints")
    class FormEndpoints {

        @Test
        @DisplayName("create / update / delete / regenerate-token / relink by a VIEWER are 403 and reach no service")
        void viewerWritesRefused() {
            StandaloneFormEndpointService service = mock(StandaloneFormEndpointService.class);
            StandaloneFormEndpointController controller = new StandaloneFormEndpointController(service);
            StandaloneFormEndpointRequest body = mock(StandaloneFormEndpointRequest.class);

            assertForbidden(controller.create(TENANT, ORG, "VIEWER", "PRO", body));
            assertForbidden(controller.update(TENANT, ORG, "VIEWER", ID, body));
            assertForbidden(controller.delete(TENANT, ORG, "VIEWER", ID));
            assertForbidden(controller.regenerateToken(TENANT, ORG, "VIEWER", ID));
            assertForbidden(controller.updateWorkflowReference(TENANT, ORG, "VIEWER", ID,
                    new WorkflowReferenceRequest(null, null, null)));
            verifyNoInteractions(service);
        }

        @Test
        @DisplayName("a MEMBER still re-keys a form endpoint")
        void memberRegenerates() {
            StandaloneFormEndpointService service = mock(StandaloneFormEndpointService.class);
            StandaloneFormEndpointController controller = new StandaloneFormEndpointController(service);

            controller.regenerateToken(TENANT, ORG, "MEMBER", ID);

            verify(service).regenerateToken(TENANT, ORG, ID);
        }
    }
}

package com.apimarketplace.trigger.controller;

import com.apimarketplace.trigger.client.dto.WebhookTokenDto;
import com.apimarketplace.trigger.domain.WebhookTokenEntity;
import com.apimarketplace.trigger.repository.ScheduledExecutionRepository;
import com.apimarketplace.trigger.repository.StandaloneChatEndpointRepository;
import com.apimarketplace.trigger.repository.StandaloneFormEndpointRepository;
import com.apimarketplace.trigger.repository.StandaloneWebhookRepository;
import com.apimarketplace.trigger.service.ScheduleCronParser;
import com.apimarketplace.trigger.service.StandaloneChatEndpointService;
import com.apimarketplace.trigger.service.StandaloneFormEndpointService;
import com.apimarketplace.trigger.service.StandaloneScheduleService;
import com.apimarketplace.trigger.service.StandaloneWebhookService;
import com.apimarketplace.trigger.service.TriggerLifecycleManager;
import com.apimarketplace.trigger.service.WebhookTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR22d R4 - body-vs-header precedence tests for the webhook-token internal
 * endpoints. Convergent R3 must-fix promoted from nice-to-have (reviewers A+C):
 * PR22c R3 introduced the {@code organizationId} body field on
 * {@code /tokens/ensure} and {@code /tokens/regenerate}, with {@code body wins,
 * header is fallback} precedence - but no test pinned the contract. A future
 * refactor could silently flip the precedence and the dispatch guard would
 * start refusing legitimate fires.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InternalTriggerController - token endpoints (PR22d R4 body-vs-header)")
class InternalTriggerControllerTokenEndpointsTest {

    private static final UUID WORKFLOW_ID = UUID.randomUUID();
    private static final String TRIGGER_ID = "trigger:my_webhook";
    private static final String BODY_ORG = "org-from-body";
    private static final String HEADER_ORG = "org-from-header";

    @Mock private WebhookTokenService tokenService;
    @Mock private StandaloneWebhookService standaloneWebhookService;
    @Mock private StandaloneScheduleService standaloneScheduleService;
    @Mock private StandaloneChatEndpointService chatEndpointService;
    @Mock private StandaloneFormEndpointService formEndpointService;
    @Mock private StandaloneWebhookRepository webhookRepository;
    @Mock private StandaloneChatEndpointRepository chatEndpointRepository;
    @Mock private StandaloneFormEndpointRepository formEndpointRepository;
    @Mock private ScheduledExecutionRepository scheduleRepository;
    @Mock private ScheduleCronParser cronParser;
    @Mock private TriggerLifecycleManager triggerLifecycleManager;

    private InternalTriggerController controller;

    @BeforeEach
    void setUp() {
        controller = new InternalTriggerController(
                tokenService, standaloneWebhookService, standaloneScheduleService,
                chatEndpointService, formEndpointService, webhookRepository,
                chatEndpointRepository, formEndpointRepository,
                scheduleRepository, cronParser, triggerLifecycleManager);
    }

    private WebhookTokenEntity stubEntity(String orgId) {
        WebhookTokenEntity entity = new WebhookTokenEntity();
        entity.setId(1L);
        entity.setWorkflowId(WORKFLOW_ID);
        entity.setTriggerId(TRIGGER_ID);
        entity.setToken("wh_test1234567890123456789012345a");
        entity.setOrganizationId(orgId);
        return entity;
    }

    @Nested
    @DisplayName("ensureTokenForTrigger - body wins over header")
    class EnsureTokenPrecedence {

        @Test
        @DisplayName("body.organizationId WINS when both body and header are present")
        void bodyWinsOverHeader() {
            Map<String, Object> body = new HashMap<>();
            body.put("workflowId", WORKFLOW_ID.toString());
            body.put("triggerId", TRIGGER_ID);
            body.put("organizationId", BODY_ORG);
            when(tokenService.ensureTokenForTrigger(eq(WORKFLOW_ID), eq(TRIGGER_ID), eq(BODY_ORG), eq((String) null)))
                    .thenReturn(stubEntity(BODY_ORG));

            ResponseEntity<WebhookTokenDto> response = controller.ensureTokenForTrigger(body, null, HEADER_ORG);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            ArgumentCaptor<String> orgCaptor = ArgumentCaptor.forClass(String.class);
            verify(tokenService).ensureTokenForTrigger(eq(WORKFLOW_ID), eq(TRIGGER_ID), orgCaptor.capture(), eq((String) null));
            assertThat(orgCaptor.getValue())
                .as("Explicit caller intent in body MUST win over PR16-forwarded header")
                .isEqualTo(BODY_ORG);
            assertThat(response.getBody().getOrganizationId()).isEqualTo(BODY_ORG);
        }

        @Test
        @DisplayName("X-Organization-ID header is used when body has no organizationId")
        void headerFallsThroughWhenBodyAbsent() {
            Map<String, Object> body = new HashMap<>();
            body.put("workflowId", WORKFLOW_ID.toString());
            body.put("triggerId", TRIGGER_ID);
            // No "organizationId" key - PR16 forwarder header is the source.
            when(tokenService.ensureTokenForTrigger(eq(WORKFLOW_ID), eq(TRIGGER_ID), eq(HEADER_ORG), eq((String) null)))
                    .thenReturn(stubEntity(HEADER_ORG));

            ResponseEntity<WebhookTokenDto> response = controller.ensureTokenForTrigger(body, null, HEADER_ORG);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            ArgumentCaptor<String> orgCaptor = ArgumentCaptor.forClass(String.class);
            verify(tokenService).ensureTokenForTrigger(eq(WORKFLOW_ID), eq(TRIGGER_ID), orgCaptor.capture(), eq((String) null));
            assertThat(orgCaptor.getValue()).isEqualTo(HEADER_ORG);
            assertThat(response.getBody().getOrganizationId()).isEqualTo(HEADER_ORG);
        }

        @Test
        @DisplayName("Blank body.organizationId falls through to header")
        void blankBodyFallsThrough() {
            Map<String, Object> body = new HashMap<>();
            body.put("workflowId", WORKFLOW_ID.toString());
            body.put("triggerId", TRIGGER_ID);
            body.put("organizationId", "");  // blank is treated as absent
            when(tokenService.ensureTokenForTrigger(eq(WORKFLOW_ID), eq(TRIGGER_ID), eq(HEADER_ORG), eq((String) null)))
                    .thenReturn(stubEntity(HEADER_ORG));

            ResponseEntity<WebhookTokenDto> response = controller.ensureTokenForTrigger(body, null, HEADER_ORG);

            ArgumentCaptor<String> orgCaptor = ArgumentCaptor.forClass(String.class);
            verify(tokenService).ensureTokenForTrigger(eq(WORKFLOW_ID), eq(TRIGGER_ID), orgCaptor.capture(), eq((String) null));
            assertThat(orgCaptor.getValue()).isEqualTo(HEADER_ORG);
        }

        @Test
        @DisplayName("Both null → service receives null (legacy permissive path)")
        void bothNullReceivesNull() {
            Map<String, Object> body = new HashMap<>();
            body.put("workflowId", WORKFLOW_ID.toString());
            body.put("triggerId", TRIGGER_ID);
            // No body org, no header org.
            when(tokenService.ensureTokenForTrigger(eq(WORKFLOW_ID), eq(TRIGGER_ID), eq((String) null), eq((String) null)))
                    .thenReturn(stubEntity(null));

            ResponseEntity<WebhookTokenDto> response = controller.ensureTokenForTrigger(body, null, null);

            ArgumentCaptor<String> orgCaptor = ArgumentCaptor.forClass(String.class);
            verify(tokenService).ensureTokenForTrigger(eq(WORKFLOW_ID), eq(TRIGGER_ID), orgCaptor.capture(), eq((String) null));
            assertThat(orgCaptor.getValue()).isNull();
            assertThat(response.getBody().getOrganizationId()).isNull();
        }
    }

    @Nested
    @DisplayName("regenerateToken - body wins over header")
    class RegenerateTokenPrecedence {

        @Test
        @DisplayName("body.organizationId WINS when both body and header are present")
        void bodyWinsOverHeader() {
            Map<String, Object> body = new HashMap<>();
            body.put("workflowId", WORKFLOW_ID.toString());
            body.put("triggerId", TRIGGER_ID);
            body.put("organizationId", BODY_ORG);
            when(tokenService.regenerateTokenForTrigger(eq(WORKFLOW_ID), eq(TRIGGER_ID), eq(BODY_ORG), eq((String) null)))
                    .thenReturn(stubEntity(BODY_ORG));

            ResponseEntity<WebhookTokenDto> response = controller.regenerateToken(body, null, HEADER_ORG);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            ArgumentCaptor<String> orgCaptor = ArgumentCaptor.forClass(String.class);
            verify(tokenService).regenerateTokenForTrigger(eq(WORKFLOW_ID), eq(TRIGGER_ID), orgCaptor.capture(), eq((String) null));
            assertThat(orgCaptor.getValue()).isEqualTo(BODY_ORG);
            assertThat(response.getBody().getOrganizationId()).isEqualTo(BODY_ORG);
        }

        @Test
        @DisplayName("X-Organization-ID header is used when body has no organizationId")
        void headerFallsThroughWhenBodyAbsent() {
            Map<String, Object> body = new HashMap<>();
            body.put("workflowId", WORKFLOW_ID.toString());
            body.put("triggerId", TRIGGER_ID);
            when(tokenService.regenerateTokenForTrigger(eq(WORKFLOW_ID), eq(TRIGGER_ID), eq(HEADER_ORG), eq((String) null)))
                    .thenReturn(stubEntity(HEADER_ORG));

            ResponseEntity<WebhookTokenDto> response = controller.regenerateToken(body, null, HEADER_ORG);

            ArgumentCaptor<String> orgCaptor = ArgumentCaptor.forClass(String.class);
            verify(tokenService).regenerateTokenForTrigger(eq(WORKFLOW_ID), eq(TRIGGER_ID), orgCaptor.capture(), eq((String) null));
            assertThat(orgCaptor.getValue()).isEqualTo(HEADER_ORG);
        }
    }
}

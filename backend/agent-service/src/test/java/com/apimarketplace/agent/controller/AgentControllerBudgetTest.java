package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.service.AgentService;
import com.apimarketplace.agent.service.AgentWidgetConfigService;
import com.apimarketplace.agent.util.RequestParameterExtractor;
import com.apimarketplace.agent.webhook.AgentWebhookTokenService;
import com.apimarketplace.common.web.TenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests the budget-specific contract on AgentController:
 *  - PUT rejects server-managed budget fields (creditsReserved/creditsFree/creditsConsumed)
 *    with 400 (§6.1 AGENT_BUDGET_HIERARCHY.md)
 *  - POST /budget/reset returns 409 when the agent has an in-flight sub-agent reservation
 *    (§6.2)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentController - budget contract")
class AgentControllerBudgetTest {

    @Mock private AgentService agentService;
    @Mock private AgentWebhookTokenService webhookTokenService;
    @Mock private AgentWidgetConfigService widgetConfigService;
    @Mock private TenantResolver tenantResolver;
    @Mock private HttpServletRequest request;

    private AgentController controller;

    private static final UUID AGENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String TENANT = "tenant-1";

    @BeforeEach
    void setUp() {
        RequestParameterExtractor extractor = new RequestParameterExtractor();
        controller = new AgentController(
            agentService,
            webhookTokenService,
            widgetConfigService,
            tenantResolver,
            extractor,
            "",
            "",
            "http://localhost:8091"
        );
    }

    @Nested
    @DisplayName("PUT /{id} - read-only budget fields")
    class PutValidation {

        @Test
        @DisplayName("400 when body contains creditsReserved")
        void rejectsCreditsReserved() {
            Map<String, Object> body = new HashMap<>();
            body.put("creditsReserved", 10);

            ResponseEntity<?> response = controller.updateAgent(AGENT_ID, request, null, body);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            @SuppressWarnings("unchecked")
            Map<String, Object> errorBody = (Map<String, Object>) response.getBody();
            assertThat(errorBody).containsEntry("error", "read_only_field");
            assertThat(errorBody).containsEntry("field", "creditsReserved");
            verifyNoInteractions(agentService);
        }

        @Test
        @DisplayName("400 when body contains creditsFree")
        void rejectsCreditsFree() {
            Map<String, Object> body = new HashMap<>();
            body.put("creditsFree", 50);

            ResponseEntity<?> response = controller.updateAgent(AGENT_ID, request, null, body);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            @SuppressWarnings("unchecked")
            Map<String, Object> errorBody = (Map<String, Object>) response.getBody();
            assertThat(errorBody).containsEntry("field", "creditsFree");
            verifyNoInteractions(agentService);
        }

        @Test
        @DisplayName("400 when body contains creditsConsumed (clients cannot zero consumption via PUT)")
        void rejectsCreditsConsumed() {
            Map<String, Object> body = new HashMap<>();
            body.put("creditsConsumed", 0);

            ResponseEntity<?> response = controller.updateAgent(AGENT_ID, request, null, body);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            @SuppressWarnings("unchecked")
            Map<String, Object> errorBody = (Map<String, Object>) response.getBody();
            assertThat(errorBody).containsEntry("field", "creditsConsumed");
            assertThat((String) errorBody.get("message"))
                .contains("/budget/reset");
            verifyNoInteractions(agentService);
        }

        @Test
        @DisplayName("400 when body contains creditsConsumedFromSubagents (server-written observability column)")
        void rejectsCreditsConsumedFromSubagents() {
            // Clients must not be able to forge the cascade-spend accumulator either - it's written
            // exclusively by BudgetReservationService.settleReservationChain. If PUT accepted it,
            // a malicious client could hide own-spend as cascade-spend and vice-versa, breaking
            // the observability split that this column exists to provide.
            Map<String, Object> body = new HashMap<>();
            body.put("creditsConsumedFromSubagents", 0);

            ResponseEntity<?> response = controller.updateAgent(AGENT_ID, request, null, body);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            @SuppressWarnings("unchecked")
            Map<String, Object> errorBody = (Map<String, Object>) response.getBody();
            assertThat(errorBody).containsEntry("field", "creditsConsumedFromSubagents");
            verifyNoInteractions(agentService);
        }

        @Test
        @DisplayName("creditBudget (the cap) is still allowed - it's the public knob")
        void acceptsCreditBudget() {
            Map<String, Object> body = new HashMap<>();
            body.put("creditBudget", 200);
            when(tenantResolver.resolveOrNull(any())).thenReturn(TENANT);
            when(agentService.updateAgent(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), anyBoolean()))
                .thenReturn(null);

            ResponseEntity<?> response = controller.updateAgent(AGENT_ID, request, null, body);

            // Not a 400 - validation passed. (Body is null because service is stubbed.)
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    @Nested
    @DisplayName("POST /{id}/budget/reset - reservation-aware 409")
    class ResetEndpoint {

        @Test
        @DisplayName("200 when reset applied (no reservation in flight)")
        void returnsOkWhenApplied() {
            when(tenantResolver.resolveOrNull(any())).thenReturn(TENANT);
            when(agentService.resetCredits(AGENT_ID, TENANT, null, null)).thenReturn(true);

            ResponseEntity<Map<String, Object>> response = controller.resetCredits(AGENT_ID, request, null, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("status", "reset");
            assertThat(response.getBody()).containsEntry("agentId", AGENT_ID.toString());
        }

        @Test
        @DisplayName("409 with reservation_in_flight error when service refuses")
        void returns409WhenReservationBlocks() {
            when(tenantResolver.resolveOrNull(any())).thenReturn(TENANT);
            when(agentService.resetCredits(AGENT_ID, TENANT, null, null)).thenReturn(false);

            ResponseEntity<Map<String, Object>> response = controller.resetCredits(AGENT_ID, request, null, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).containsEntry("error", "reservation_in_flight");
            assertThat(response.getBody()).containsEntry("agentId", AGENT_ID.toString());
            assertThat((String) response.getBody().get("message"))
                .contains("sub-agent reservation");
        }
    }
}

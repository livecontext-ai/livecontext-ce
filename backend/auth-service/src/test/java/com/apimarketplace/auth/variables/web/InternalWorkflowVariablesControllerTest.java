package com.apimarketplace.auth.variables.web;

import com.apimarketplace.auth.variables.domain.WorkflowVariableModels.UpsertVariableRequest;
import com.apimarketplace.auth.variables.domain.WorkflowVariableModels.ValueType;
import com.apimarketplace.auth.variables.domain.WorkflowVariableModels.VariableResponse;
import com.apimarketplace.auth.variables.domain.WorkflowVariableModels.WorkflowVariable;
import com.apimarketplace.auth.variables.service.WorkflowVariableService;
import com.apimarketplace.auth.variables.service.WorkflowVariableService.VariableLimitExceededException;
import com.apimarketplace.auth.variables.service.WorkflowVariableService.VariableValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The internal (service-to-service) variables API.
 *
 * <p>{@code GET /api/internal/variables/bundle} - the orchestrator-facing typed
 * bundle. Pins the response envelope ({@code {variables, count}}), the
 * explicit-scope contract (tenant via query param, org via header - async runs
 * have no request context to forward), and the blank-tenant 400 guard.
 *
 * <p>{@code GET /api/internal/variables/list} and
 * {@code POST /api/internal/variables/set} - the agent-facing pair behind
 * {@code credential(action='variables'|'set_variable')}. Pins the
 * {@code {variables:[VariableResponse],count}} listing shape, the upsert
 * delegation, and the exception-to-HTTP mapping (400 invalid_variable /
 * 409 PLAN_RESOURCE_LIMIT_EXCEEDED with the exact LimitExceededError body
 * the conversation layer relays to the LLM).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InternalWorkflowVariablesController")
class InternalWorkflowVariablesControllerTest {

    private static final String TENANT = "tenant-abc";
    private static final String ORG = "org-xyz";

    @Mock
    private WorkflowVariableService service;

    @InjectMocks
    private InternalWorkflowVariablesController controller;

    private static WorkflowVariable stored(Long id, String name, String value,
                                           ValueType type, String organizationId) {
        return new WorkflowVariable(id, TENANT, organizationId, name, value,
                type, false, "desc-" + name, TENANT, null, null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapBodyOf(ResponseEntity<?> response) {
        return (Map<String, Object>) response.getBody();
    }

    @Test
    @DisplayName("returns {variables, count} with the service's typed values verbatim")
    void returnsBundleEnvelope() {
        Map<String, Object> typed = new LinkedHashMap<>();
        typed.put("api_url", "https://x");
        typed.put("retries", new BigDecimal("3"));
        typed.put("debug", Boolean.TRUE);
        when(service.bundleForScope(TENANT, ORG)).thenReturn(typed);

        ResponseEntity<Map<String, Object>> response = controller.getBundle(TENANT, ORG);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).containsOnlyKeys("variables", "count");
        assertThat(response.getBody().get("variables")).isEqualTo(typed);
        assertThat(response.getBody().get("count")).isEqualTo(3);
    }

    @Test
    @DisplayName("an empty scope yields an empty variables map and count 0 - not an error")
    void emptyScopeYieldsEmptyBundle() {
        when(service.bundleForScope(TENANT, null)).thenReturn(Map.of());

        ResponseEntity<Map<String, Object>> response = controller.getBundle(TENANT, null);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody().get("variables")).isEqualTo(Map.of());
        assertThat(response.getBody().get("count")).isEqualTo(0);
    }

    @Test
    @DisplayName("passes the org header through to the service so workspace runs get workspace variables")
    void threadsOrgHeaderToService() {
        when(service.bundleForScope(TENANT, ORG)).thenReturn(Map.of());

        controller.getBundle(TENANT, ORG);

        verify(service).bundleForScope(TENANT, ORG);
    }

    @Test
    @DisplayName("absent org header resolves the tenant's PERSONAL scope (org null)")
    void absentOrgHeaderResolvesPersonalScope() {
        when(service.bundleForScope(TENANT, null)).thenReturn(Map.of());

        controller.getBundle(TENANT, null);

        verify(service).bundleForScope(TENANT, null);
    }

    @Test
    @DisplayName("blank tenantId returns 400 without touching the service")
    void blankTenantReturns400() {
        ResponseEntity<Map<String, Object>> response = controller.getBundle("   ", null);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).containsEntry("error", "tenantId is required");
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("empty tenantId returns 400 without touching the service")
    void emptyTenantReturns400() {
        ResponseEntity<Map<String, Object>> response = controller.getBundle("", null);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(service);
    }

    // ========== GET /list ==========

    @Nested
    @DisplayName("GET /list - agent-facing metadata listing")
    class ListTests {

        @Test
        @DisplayName("returns {variables:[VariableResponse],count} with the public-API response mapping")
        void returnsVariablesEnvelopeWithResponseMapping() {
            when(service.listForScope(TENANT, ORG)).thenReturn(List.of(
                    stored(1L, "api_url", "https://x", ValueType.STRING, ORG),
                    stored(2L, "retries", "3", ValueType.NUMBER, null)));

            ResponseEntity<?> response = controller.list(TENANT, ORG);

            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
            Map<String, Object> body = mapBodyOf(response);
            assertThat(body).containsOnlyKeys("variables", "count");
            assertThat(body.get("count")).isEqualTo(2);
            @SuppressWarnings("unchecked")
            List<VariableResponse> variables = (List<VariableResponse>) body.get("variables");
            assertThat(variables).extracting(VariableResponse::name)
                    .containsExactly("api_url", "retries");
            // Full VariableResponse mapping - value/type/scope/description survive.
            VariableResponse first = variables.get(0);
            assertThat(first.value()).isEqualTo("https://x");
            assertThat(first.type()).isEqualTo("STRING");
            assertThat(first.scope()).as("org-scoped row reports 'workspace'").isEqualTo("workspace");
            assertThat(first.description()).isEqualTo("desc-api_url");
            assertThat(variables.get(1).scope()).as("personal row reports 'personal'").isEqualTo("personal");
            assertThat(variables.get(1).type()).isEqualTo("NUMBER");
        }

        @Test
        @DisplayName("masks the value of a secret row to null (secret=true) - the agent listing never sees the real value")
        void listMasksSecretValues() {
            WorkflowVariable secretRow = new WorkflowVariable(3L, TENANT, null, "api_key",
                    "sk-live-SUPERSECRET", ValueType.STRING, true, null, TENANT, null, null);
            when(service.listForScope(TENANT, null)).thenReturn(List.of(
                    secretRow,
                    stored(4L, "api_url", "https://x", ValueType.STRING, null)));

            ResponseEntity<?> response = controller.list(TENANT, null);

            @SuppressWarnings("unchecked")
            List<VariableResponse> variables =
                    (List<VariableResponse>) mapBodyOf(response).get("variables");
            VariableResponse secret = variables.get(0);
            assertThat(secret.secret()).isTrue();
            assertThat(secret.value())
                    .as("secret values are write-only through the internal /list too")
                    .isNull();
            VariableResponse plain = variables.get(1);
            assertThat(plain.secret()).isFalse();
            assertThat(plain.value()).isEqualTo("https://x");
        }

        @Test
        @DisplayName("an empty scope yields an empty list and count 0 - not an error")
        void emptyScopeYieldsEmptyList() {
            when(service.listForScope(TENANT, null)).thenReturn(List.of());

            ResponseEntity<?> response = controller.list(TENANT, null);

            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(mapBodyOf(response))
                    .containsEntry("variables", List.of())
                    .containsEntry("count", 0);
        }

        @Test
        @DisplayName("absent org header resolves the tenant's PERSONAL scope (org null)")
        void absentOrgHeaderResolvesPersonalScope() {
            when(service.listForScope(TENANT, null)).thenReturn(List.of());

            controller.list(TENANT, null);

            verify(service).listForScope(TENANT, null);
        }

        @Test
        @DisplayName("blank tenantId returns 400 without touching the service")
        void blankTenantReturns400() {
            ResponseEntity<?> response = controller.list("   ", ORG);

            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat(mapBodyOf(response)).containsEntry("error", "tenantId is required");
            verifyNoInteractions(service);
        }
    }

    // ========== POST /set ==========

    @Nested
    @DisplayName("POST /set - agent-facing upsert")
    class SetTests {

        @Test
        @DisplayName("delegates to upsertByName with the tenant as createdBy and returns the VariableResponse")
        void delegatesToUpsertByNameAndReturnsResponse() {
            UpsertVariableRequest request =
                    new UpsertVariableRequest("api_url", "https://x", "STRING", null, null);
            when(service.upsertByName(request, TENANT, ORG, TENANT))
                    .thenReturn(stored(7L, "api_url", "https://x", ValueType.STRING, ORG));

            ResponseEntity<?> response = controller.set(TENANT, ORG, "MEMBER", request);

            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
            verify(service).upsertByName(request, TENANT, ORG, TENANT);
            VariableResponse body = (VariableResponse) response.getBody();
            assertThat(body.id()).isEqualTo(7L);
            assertThat(body.name()).isEqualTo("api_url");
            assertThat(body.scope()).isEqualTo("workspace");
        }

        @Test
        @DisplayName("absent org header upserts into the PERSONAL scope (org null)")
        void absentOrgHeaderUpsertsPersonalScope() {
            UpsertVariableRequest request = new UpsertVariableRequest("n", "v", null, null, null);
            when(service.upsertByName(request, TENANT, null, TENANT))
                    .thenReturn(stored(1L, "n", "v", ValueType.STRING, null));

            ResponseEntity<?> response = controller.set(TENANT, null, null, request);

            verify(service).upsertByName(request, TENANT, null, TENANT);
            assertThat(((VariableResponse) response.getBody()).scope()).isEqualTo("personal");
        }

        @Test
        @DisplayName("blank tenantId returns 400 without touching the service")
        void blankTenantReturns400() {
            ResponseEntity<?> response = controller.set("  ",
                    ORG, null, new UpsertVariableRequest("n", "v", null, null, null));

            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat(mapBodyOf(response)).containsEntry("error", "tenantId is required");
            verifyNoInteractions(service);
        }
    }

    // ========== POST /set - VIEWER read-only gate ==========

    @Nested
    @DisplayName("POST /set - VIEWER read-only gate (agent path)")
    class ViewerGateTests {

        // The public controller's requireWriteAccess never runs on the agent
        // path (conversation-service calls this internal endpoint directly),
        // so the platform-wide VIEWER read-only rule is enforced HERE. Pre-fix,
        // a VIEWER driving credential(action='set_variable') could write
        // workspace variables their role forbids in the UI.

        private final UpsertVariableRequest request =
                new UpsertVariableRequest("api_url", "https://x", null, null, null);

        @Test
        @DisplayName("REGRESSION (VIEWER write bypass): VIEWER + org-scoped write → 403 org_role_read_only with the DO NOT RETRY relay message")
        void viewerOrgScopedWriteReturns403() {
            ResponseEntity<?> response = controller.set(TENANT, ORG, "VIEWER", request);

            assertThat(response.getStatusCode().value()).isEqualTo(403);
            Map<String, Object> body = mapBodyOf(response);
            // conversation-service relays `message` verbatim to the LLM - both
            // keys are load-bearing.
            assertThat(body).containsEntry("error", "org_role_read_only");
            assertThat((String) body.get("message"))
                    .contains("read-only")
                    .contains("DO NOT RETRY");
            verifyNoInteractions(service);
        }

        @Test
        @DisplayName("VIEWER role is matched case-insensitively (header casing must not open the gate)")
        void viewerRoleIsCaseInsensitive() {
            ResponseEntity<?> response = controller.set(TENANT, ORG, "viewer", request);

            assertThat(response.getStatusCode().value()).isEqualTo(403);
            verifyNoInteractions(service);
        }

        @Test
        @DisplayName("VIEWER without an org (personal scope) is allowed - the read-only rule is a WORKSPACE rule")
        void viewerPersonalScopeIsAllowed() {
            when(service.upsertByName(request, TENANT, null, TENANT))
                    .thenReturn(stored(1L, "api_url", "https://x", ValueType.STRING, null));

            ResponseEntity<?> response = controller.set(TENANT, null, "VIEWER", request);

            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
            verify(service).upsertByName(request, TENANT, null, TENANT);
        }

        @Test
        @DisplayName("MEMBER + org-scoped write is allowed")
        void memberOrgScopedWriteIsAllowed() {
            when(service.upsertByName(request, TENANT, ORG, TENANT))
                    .thenReturn(stored(2L, "api_url", "https://x", ValueType.STRING, ORG));

            ResponseEntity<?> response = controller.set(TENANT, ORG, "MEMBER", request);

            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
            verify(service).upsertByName(request, TENANT, ORG, TENANT);
        }

        @Test
        @DisplayName("OWNER + org-scoped write is allowed")
        void ownerOrgScopedWriteIsAllowed() {
            when(service.upsertByName(request, TENANT, ORG, TENANT))
                    .thenReturn(stored(3L, "api_url", "https://x", ValueType.STRING, ORG));

            ResponseEntity<?> response = controller.set(TENANT, ORG, "OWNER", request);

            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
            verify(service).upsertByName(request, TENANT, ORG, TENANT);
        }

        @Test
        @DisplayName("missing role header + org write is allowed (in-process/legacy callers that predate role forwarding)")
        void missingRoleHeaderIsAllowed() {
            when(service.upsertByName(request, TENANT, ORG, TENANT))
                    .thenReturn(stored(4L, "api_url", "https://x", ValueType.STRING, ORG));

            ResponseEntity<?> response = controller.set(TENANT, ORG, null, request);

            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
            verify(service).upsertByName(request, TENANT, ORG, TENANT);
        }
    }

    // ========== exception mapping ==========

    @Nested
    @DisplayName("exception-to-HTTP mapping")
    class ExceptionMappingTests {

        @Test
        @DisplayName("VariableValidationException maps to 400 invalid_variable carrying the rule message")
        void validationMapsTo400() {
            ResponseEntity<?> response = controller.onValidation(
                    new VariableValidationException("value is not a valid number"));

            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat(mapBodyOf(response))
                    .containsEntry("error", "invalid_variable")
                    .containsEntry("message", "value is not a valid number");
        }

        @Test
        @DisplayName("VariableConflictException (lost /set insert race) maps to 409 variable_name_conflict carrying the name")
        void conflictMapsTo409() {
            // Mirrors the public controller's onConflict twin: a concurrent
            // set_variable losing the check-then-insert race must surface the
            // same 409 contract here, not a raw 500.
            ResponseEntity<?> response = controller.onConflict(
                    new WorkflowVariableService.VariableConflictException("api_url"));

            assertThat(response.getStatusCode().value()).isEqualTo(409);
            Map<String, Object> body = mapBodyOf(response);
            assertThat(body)
                    .containsEntry("error", "variable_name_conflict")
                    .containsEntry("name", "api_url");
            assertThat((String) body.get("message")).contains("api_url");
        }

        @Test
        @DisplayName("VariableNotFoundException maps to 404 variable_not_found carrying the id in the message")
        void notFoundMapsTo404() {
            // Mirrors the public controller's onNotFound twin (upsertByName can
            // throw it when the row vanishes between update and re-fetch).
            ResponseEntity<?> response = controller.onNotFound(
                    new WorkflowVariableService.VariableNotFoundException(7L));

            assertThat(response.getStatusCode().value()).isEqualTo(404);
            Map<String, Object> body = mapBodyOf(response);
            assertThat(body).containsEntry("error", "variable_not_found");
            assertThat((String) body.get("message")).contains("7");
        }

        @Test
        @DisplayName("VariableLimitExceededException maps to 409 with the EXACT LimitExceededError body the LLM relay depends on")
        void limitExceededMapsToExactBodyShape() {
            ResponseEntity<?> response = controller.onLimitExceeded(
                    new VariableLimitExceededException("FREE", 3, 3));

            assertThat(response.getStatusCode().value()).isEqualTo(409);
            Map<String, Object> body = mapBodyOf(response);
            // conversation-service's credential(action='set_variable') extracts
            // `message` and relays it verbatim - these exact keys are load-bearing.
            assertThat(body)
                    .containsEntry("error", "PLAN_RESOURCE_LIMIT_EXCEEDED")
                    .containsEntry("resourceType", "WORKFLOW_VARIABLE")
                    .containsEntry("planCode", "FREE")
                    .containsEntry("currentCount", 3)
                    .containsEntry("limit", 3);
            assertThat((String) body.get("message"))
                    .contains("LIMIT REACHED")
                    .contains("DO NOT RETRY");
        }
    }
}

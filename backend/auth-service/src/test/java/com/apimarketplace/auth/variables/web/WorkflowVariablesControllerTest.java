package com.apimarketplace.auth.variables.web;

import com.apimarketplace.auth.variables.domain.WorkflowVariableModels.UpsertVariableRequest;
import com.apimarketplace.auth.variables.domain.WorkflowVariableModels.ValueType;
import com.apimarketplace.auth.variables.domain.WorkflowVariableModels.VariableResponse;
import com.apimarketplace.auth.variables.domain.WorkflowVariableModels.WorkflowVariable;
import com.apimarketplace.auth.variables.service.WorkflowVariableService;
import com.apimarketplace.auth.variables.service.WorkflowVariableService.QuotaStatus;
import com.apimarketplace.auth.variables.service.WorkflowVariableService.VariableConflictException;
import com.apimarketplace.auth.variables.service.WorkflowVariableService.VariableLimitExceededException;
import com.apimarketplace.auth.variables.service.WorkflowVariableService.VariableNotFoundException;
import com.apimarketplace.auth.variables.service.WorkflowVariableService.VariableValidationException;
import com.apimarketplace.common.web.TenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@code /api/variables} - header guards (401 unauthenticated / 400 blank
 * tenant), the org-VIEWER read-only rule (403 on writes, reads open), scope
 * threading through {@link TenantResolver}, and the exception-to-HTTP mapping
 * including the EXACT {@code PLAN_RESOURCE_LIMIT_EXCEEDED} body the frontend
 * upgrade toast matches on.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowVariablesController")
class WorkflowVariablesControllerTest {

    private static final String TENANT = "tenant-abc";
    private static final String ORG = "org-xyz";

    @Mock
    private WorkflowVariableService service;

    @Mock
    private TenantResolver tenantResolver;

    @Mock
    private HttpServletRequest httpRequest;

    @InjectMocks
    private WorkflowVariablesController controller;

    private static WorkflowVariable variable(Long id, String name, String organizationId) {
        return new WorkflowVariable(id, TENANT, organizationId, name, "value",
                ValueType.STRING, false, null, TENANT, null, null);
    }

    private static UpsertVariableRequest anyRequest() {
        return new UpsertVariableRequest("api_url", "v", null, null, null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> bodyOf(ResponseEntity<?> response) {
        return (Map<String, Object>) response.getBody();
    }

    // ========== authentication guards ==========

    @Nested
    @DisplayName("authentication guards")
    class AuthGuardTests {

        @Test
        @DisplayName("GET returns 401 when X-Authenticated is absent")
        void listWithoutAuthHeaderReturns401() {
            ResponseEntity<?> response = controller.list(httpRequest, null, TENANT);

            assertThat(response.getStatusCode().value()).isEqualTo(401);
            verifyNoInteractions(service);
        }

        @Test
        @DisplayName("GET returns 401 when X-Authenticated is anything but 'true'")
        void listWithFalsyAuthHeaderReturns401() {
            ResponseEntity<?> response = controller.list(httpRequest, "false", TENANT);

            assertThat(response.getStatusCode().value()).isEqualTo(401);
            verifyNoInteractions(service);
        }

        @Test
        @DisplayName("GET returns 400 when X-User-ID is blank - never query with an empty tenant")
        void listWithBlankTenantReturns400() {
            ResponseEntity<?> response = controller.list(httpRequest, "true", "   ");

            assertThat(response.getStatusCode().value()).isEqualTo(400);
            verifyNoInteractions(service);
        }

        @Test
        @DisplayName("POST returns 401 without X-Authenticated - the write guard includes the auth guard")
        void createWithoutAuthReturns401() {
            ResponseEntity<?> response = controller.create(httpRequest, null, TENANT, anyRequest());

            assertThat(response.getStatusCode().value()).isEqualTo(401);
            verifyNoInteractions(service);
        }

        @Test
        @DisplayName("GET /quota returns 401 without X-Authenticated")
        void quotaWithoutAuthReturns401() {
            ResponseEntity<?> response = controller.quota(httpRequest, null, TENANT);

            assertThat(response.getStatusCode().value()).isEqualTo(401);
            verifyNoInteractions(service);
        }
    }

    // ========== VIEWER read-only rule ==========

    @Nested
    @DisplayName("org VIEWER read-only rule")
    class ViewerRuleTests {

        private void stubViewerInOrg() {
            when(tenantResolver.resolveOrgId(httpRequest)).thenReturn(ORG);
            when(tenantResolver.resolveOrgRole(httpRequest)).thenReturn("VIEWER");
        }

        @Test
        @DisplayName("POST as org VIEWER returns 403 with error code org_role_read_only")
        void createAsViewerReturns403() {
            stubViewerInOrg();

            ResponseEntity<?> response = controller.create(httpRequest, "true", TENANT, anyRequest());

            assertThat(response.getStatusCode().value()).isEqualTo(403);
            assertThat(bodyOf(response)).containsEntry("error", "org_role_read_only");
            verifyNoInteractions(service);
        }

        @Test
        @DisplayName("PUT as org VIEWER returns 403")
        void updateAsViewerReturns403() {
            stubViewerInOrg();

            ResponseEntity<?> response = controller.update(httpRequest, "true", TENANT, 1L, anyRequest());

            assertThat(response.getStatusCode().value()).isEqualTo(403);
            verifyNoInteractions(service);
        }

        @Test
        @DisplayName("DELETE as org VIEWER returns 403")
        void deleteAsViewerReturns403() {
            stubViewerInOrg();

            ResponseEntity<?> response = controller.delete(httpRequest, "true", TENANT, 1L);

            assertThat(response.getStatusCode().value()).isEqualTo(403);
            verifyNoInteractions(service);
        }

        @Test
        @DisplayName("GET as org VIEWER stays open - VIEWER is read-only, not read-none")
        void listAsViewerSucceeds() {
            when(tenantResolver.resolveOrgId(httpRequest)).thenReturn(ORG);
            when(service.listForScope(TENANT, ORG)).thenReturn(List.of());

            ResponseEntity<?> response = controller.list(httpRequest, "true", TENANT);

            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        }

        @Test
        @DisplayName("VIEWER role WITHOUT an active org does not block writes - the rule is org-scope only")
        void viewerRoleInPersonalScopeDoesNotBlock() {
            when(tenantResolver.resolveOrgId(httpRequest)).thenReturn(null);
            when(tenantResolver.resolveOrgRole(httpRequest)).thenReturn("VIEWER");
            when(service.create(any(), any(), any(), any())).thenReturn(variable(1L, "api_url", null));

            ResponseEntity<?> response = controller.create(httpRequest, "true", TENANT, anyRequest());

            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        }

        @Test
        @DisplayName("a non-VIEWER org role (MEMBER) writes normally")
        void memberRoleWrites() {
            when(tenantResolver.resolveOrgId(httpRequest)).thenReturn(ORG);
            when(tenantResolver.resolveOrgRole(httpRequest)).thenReturn("MEMBER");
            when(service.create(any(), any(), any(), any())).thenReturn(variable(1L, "api_url", ORG));

            ResponseEntity<?> response = controller.create(httpRequest, "true", TENANT, anyRequest());

            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
            verify(service).create(any(), any(), any(), any());
        }
    }

    // ========== happy paths ==========

    @Nested
    @DisplayName("happy paths")
    class HappyPathTests {

        @Test
        @DisplayName("GET maps each variable through VariableResponse (scope string included)")
        void listMapsToResponses() {
            when(tenantResolver.resolveOrgId(httpRequest)).thenReturn(ORG);
            when(service.listForScope(TENANT, ORG))
                    .thenReturn(List.of(variable(1L, "api_url", ORG)));

            ResponseEntity<?> response = controller.list(httpRequest, "true", TENANT);

            @SuppressWarnings("unchecked")
            List<VariableResponse> body = (List<VariableResponse>) response.getBody();
            assertThat(body).hasSize(1);
            assertThat(body.get(0).name()).isEqualTo("api_url");
            assertThat(body.get(0).scope()).isEqualTo("workspace");
        }

        @Test
        @DisplayName("GET masks the value of a secret variable to null while plain values pass through")
        void listMasksSecretValues() {
            WorkflowVariable secretRow = new WorkflowVariable(1L, TENANT, null, "api_key",
                    "sk-live-SUPERSECRET", ValueType.STRING, true, null, TENANT, null, null);
            WorkflowVariable plainRow = new WorkflowVariable(2L, TENANT, null, "api_url",
                    "https://x", ValueType.STRING, false, null, TENANT, null, null);
            when(tenantResolver.resolveOrgId(httpRequest)).thenReturn(null);
            when(service.listForScope(TENANT, null)).thenReturn(List.of(secretRow, plainRow));

            ResponseEntity<?> response = controller.list(httpRequest, "true", TENANT);

            @SuppressWarnings("unchecked")
            List<VariableResponse> body = (List<VariableResponse>) response.getBody();
            assertThat(body).hasSize(2);
            VariableResponse secret = body.get(0);
            assertThat(secret.secret()).isTrue();
            assertThat(secret.value()).as("secret value must be masked in the public listing").isNull();
            VariableResponse plain = body.get(1);
            assertThat(plain.secret()).isFalse();
            assertThat(plain.value()).isEqualTo("https://x");
        }

        @Test
        @DisplayName("GET resolves the scope from the request and passes it to the service verbatim")
        void listThreadsResolvedScope() {
            when(tenantResolver.resolveOrgId(httpRequest)).thenReturn(null);
            when(service.listForScope(TENANT, null)).thenReturn(List.of());

            controller.list(httpRequest, "true", TENANT);

            verify(service).listForScope(TENANT, null);
        }

        @Test
        @DisplayName("GET /quota returns exactly {used, limit, planCode} - null limit included as unlimited")
        void quotaBodyShape() {
            when(tenantResolver.resolveOrgId(httpRequest)).thenReturn(null);
            when(service.quotaForScope(TENANT, null)).thenReturn(new QuotaStatus(2, null, "FREE"));

            ResponseEntity<?> response = controller.quota(httpRequest, "true", TENANT);

            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).containsOnlyKeys("used", "limit", "planCode");
            assertThat(body.get("used")).isEqualTo(2);
            assertThat(body.get("limit")).as("null limit must still be present (= unlimited)").isNull();
            assertThat(body.get("planCode")).isEqualTo("FREE");
        }

        @Test
        @DisplayName("POST passes the caller's tenant as createdBy")
        void createPassesTenantAsCreatedBy() {
            when(tenantResolver.resolveOrgId(httpRequest)).thenReturn(ORG);
            when(tenantResolver.resolveOrgRole(httpRequest)).thenReturn("ADMIN");
            UpsertVariableRequest request = anyRequest();
            when(service.create(request, TENANT, ORG, TENANT)).thenReturn(variable(5L, "api_url", ORG));

            ResponseEntity<?> response = controller.create(httpRequest, "true", TENANT, request);

            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
            verify(service).create(request, TENANT, ORG, TENANT);
            assertThat(((VariableResponse) response.getBody()).id()).isEqualTo(5L);
        }

        @Test
        @DisplayName("PUT returns the updated variable mapped through VariableResponse")
        void updateReturnsMappedResponse() {
            when(tenantResolver.resolveOrgId(httpRequest)).thenReturn(null);
            UpsertVariableRequest request = anyRequest();
            when(service.update(3L, request, TENANT, null)).thenReturn(variable(3L, "api_url", null));

            ResponseEntity<?> response = controller.update(httpRequest, "true", TENANT, 3L, request);

            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
            VariableResponse body = (VariableResponse) response.getBody();
            assertThat(body.id()).isEqualTo(3L);
            assertThat(body.scope()).isEqualTo("personal");
        }

        @Test
        @DisplayName("DELETE returns {deleted: true, id} after the service delete")
        void deleteReturnsAck() {
            when(tenantResolver.resolveOrgId(httpRequest)).thenReturn(null);

            ResponseEntity<?> response = controller.delete(httpRequest, "true", TENANT, 9L);

            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(bodyOf(response))
                    .containsEntry("deleted", true)
                    .containsEntry("id", 9L);
            verify(service).delete(9L, TENANT, null);
        }
    }

    // ========== exception mapping ==========

    @Nested
    @DisplayName("exception mapping")
    class ExceptionMappingTests {

        @Test
        @DisplayName("VariableValidationException maps to 400 invalid_variable")
        void validationMapsTo400() {
            ResponseEntity<?> response = controller.onValidation(
                    new VariableValidationException("value is required"));

            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat(bodyOf(response))
                    .containsEntry("error", "invalid_variable")
                    .containsEntry("message", "value is required");
        }

        @Test
        @DisplayName("VariableNotFoundException maps to 404 variable_not_found")
        void notFoundMapsTo404() {
            ResponseEntity<?> response = controller.onNotFound(new VariableNotFoundException(7L));

            assertThat(response.getStatusCode().value()).isEqualTo(404);
            assertThat(bodyOf(response)).containsEntry("error", "variable_not_found");
        }

        @Test
        @DisplayName("VariableConflictException maps to 409 variable_name_conflict carrying the name")
        void conflictMapsTo409() {
            ResponseEntity<?> response = controller.onConflict(new VariableConflictException("api_url"));

            assertThat(response.getStatusCode().value()).isEqualTo(409);
            assertThat(bodyOf(response))
                    .containsEntry("error", "variable_name_conflict")
                    .containsEntry("name", "api_url");
        }

        @Test
        @DisplayName("VariableLimitExceededException maps to 409 with the EXACT LimitExceededError field set")
        void limitExceededMapsToExactBodyShape() {
            ResponseEntity<?> response = controller.onLimitExceeded(
                    new VariableLimitExceededException("FREE", 3, 3));

            assertThat(response.getStatusCode().value()).isEqualTo(409);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            // The frontend upgrade toast and the LLM agent match on these exact
            // keys (mirrors auth-client LimitExceededError) - do not rename.
            assertThat(body).containsOnlyKeys(
                    "error", "resourceType", "planCode", "currentCount", "limit", "upgradeHint");
            assertThat(body.get("error")).isEqualTo("PLAN_RESOURCE_LIMIT_EXCEEDED");
            assertThat(body.get("resourceType")).isEqualTo("WORKFLOW_VARIABLE");
            assertThat(body.get("planCode")).isEqualTo("FREE");
            assertThat(body.get("currentCount")).isEqualTo(3);
            assertThat(body.get("limit")).isEqualTo(3);
            // upgradeHint is the SHORT presentation hint (mirror of
            // EntitlementGuard.upgradeHintFor) that the frontend toast displays
            // verbatim - NOT the long LLM exception message (that one is relayed
            // by the INTERNAL controller's `message` field instead).
            assertThat(body.get("upgradeHint")).isEqualTo("Upgrade to STARTER");
        }
    }
}

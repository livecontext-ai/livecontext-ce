package com.apimarketplace.auth.variables.web;

import com.apimarketplace.auth.variables.domain.WorkflowVariableModels.UpsertVariableRequest;
import com.apimarketplace.auth.variables.domain.WorkflowVariableModels.VariableResponse;
import com.apimarketplace.auth.variables.service.WorkflowVariableService;
import com.apimarketplace.auth.variables.service.WorkflowVariableService.QuotaStatus;
import com.apimarketplace.auth.variables.service.WorkflowVariableService.VariableConflictException;
import com.apimarketplace.auth.variables.service.WorkflowVariableService.VariableLimitExceededException;
import com.apimarketplace.auth.variables.service.WorkflowVariableService.VariableNotFoundException;
import com.apimarketplace.auth.variables.service.WorkflowVariableService.VariableValidationException;
import com.apimarketplace.common.web.TenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * User-facing CRUD for workflow variables ({@code {{$vars.name}}}).
 *
 * <p>Scope comes from the gateway-injected headers: {@code X-User-ID} (tenant)
 * and {@code X-Organization-ID} (active workspace; absent = personal scope).
 * Reads are open to every member of the scope; writes are blocked for org
 * VIEWERs ({@code X-Organization-Role}), consistent with the platform-wide
 * VIEWER read-only rule.
 *
 * <p>Quota: creation beyond the plan's {@code max_workflow_variables} returns
 * HTTP 409 with the exact {@code PLAN_RESOURCE_LIMIT_EXCEEDED} body shape of
 * auth-client's LimitExceededError, so the existing frontend upgrade toast
 * fires without modification.
 */
@RestController
@RequestMapping("/api/variables")
public class WorkflowVariablesController {

    private final WorkflowVariableService service;
    private final TenantResolver tenantResolver;

    public WorkflowVariablesController(WorkflowVariableService service, TenantResolver tenantResolver) {
        this.service = service;
        this.tenantResolver = tenantResolver;
    }

    @GetMapping
    public ResponseEntity<?> list(
            HttpServletRequest httpRequest,
            @RequestHeader(value = "X-Authenticated", required = false) String authenticated,
            @RequestHeader(value = "X-User-ID", required = false) String tenantId
    ) {
        ResponseEntity<?> guard = requireAuthenticated(authenticated, tenantId);
        if (guard != null) return guard;
        String organizationId = tenantResolver.resolveOrgId(httpRequest);
        List<VariableResponse> variables = service.listForScope(tenantId, organizationId).stream()
                .map(VariableResponse::from)
                .toList();
        return ResponseEntity.ok(variables);
    }

    /** Usage vs plan cap for the UI meter: {@code {used, limit, planCode}}, limit null = unlimited. */
    @GetMapping("/quota")
    public ResponseEntity<?> quota(
            HttpServletRequest httpRequest,
            @RequestHeader(value = "X-Authenticated", required = false) String authenticated,
            @RequestHeader(value = "X-User-ID", required = false) String tenantId
    ) {
        ResponseEntity<?> guard = requireAuthenticated(authenticated, tenantId);
        if (guard != null) return guard;
        String organizationId = tenantResolver.resolveOrgId(httpRequest);
        QuotaStatus status = service.quotaForScope(tenantId, organizationId);
        Map<String, Object> body = new HashMap<>();
        body.put("used", status.used());
        body.put("limit", status.limit());
        body.put("planCode", status.planCode());
        return ResponseEntity.ok(body);
    }

    @PostMapping
    public ResponseEntity<?> create(
            HttpServletRequest httpRequest,
            @RequestHeader(value = "X-Authenticated", required = false) String authenticated,
            @RequestHeader(value = "X-User-ID", required = false) String tenantId,
            @RequestBody UpsertVariableRequest request
    ) {
        ResponseEntity<?> guard = requireWriteAccess(httpRequest, authenticated, tenantId);
        if (guard != null) return guard;
        String organizationId = tenantResolver.resolveOrgId(httpRequest);
        var created = service.create(request, tenantId, organizationId, tenantId);
        return ResponseEntity.ok(VariableResponse.from(created));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(
            HttpServletRequest httpRequest,
            @RequestHeader(value = "X-Authenticated", required = false) String authenticated,
            @RequestHeader(value = "X-User-ID", required = false) String tenantId,
            @PathVariable long id,
            @RequestBody UpsertVariableRequest request
    ) {
        ResponseEntity<?> guard = requireWriteAccess(httpRequest, authenticated, tenantId);
        if (guard != null) return guard;
        String organizationId = tenantResolver.resolveOrgId(httpRequest);
        var updated = service.update(id, request, tenantId, organizationId);
        return ResponseEntity.ok(VariableResponse.from(updated));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(
            HttpServletRequest httpRequest,
            @RequestHeader(value = "X-Authenticated", required = false) String authenticated,
            @RequestHeader(value = "X-User-ID", required = false) String tenantId,
            @PathVariable long id
    ) {
        ResponseEntity<?> guard = requireWriteAccess(httpRequest, authenticated, tenantId);
        if (guard != null) return guard;
        String organizationId = tenantResolver.resolveOrgId(httpRequest);
        service.delete(id, tenantId, organizationId);
        return ResponseEntity.ok(Map.of("deleted", true, "id", id));
    }

    // ===== guards =====

    private ResponseEntity<?> requireAuthenticated(String authenticated, String tenantId) {
        if (!"true".equalsIgnoreCase(authenticated)) {
            return ResponseEntity.status(401).body(Map.of("error", "authentication required"));
        }
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "X-User-ID header is required"));
        }
        return null;
    }

    /** Org VIEWERs are read-only: block writes when the active workspace role is VIEWER. */
    private ResponseEntity<?> requireWriteAccess(HttpServletRequest httpRequest,
                                                 String authenticated, String tenantId) {
        ResponseEntity<?> guard = requireAuthenticated(authenticated, tenantId);
        if (guard != null) return guard;
        String organizationId = tenantResolver.resolveOrgId(httpRequest);
        String orgRole = tenantResolver.resolveOrgRole(httpRequest);
        if (organizationId != null && "VIEWER".equalsIgnoreCase(orgRole)) {
            return ResponseEntity.status(403).body(Map.of(
                    "error", "org_role_read_only",
                    "message", "Viewers cannot modify workspace variables"));
        }
        return null;
    }

    // ===== exception mapping =====

    @ExceptionHandler(VariableValidationException.class)
    public ResponseEntity<?> onValidation(VariableValidationException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", "invalid_variable", "message", ex.getMessage()));
    }

    @ExceptionHandler(VariableNotFoundException.class)
    public ResponseEntity<?> onNotFound(VariableNotFoundException ex) {
        return ResponseEntity.status(404).body(Map.of("error", "variable_not_found", "message", ex.getMessage()));
    }

    @ExceptionHandler(VariableConflictException.class)
    public ResponseEntity<?> onConflict(VariableConflictException ex) {
        return ResponseEntity.status(409).body(Map.of(
                "error", "variable_name_conflict",
                "name", ex.name(),
                "message", ex.getMessage()));
    }

    /**
     * Mirrors auth-client LimitExceededError field-for-field (frontend and the
     * LLM agent match on {@code error} + read the numbers - do not rename).
     * upgradeHint is the SHORT presentation hint (same table as
     * EntitlementGuard.upgradeHintFor), NOT the long LLM exception message -
     * the frontend toast displays it verbatim.
     */
    @ExceptionHandler(VariableLimitExceededException.class)
    public ResponseEntity<?> onLimitExceeded(VariableLimitExceededException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", "PLAN_RESOURCE_LIMIT_EXCEEDED");
        body.put("resourceType", "WORKFLOW_VARIABLE");
        body.put("planCode", ex.planCode());
        body.put("currentCount", ex.currentCount());
        body.put("limit", ex.limit());
        body.put("upgradeHint", upgradeHintFor(ex.planCode()));
        return ResponseEntity.status(409).body(body);
    }

    /** Mirror of auth-client EntitlementGuard.upgradeHintFor (auth-service has no auth-client dependency). */
    private static String upgradeHintFor(String currentPlanCode) {
        if (currentPlanCode == null) return "Upgrade to STARTER";
        return switch (currentPlanCode) {
            case "FREE" -> "Upgrade to STARTER";
            case "STARTER" -> "Upgrade to PRO";
            case "PRO" -> "Upgrade to TEAM";
            case "TEAM" -> "Upgrade to ENTERPRISE";
            default -> "Contact sales for higher limits";
        };
    }
}

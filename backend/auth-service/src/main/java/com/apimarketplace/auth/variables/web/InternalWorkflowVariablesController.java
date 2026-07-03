package com.apimarketplace.auth.variables.web;

import com.apimarketplace.auth.variables.domain.WorkflowVariableModels.UpsertVariableRequest;
import com.apimarketplace.auth.variables.domain.WorkflowVariableModels.VariableResponse;
import com.apimarketplace.auth.variables.service.WorkflowVariableService;
import com.apimarketplace.auth.variables.service.WorkflowVariableService.VariableLimitExceededException;
import com.apimarketplace.auth.variables.service.WorkflowVariableService.VariableValidationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Internal API for workflow-variable resolution. Consumed by
 * orchestrator-service (via CredentialClient) once per workflow run to build
 * the {@code {{$vars.*}}} bundle. Not exposed through the public gateway
 * (no route on the gateway table - unreachable externally by construction).
 *
 * <p>The caller passes the RUN OWNER's scope explicitly ({@code tenantId}
 * query param + {@code X-Organization-ID} header): workflow runs execute
 * asynchronously with no inbound request context, so header forwarding
 * cannot be relied on here.
 */
@RestController
@RequestMapping("/api/internal/variables")
public class InternalWorkflowVariablesController {

    private final WorkflowVariableService service;

    public InternalWorkflowVariablesController(WorkflowVariableService service) {
        this.service = service;
    }

    /**
     * Decrypted, typed name-to-value map for the scope. Response:
     * {@code {"variables": {name: value, ...}, "count": N}}.
     */
    @GetMapping("/bundle")
    public ResponseEntity<Map<String, Object>> getBundle(
            @RequestParam String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId
    ) {
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "tenantId is required"));
        }
        Map<String, Object> variables = service.bundleForScope(tenantId, organizationId);
        return ResponseEntity.ok(Map.of(
                "variables", variables,
                "count", variables.size()
        ));
    }

    /**
     * Variable METADATA list (name/type/scope/description/value) for the
     * agent-facing {@code credential(action='variables')} listing. Same
     * VariableResponse shape as the public API.
     */
    @GetMapping("/list")
    public ResponseEntity<?> list(
            @RequestParam String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId
    ) {
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "tenantId is required"));
        }
        List<VariableResponse> variables = service.listForScope(tenantId, organizationId).stream()
                .map(VariableResponse::from)
                .toList();
        return ResponseEntity.ok(Map.of("variables", variables, "count", variables.size()));
    }

    /**
     * Create-or-update by name for the agent-facing
     * {@code credential(action='set_variable')}. Quota applies only to NEW
     * names (updates never hit the cap); a cap hit returns the same 409
     * PLAN_RESOURCE_LIMIT_EXCEEDED contract as the public API so the
     * conversation layer can relay the do-not-retry message to the LLM.
     */
    @PostMapping("/set")
    public ResponseEntity<?> set(
            @RequestParam String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestHeader(value = "X-Organization-Role", required = false) String organizationRole,
            @RequestBody UpsertVariableRequest request
    ) {
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "tenantId is required"));
        }
        // Platform-wide VIEWER read-only rule - same gate as the public
        // controller's requireWriteAccess, enforced here because the agent
        // path reaches this endpoint directly (no gateway/public controller).
        if (organizationId != null && "VIEWER".equalsIgnoreCase(organizationRole)) {
            return ResponseEntity.status(403).body(Map.of(
                    "error", "org_role_read_only",
                    "message", "Viewers cannot modify workspace variables. "
                            + "Tell the user their workspace role is read-only. DO NOT RETRY."));
        }
        var saved = service.upsertByName(request, tenantId, organizationId, tenantId);
        return ResponseEntity.ok(VariableResponse.from(saved));
    }

    @ExceptionHandler(VariableValidationException.class)
    public ResponseEntity<?> onValidation(VariableValidationException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", "invalid_variable", "message", ex.getMessage()));
    }

    /** Lost insert race on /set (upsertByName) - same 409 contract as the public API. */
    @ExceptionHandler(WorkflowVariableService.VariableConflictException.class)
    public ResponseEntity<?> onConflict(WorkflowVariableService.VariableConflictException ex) {
        return ResponseEntity.status(409).body(Map.of(
                "error", "variable_name_conflict",
                "name", ex.name(),
                "message", ex.getMessage()));
    }

    @ExceptionHandler(WorkflowVariableService.VariableNotFoundException.class)
    public ResponseEntity<?> onNotFound(WorkflowVariableService.VariableNotFoundException ex) {
        return ResponseEntity.status(404).body(Map.of("error", "variable_not_found", "message", ex.getMessage()));
    }

    @ExceptionHandler(VariableLimitExceededException.class)
    public ResponseEntity<?> onLimitExceeded(VariableLimitExceededException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", "PLAN_RESOURCE_LIMIT_EXCEEDED");
        body.put("resourceType", "WORKFLOW_VARIABLE");
        body.put("planCode", ex.planCode());
        body.put("currentCount", ex.currentCount());
        body.put("limit", ex.limit());
        body.put("message", ex.getMessage());
        return ResponseEntity.status(409).body(body);
    }
}

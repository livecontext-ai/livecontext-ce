package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.domain.ModelExecutionLinkEntity;
import com.apimarketplace.agent.domain.ModelExecutionLinkScope;
import com.apimarketplace.agent.service.ModelExecutionLinkService;
import com.apimarketplace.common.web.AdminRoleGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin CRUD for model execution links (CLOUD only). A link makes a billed
 * {@code (provider, model)} pair execute through a different execution provider -
 * a CLI bridge OR a regular API provider (e.g. openrouter) - while keeping the
 * billed identity for billing. Gated behind the same
 * {@code model-catalog.execution-links.enabled} flag as
 * {@link ModelExecutionLinkService}: absent in the CE monolith, so neither this
 * controller nor its dependency loads there. Admin-only via {@link AdminRoleGuard}.
 */
@Slf4j
@RestController
@RequestMapping("/api/model-config/execution-links")
@ConditionalOnProperty(name = "model-catalog.execution-links.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class ModelExecutionLinkController {

    private final ModelExecutionLinkService service;

    /** List all execution links. */
    @GetMapping
    public ResponseEntity<?> list(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;
        List<Map<String, Object>> out = service.list().stream()
                .map(ModelExecutionLinkController::toMap)
                .toList();
        return ResponseEntity.ok(out);
    }

    /**
     * Create or update a link. Body:
     * {@code { billedProvider, billedModel, executionProvider, executionModel?, scope?, enabled? }}.
     * {@code executionProvider} is any provider slug (a CLI bridge OR a regular API
     * provider like openrouter); {@code executionModel} blank/absent reuses
     * {@code billedModel}; {@code scope} narrows the link to one app surface
     * (ALL/CHAT/WORKFLOW/WEBHOOK/WIDGET/SCHEDULE/TASK/TASK_REVIEW, blank/absent ⇒ ALL,
     * an unknown value is 400); {@code enabled} defaults to true.
     */
    @PutMapping
    public ResponseEntity<?> upsert(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @RequestBody Map<String, Object> body) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;
        try {
            String billedProvider = requireString(body, "billedProvider");
            String billedModel = requireString(body, "billedModel");
            String executionProvider = requireString(body, "executionProvider");
            String executionModel = optionalString(body, "executionModel");
            ModelExecutionLinkScope scope = ModelExecutionLinkScope.parse(optionalString(body, "scope"));
            boolean enabled = optionalBoolean(body, "enabled", true);
            ModelExecutionLinkEntity saved = service.upsert(
                    billedProvider, billedModel, executionProvider, executionModel, scope, enabled);
            return ResponseEntity.ok(toMap(saved));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Delete the link for a billed {@code (provider, model)} pair on a given scope.
     * Query-param form, the canonical one: a billed model id may contain {@code /}
     * (any OpenRouter id, e.g. {@code meta-llama/llama-3.3-70b}), which cannot travel
     * as a path segment (the raw slash splits the path and the encoded {@code %2F} is
     * rejected by the default URL firewall), so path addressing made such links
     * undeletable.
     */
    @DeleteMapping
    public ResponseEntity<?> deleteByParams(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @RequestParam String billedProvider, @RequestParam String billedModel,
            @RequestParam(required = false) String scope) {
        return doDelete(roles, billedProvider, billedModel, scope);
    }

    /**
     * Legacy path form, kept for pre-existing callers. Only reachable for model ids
     * without {@code /} - use the query-param form otherwise.
     */
    @DeleteMapping("/{billedProvider}/{billedModel}/{scope}")
    public ResponseEntity<?> delete(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @PathVariable String billedProvider, @PathVariable String billedModel,
            @PathVariable String scope) {
        return doDelete(roles, billedProvider, billedModel, scope);
    }

    private ResponseEntity<?> doDelete(String roles, String billedProvider, String billedModel, String scope) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;
        try {
            boolean removed = service.delete(billedProvider, billedModel, ModelExecutionLinkScope.parse(scope));
            return removed ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private static Map<String, Object> toMap(ModelExecutionLinkEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("billedProvider", e.getBilledProvider());
        m.put("billedModel", e.getBilledModel());
        m.put("executionProvider", e.getExecutionProvider());
        m.put("executionModel", e.getExecutionModel());
        m.put("scope", e.getScope() != null ? e.getScope().name() : ModelExecutionLinkScope.ALL.name());
        m.put("enabled", e.isEnabled());
        return m;
    }

    private static String requireString(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (!(value instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException(key + " must be a non-blank string");
        }
        return s;
    }

    private static String optionalString(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null) return null;
        if (!(value instanceof String s)) {
            throw new IllegalArgumentException(key + " must be a string or null");
        }
        return s;
    }

    /**
     * Strict JSON boolean: absent ⇒ {@code defaultValue}, anything else must be a
     * real boolean. A lenient read ({@code Boolean.TRUE.equals}) silently turned
     * {@code "enabled": "true"} (string) into a DISABLED link with a 200 OK - the
     * admin saw success while the route never fired.
     */
    private static boolean optionalBoolean(Map<String, Object> body, String key, boolean defaultValue) {
        if (!body.containsKey(key)) return defaultValue;
        Object value = body.get(key);
        if (!(value instanceof Boolean b)) {
            throw new IllegalArgumentException(key + " must be a boolean");
        }
        return b;
    }
}

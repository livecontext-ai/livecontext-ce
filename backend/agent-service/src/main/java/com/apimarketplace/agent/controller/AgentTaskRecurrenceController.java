package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.domain.AgentTaskRecurrenceEntity;
import com.apimarketplace.agent.dto.CreateRecurrenceRequest;
import com.apimarketplace.agent.dto.RecurrenceResponse;
import com.apimarketplace.agent.dto.UpdateRecurrenceRequest;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.service.AgentTaskRecurrenceService;
import com.apimarketplace.common.web.TenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * User-facing REST API for cron-driven task recurrences.
 * Companion to {@link AgentTaskController}. Agent-to-agent callers use the
 * {@code agent} tool's {@code recurrence_*} actions; this controller is for
 * admin UIs that want to browse and edit the templates directly.
 */
@RestController
@RequestMapping("/api")
public class AgentTaskRecurrenceController {

    private final AgentTaskRecurrenceService recurrenceService;
    private final TenantResolver tenantResolver;
    private final AgentRepository agentRepository;

    public AgentTaskRecurrenceController(AgentTaskRecurrenceService recurrenceService,
                                          TenantResolver tenantResolver,
                                          AgentRepository agentRepository) {
        this.recurrenceService = recurrenceService;
        this.tenantResolver = tenantResolver;
        this.agentRepository = agentRepository;
    }

    /**
     * Guardrails the user-provided {@code agent_id} query parameter. Without
     * this any authenticated user could enumerate the recurrences of any agent
     * in their tenant simply by varying the query string.
     *
     * <p>Phase 6c (2026-05-19) - accept org-shared agents via the V217 strict-org
     * finder. Prior implementation used {@code existsByIdAndTenantId} which
     * rejected legitimate org-teammate access (org-shared agent stays stamped
     * with its creator's tenantId; a teammate's tenantId did not match).
     */
    private void assertAgentInScope(UUID agentId, String tenantId, String organizationId) {
        TenantResolver.requireOrgId(organizationId);
        if (!agentRepository.existsByIdAndOrganizationIdStrict(agentId, organizationId)) {
            throw new IllegalStateException(
                    "agent " + agentId + " is not visible from the caller's active workspace");
        }
    }

    /**
     * Org-role write gate: a VIEWER member is read-only, so recurrence
     * templates cannot be created, edited, or deleted. Mirrors the task
     * board gate in {@link AgentTaskController}.
     */
    private boolean isViewerRole(HttpServletRequest request) {
        String orgRole = tenantResolver.resolveOrgRole(request);
        return orgRole != null && "VIEWER".equalsIgnoreCase(orgRole.trim());
    }

    private static ResponseEntity<?> viewerForbidden() {
        return ResponseEntity.status(403).body(Map.of(
                "error", "your workspace role is read-only (VIEWER): recurrence mutations are not allowed"));
    }

    @GetMapping("/recurrences")
    public ResponseEntity<?> list(
            @RequestParam(value = "scope", required = false, defaultValue = "all_in_tenant") String scope,
            @RequestParam(value = "agent_id", required = false) UUID agentId,
            HttpServletRequest request) {
        String tenantId = tenantResolver.resolve(request);
        String orgId = tenantResolver.resolveOrgId(request);
        if (agentId != null) {
            try {
                assertAgentInScope(agentId, tenantId, orgId);
            } catch (IllegalStateException e) {
                return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
            }
        }
        try {
            List<AgentTaskRecurrenceEntity> list = recurrenceService.list(tenantId, orgId, agentId, scope);
            return ResponseEntity.ok(Map.of(
                    "count", list.size(),
                    "recurrences", list.stream().map(RecurrenceResponse::from).toList()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/recurrences")
    public ResponseEntity<?> create(@RequestBody CreateRecurrenceRequest request,
                                     HttpServletRequest httpRequest) {
        String tenantId = tenantResolver.resolve(httpRequest);
        if (isViewerRole(httpRequest)) {
            return viewerForbidden();
        }
        try {
            AgentTaskRecurrenceEntity r = recurrenceService.create(tenantId, null, tenantId, request);
            return ResponseEntity.ok(RecurrenceResponse.from(r));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/recurrences/{recurrenceId}")
    public ResponseEntity<?> update(@PathVariable UUID recurrenceId,
                                     @RequestBody UpdateRecurrenceRequest request,
                                     HttpServletRequest httpRequest) {
        String tenantId = tenantResolver.resolve(httpRequest);
        if (isViewerRole(httpRequest)) {
            return viewerForbidden();
        }
        try {
            AgentTaskRecurrenceEntity r = recurrenceService.update(tenantId, recurrenceId, null, tenantId, request);
            return ResponseEntity.ok(RecurrenceResponse.from(r));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/recurrences/{recurrenceId}")
    public ResponseEntity<?> delete(@PathVariable UUID recurrenceId,
                                     HttpServletRequest httpRequest) {
        String tenantId = tenantResolver.resolve(httpRequest);
        if (isViewerRole(httpRequest)) {
            return viewerForbidden();
        }
        try {
            recurrenceService.delete(tenantId, recurrenceId, null, tenantId);
            return ResponseEntity.ok(Map.of("deleted", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        }
    }
}

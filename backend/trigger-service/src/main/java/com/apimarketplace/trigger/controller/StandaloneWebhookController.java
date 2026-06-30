package com.apimarketplace.trigger.controller;

import com.apimarketplace.trigger.client.dto.*;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.trigger.repository.StandaloneWebhookRepository;
import com.apimarketplace.trigger.service.PlanLimitHelper;
import com.apimarketplace.trigger.service.StandaloneWebhookService;
import com.apimarketplace.trigger.service.WorkflowReferenceImmutableException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for standalone webhook CRUD operations.
 */
@RestController
@RequestMapping("/api/webhooks")
public class StandaloneWebhookController {

    private final StandaloneWebhookService webhookService;
    private final StandaloneWebhookRepository webhookRepository;
    private final TenantResolver tenantResolver;
    private final PlanLimitHelper planLimitHelper;

    public StandaloneWebhookController(StandaloneWebhookService webhookService,
                                       StandaloneWebhookRepository webhookRepository,
                                       TenantResolver tenantResolver,
                                       PlanLimitHelper planLimitHelper) {
        this.webhookService = webhookService;
        this.webhookRepository = webhookRepository;
        this.tenantResolver = tenantResolver;
        this.planLimitHelper = planLimitHelper;
    }

    @GetMapping
    public ResponseEntity<List<StandaloneWebhookDto>> getAll(HttpServletRequest request) {
        String tenantId = tenantResolver.resolve(request);
        // Strict-isolation list (post-V261: every row carries a non-null org).
        String organizationId = requireOrgId(request);
        return ResponseEntity.ok(webhookService.getAll(tenantId, organizationId));
    }

    @PostMapping
    public ResponseEntity<?> create(
            @RequestBody StandaloneWebhookRequest body,
            HttpServletRequest request) {
        String tenantId = tenantResolver.resolve(request);
        String organizationId = requireOrgId(request);
        String userPlan = request.getHeader("X-User-Plan");

        // Plan-limit check lives inside the service, AFTER the sourceNodeId
        // dedup: a refresh at-limit must return the existing row rather than 400.
        try {
            return ResponseEntity.ok(webhookService.create(tenantId, organizationId, userPlan, body));
        } catch (IllegalStateException e) {
            // PlanLimitHelper throws "Resource limit reached: <n>/<max>" - surface
            // the same shape as before so existing callers don't break.
            int maxEndpoints = planLimitHelper.getMaxEndpoints(userPlan);
            long currentCount = webhookRepository.countByOrganizationIdStrictAndWorkflowIdIsNotNull(organizationId);
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Webhook endpoint limit reached",
                    "currentCount", currentCount,
                    "maxPerUser", maxEndpoints));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<StandaloneWebhookDto> getById(
            @PathVariable("id") UUID id,
            HttpServletRequest request) {
        String tenantId = tenantResolver.resolve(request);
        String organizationId = requireOrgId(request);
        try {
            return ResponseEntity.ok(webhookService.getById(tenantId, organizationId, id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<StandaloneWebhookDto> update(
            @PathVariable("id") UUID id,
            @RequestBody StandaloneWebhookRequest body,
            HttpServletRequest request) {
        String tenantId = tenantResolver.resolve(request);
        String organizationId = requireOrgId(request);
        try {
            return ResponseEntity.ok(webhookService.update(tenantId, organizationId, id, body));
        } catch (WorkflowReferenceImmutableException e) {
            return ResponseEntity.badRequest().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable("id") UUID id,
            HttpServletRequest request) {
        String tenantId = tenantResolver.resolve(request);
        String organizationId = requireOrgId(request);
        try {
            webhookService.delete(tenantId, organizationId, id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/regenerate-token")
    public ResponseEntity<StandaloneWebhookDto> regenerateToken(
            @PathVariable("id") UUID id,
            HttpServletRequest request) {
        String tenantId = tenantResolver.resolve(request);
        String organizationId = requireOrgId(request);
        try {
            return ResponseEntity.ok(webhookService.regenerateToken(tenantId, organizationId, id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/{id}/logs")
    public ResponseEntity<Page<WebhookCallLogDto>> getCallLogs(
            @PathVariable("id") UUID id,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            HttpServletRequest request) {
        String tenantId = tenantResolver.resolve(request);
        String organizationId = requireOrgId(request);
        try {
            return ResponseEntity.ok(webhookService.getCallLogs(tenantId, organizationId, id, page, size));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PatchMapping("/{id}/workflow")
    public ResponseEntity<StandaloneWebhookDto> updateWorkflowReference(
            @PathVariable("id") UUID id,
            @RequestBody WorkflowReferenceRequest body,
            HttpServletRequest request) {
        String tenantId = tenantResolver.resolve(request);
        String organizationId = requireOrgId(request);
        UUID workflowId = body.workflowId() != null ? UUID.fromString(body.workflowId()) : null;
        try {
            return ResponseEntity.ok(webhookService.updateWorkflowReference(tenantId, organizationId, id, workflowId, body.workflowName()));
        } catch (WorkflowReferenceImmutableException e) {
            return ResponseEntity.badRequest().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig(HttpServletRequest request) {
        String tenantId = tenantResolver.resolve(request);
        String organizationId = requireOrgId(request);
        Map<String, Object> config = new java.util.LinkedHashMap<>(webhookService.getConfig(tenantId, organizationId));
        String userPlan = request.getHeader("X-User-Plan");
        config.put("maxPerUser", planLimitHelper.getMaxEndpoints(userPlan));
        return ResponseEntity.ok(config);
    }

    /**
     * Resolve X-Organization-ID from the request, treating absence as 400. Post-V261
     * the gateway always injects the header (personal-workspace users get their
     * personal org UUID), so missing → client misconfiguration.
     */
    private String requireOrgId(HttpServletRequest request) {
        String organizationId = tenantResolver.resolveOrgId(request);
        if (organizationId == null || organizationId.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "X-Organization-ID header is required (post-V261)");
        }
        return organizationId;
    }
}

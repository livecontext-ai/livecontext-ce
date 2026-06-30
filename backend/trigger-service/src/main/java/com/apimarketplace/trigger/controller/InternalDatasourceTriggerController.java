package com.apimarketplace.trigger.controller;

import com.apimarketplace.trigger.client.dto.DatasourceEventDispatchRequest;
import com.apimarketplace.trigger.client.dto.DatasourceSubscriptionRequest;
import com.apimarketplace.trigger.client.dto.DatasourceTriggerSubscriptionDto;
import com.apimarketplace.trigger.service.DatasourceTriggerSubscriptionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Internal API for the event-driven datasource trigger.
 *
 * Exposes two surfaces:
 *   - Subscription CRUD - called by orchestrator's DatasourceSubscriptionSyncService
 *     when a workflow is pinned/unpinned/deleted.
 *   - Event dispatch - called by datasource-service's DatasourceRowEventListener
 *     once a row CRUD commit completes.
 */
@RestController
@RequestMapping("/api/internal/trigger")
public class InternalDatasourceTriggerController {

    private final DatasourceTriggerSubscriptionService subscriptionService;

    public InternalDatasourceTriggerController(DatasourceTriggerSubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    @PostMapping("/datasource-subscriptions")
    public ResponseEntity<DatasourceTriggerSubscriptionDto> upsert(@RequestBody DatasourceSubscriptionRequest request) {
        DatasourceTriggerSubscriptionDto dto = subscriptionService.upsert(request);
        return ResponseEntity.ok(dto);
    }

    @GetMapping("/datasource-subscriptions/by-workflow/{workflowId}")
    public ResponseEntity<List<DatasourceTriggerSubscriptionDto>> byWorkflow(
            @PathVariable("workflowId") UUID workflowId) {
        return ResponseEntity.ok(subscriptionService.findByWorkflow(workflowId));
    }

    @DeleteMapping("/datasource-subscriptions/by-workflow/{workflowId}")
    public ResponseEntity<Void> deleteByWorkflow(@PathVariable("workflowId") UUID workflowId) {
        subscriptionService.deleteByWorkflow(workflowId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/datasource-subscriptions/by-workflow/{workflowId}/prune")
    public ResponseEntity<Map<String, Integer>> prune(@PathVariable("workflowId") UUID workflowId,
                                                       @RequestBody List<String> currentTriggerIds) {
        int removed = subscriptionService.prune(workflowId, currentTriggerIds);
        return ResponseEntity.ok(Map.of("removed", removed));
    }

    @PostMapping("/datasource-events/dispatch")
    public ResponseEntity<Map<String, Integer>> dispatchEvent(@RequestBody DatasourceEventDispatchRequest event) {
        int fired = subscriptionService.dispatchEvent(event);
        return ResponseEntity.ok(Map.of("fired", fired));
    }
}

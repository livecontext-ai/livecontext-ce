package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.orchestrator.services.OptionalFeatureCapabilityService;
import com.apimarketplace.orchestrator.services.OptionalFeatureCapabilityService.FeatureCapabilities;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only availability of the deployment's optional components (screenshot/PDF
 * renderer sidecar, browser agent + web search stack). The builder inspector and
 * the CE settings page use it to warn "enable the optional component" instead of
 * letting the feature silently no-op on installs that did not opt in.
 *
 * <p>Literal path - Spring's mapping comparator prefers it over the sibling
 * {@code /api/workflows/{workflowId}} template, so no route conflict.</p>
 */
@RestController
@RequestMapping("/api/workflows")
public class WorkflowCapabilitiesController {

    private final OptionalFeatureCapabilityService capabilityService;

    public WorkflowCapabilitiesController(OptionalFeatureCapabilityService capabilityService) {
        this.capabilityService = capabilityService;
    }

    @GetMapping("/capabilities")
    public ResponseEntity<FeatureCapabilities> getCapabilities(
        @RequestHeader(value = "X-User-ID", required = false) String tenantId
    ) {
        return ResponseEntity.ok(capabilityService.resolve(tenantId));
    }
}

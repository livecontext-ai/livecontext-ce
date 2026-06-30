package com.apimarketplace.orchestrator.controllers.internal;

import com.apimarketplace.orchestrator.services.InterfaceReferenceCascadeService;
import com.apimarketplace.orchestrator.services.InterfaceReferenceCascadeService.CascadeResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Internal endpoint called by interface-service to scrub references to a
 * deleted interface from every workflow plan in the same tenant. The caller
 * invokes this BEFORE deleting the {@code interface.interfaces} row so the
 * plan→interface link is severed atomically (cascade fail → row delete
 * aborted).
 *
 * <p>Same security pattern as sibling internal endpoints: {@code
 * /api/internal/**} is exempt from the gateway filter and reachable only on
 * the private VPC, so the {@code X-Gateway-Secret} header is not required
 * for service-to-service calls within the cluster.
 */
@RestController
@RequestMapping("/api/internal/orchestrator/workflows")
public class InternalInterfaceCascadeController {

    private static final Logger log = LoggerFactory.getLogger(InternalInterfaceCascadeController.class);

    private final InterfaceReferenceCascadeService cascadeService;

    public InternalInterfaceCascadeController(InterfaceReferenceCascadeService cascadeService) {
        this.cascadeService = cascadeService;
    }

    /**
     * Scrubs every plan in the given tenant that references the interface id.
     * Returns a structured summary of the cascade so the caller can log/audit
     * how many workflows were touched.
     *
     * <p>Tenant scope is mandatory - without it we'd risk silently mutating
     * other tenants' plans on a UUID collision (extremely unlikely for v4
     * UUIDs, but the contract is explicit).
     */
    @PostMapping("/strip-interface/{interfaceId}")
    public ResponseEntity<CascadeResult> stripInterface(
            @PathVariable String interfaceId,
            @RequestParam("tenantId") String tenantId) {
        log.info("[InterfaceCascade] strip-interface request tenant={} interface={}", tenantId, interfaceId);
        CascadeResult result = cascadeService.stripReferences(tenantId, interfaceId);
        log.info("[InterfaceCascade] strip-interface result tenant={} interface={} workflowsTouched={} entries={} edges={}",
            tenantId, interfaceId,
            result.workflowsTouched(), result.interfaceEntriesRemoved(), result.edgesRemoved());
        return ResponseEntity.ok(result);
    }
}

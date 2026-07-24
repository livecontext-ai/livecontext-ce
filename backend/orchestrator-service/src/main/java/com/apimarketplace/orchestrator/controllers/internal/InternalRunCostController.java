package com.apimarketplace.orchestrator.controllers.internal;

import com.apimarketplace.orchestrator.services.credit.RunCostService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Internal endpoint called by agent-service after an agent execution settles its
 * credits, so the orchestrator can accumulate the run's cost and broadcast it to
 * the run-mode UI. Agent executions are the only cost source inside a run.
 *
 * <p>Same security posture as sibling {@code /api/internal/**} endpoints:
 * exempt from the gateway filter, reachable only on the private VPC. Available
 * in both the microservice topology and the CE monolith (agent-service calls it
 * over {@code services.orchestrator-url}, which is the same process in CE).
 *
 * <p>Best-effort by contract: a failure here must never break the agent's
 * observability/credit path, so it returns {@code 200 {"status":"recorded"}}
 * even when nothing matched (the service logs the reason).
 */
@RestController
@RequestMapping("/api/internal/orchestrator/runs")
public class InternalRunCostController {

    private static final Logger log = LoggerFactory.getLogger(InternalRunCostController.class);

    private final RunCostService runCostService;

    public InternalRunCostController(RunCostService runCostService) {
        this.runCostService = runCostService;
    }

    /**
     * Record a settled agent cost on a run.
     *
     * @param runIdPublic the public run id (path)
     * @param body        {@code {organizationId?, epoch, credits}} - credits in
     *                    credits (1 credit = $0.001); organizationId null for
     *                    personal scope
     */
    @PostMapping("/{runIdPublic}/cost")
    public ResponseEntity<Map<String, String>> recordCost(
            @PathVariable String runIdPublic,
            @RequestBody Map<String, Object> body) {
        try {
            String orgId = asString(body.get("organizationId"));
            int epoch = asInt(body.get("epoch"));
            BigDecimal credits = asDecimal(body.get("credits"));
            runCostService.recordAgentCost(runIdPublic, orgId, epoch, credits);
        } catch (Exception e) {
            // Never fail the caller - cost tracking is best-effort.
            log.warn("[RunCost] recordCost failed for runId={}: {}", runIdPublic, e.getMessage());
        }
        return ResponseEntity.ok(Map.of("status", "recorded"));
    }

    private static String asString(Object v) {
        if (v == null) return null;
        String s = v.toString();
        return s.isBlank() ? null : s;
    }

    private static int asInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        if (v == null) return 0;
        try {
            return Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static BigDecimal asDecimal(Object v) {
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        if (v == null) return BigDecimal.ZERO;
        try {
            return new BigDecimal(v.toString());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }
}

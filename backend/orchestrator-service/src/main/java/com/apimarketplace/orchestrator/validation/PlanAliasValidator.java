package com.apimarketplace.orchestrator.validation;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.metrics.WorkflowMetrics;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Validates alias uniqueness in a WorkflowPlan.
 *
 * <p>The runtime resolves template references like {@code {{foo.output.bar}}} via a
 * normalized-alias → full-key map (see {@code V2StepByStepContextManager.buildAliasMapping}).
 * That map uses last-wins semantics on insertion. If two nodes - say {@code mcp:read_email}
 * and {@code trigger:read_email} - share the alias {@code read_email}, the alias points to
 * one or the other depending on plan iteration order. Bug is silent and non-deterministic.
 *
 * <p>This validator runs at boot (ExecutionTreeBuilder) and either fails fast (strict mode)
 * or logs a warning (lenient mode, default).
 *
 * <h3>Strict-mode rollout runbook</h3>
 * <ol>
 *   <li>Deploy with {@code services.plan-validation.alias-uniqueness.strict=false} (default).</li>
 *   <li>Watch the Prometheus counter {@code orchestrator_context_alias_collision_count{type="plan_boot"}}.
 *       A non-zero rate over 24h means a deployed plan still has collisions - fix the plans
 *       (rename ambiguous labels) before flipping strict.</li>
 *   <li>After 7 consecutive days of zero plan_boot collisions, set
 *       {@code PLAN_ALIAS_VALIDATION_STRICT=true} in the orchestrator env.</li>
 *   <li>Rollback procedure: if any run hits {@link PlanAliasValidationException} after flip,
 *       unset the env var, re-deploy, then clean the offending plan(s) and retry.</li>
 * </ol>
 */
@Service
public class PlanAliasValidator {

    private static final Logger logger = LoggerFactory.getLogger(PlanAliasValidator.class);

    private final boolean strict;
    private final WorkflowMetrics metrics;

    @Autowired
    public PlanAliasValidator(
            @Value("${services.plan-validation.alias-uniqueness.strict:false}") boolean strict,
            WorkflowMetrics metrics) {
        this.strict = strict;
        this.metrics = metrics;
        logger.info("[PlanAliasValidator] Initialized - strict mode: {}", strict);
    }

    /**
     * Test-only constructor - bypasses the metrics dependency.
     */
    public PlanAliasValidator(boolean strict) {
        this(strict, null);
    }

    /**
     * Validates that every normalized alias in the plan maps to exactly one full-key.
     *
     * @param plan the plan to validate
     * @throws PlanAliasValidationException in strict mode when collisions exist
     */
    public void validate(WorkflowPlan plan) {
        if (plan == null) {
            return;
        }

        Map<String, List<String>> aliasToKeys = collectAliasToKeys(plan);
        Map<String, List<String>> collisions = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : aliasToKeys.entrySet()) {
            if (entry.getValue().size() > 1) {
                collisions.put(entry.getKey(), entry.getValue());
            }
        }

        if (collisions.isEmpty()) {
            logger.debug("[PlanAliasValidator] Plan OK - {} aliases, no collisions", aliasToKeys.size());
            return;
        }

        // Increment the alias-collision counter by the magnitude of the failure so Grafana
        // sees how bad the plan is, not just "at least one". Tagged as plan_boot to
        // distinguish from runtime loop-override collisions.
        if (metrics != null) {
            metrics.recordAliasCollision("plan_boot", collisions.size());
        }

        if (strict) {
            logger.error("[PlanAliasValidator] Plan REJECTED - {} alias collision(s): {}",
                collisions.size(), collisions);
            throw new PlanAliasValidationException(collisions);
        } else {
            logger.warn("[PlanAliasValidator] Plan has {} alias collision(s) (lenient mode - not failing): {}",
                collisions.size(), collisions);
        }
    }

    /**
     * Builds a map of normalized-alias → list of full-keys that share it.
     * Mirrors {@code V2StepByStepContextManager.buildAliasMapping} but accumulates
     * collisions instead of overwriting.
     */
    private Map<String, List<String>> collectAliasToKeys(WorkflowPlan plan) {
        Map<String, List<String>> result = new HashMap<>();

        if (plan.getTriggers() != null) {
            for (var trigger : plan.getTriggers()) {
                String label = trigger.label() != null ? trigger.label() : trigger.id();
                record(result, LabelNormalizer.normalizeLabel(label), trigger.getNormalizedKey());
            }
        }
        if (plan.getMcps() != null) {
            for (var step : plan.getMcps()) {
                record(result, LabelNormalizer.normalizeLabel(step.label()), step.getNormalizedKey());
            }
        }
        if (plan.getAgents() != null) {
            for (var agent : plan.getAgents()) {
                record(result, LabelNormalizer.normalizeLabel(agent.label()), agent.getNormalizedKey());
            }
        }
        if (plan.getCores() != null) {
            for (var core : plan.getCores()) {
                String label = core.label() != null ? core.label() : core.id();
                record(result, LabelNormalizer.normalizeLabel(label), core.getNormalizedKey());
            }
        }
        if (plan.getTables() != null) {
            for (var table : plan.getTables()) {
                String normalizedLabel = LabelNormalizer.normalizeLabel(table.label());
                if (normalizedLabel != null) {
                    record(result, normalizedLabel, "table:" + normalizedLabel);
                }
            }
        }
        if (plan.getInterfaces() != null) {
            for (var iface : plan.getInterfaces()) {
                record(result, LabelNormalizer.normalizeLabel(iface.label()), iface.getNormalizedKey());
            }
        }

        return result;
    }

    private static void record(Map<String, List<String>> map, String alias, String fullKey) {
        if (alias == null || fullKey == null) return;
        List<String> keys = map.computeIfAbsent(alias, k -> new ArrayList<>());
        if (!keys.contains(fullKey)) {
            keys.add(fullKey);
        }
    }
}

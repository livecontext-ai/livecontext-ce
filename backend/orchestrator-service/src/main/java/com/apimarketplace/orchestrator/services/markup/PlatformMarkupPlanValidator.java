package com.apimarketplace.orchestrator.services.markup;

import com.apimarketplace.orchestrator.domain.workflow.CredentialSource;
import com.apimarketplace.orchestrator.domain.workflow.Step;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Submit-time validation for platform-credential markup invariants.
 * <p>
 * Enforced rules (fail-fast - throws {@link InvalidMarkupPlanException}):
 * <ol>
 *   <li>Every step with {@code credentialSource=platform} must carry a
 *       non-null {@code platformCredentialId}. (Also guarded in the
 *       {@link Step} compact constructor; this is defence-in-depth at the
 *       plan boundary so a malformed plan is rejected before any pin is
 *       attempted.)</li>
 *   <li>A step with {@code credentialSource=user} must NOT carry a
 *       {@code platformCredentialId} - that combination is semantically
 *       contradictory (the user cred owns the call, the platform cred would
 *       bill markup for a call it didn't make).</li>
 *   <li>Only {@code mcp} steps may reference a platform credential. CRUD
 *       steps operate on user-owned datasources and never go through a
 *       platform key, so a {@code credentialSource=platform} on any non-mcp
 *       step is a modelling bug.</li>
 * </ol>
 * <p>
 * All failures are collected and surfaced together so the agent / frontend
 * can fix the whole plan in one pass instead of one warning per iteration.
 */
@Service
public class PlatformMarkupPlanValidator {

    public void validate(WorkflowPlan plan) {
        if (plan == null) return;
        List<String> errors = new ArrayList<>();
        collectErrors(plan.getMcps(), "mcp", errors, true);
        collectErrors(plan.getTables(), "table", errors, false);
        if (!errors.isEmpty()) {
            throw new InvalidMarkupPlanException(errors);
        }
    }

    private void collectErrors(List<Step> steps, String kind, List<String> errors, boolean allowPlatform) {
        if (steps == null) return;
        for (Step step : steps) {
            CredentialSource src = step.credentialSource();
            Long credId = step.platformCredentialId();
            if (src == CredentialSource.PLATFORM) {
                if (!allowPlatform) {
                    errors.add(kind + " step '" + step.label() + "' cannot use a platform credential - only mcp steps bill markup");
                    continue;
                }
                if (credId == null) {
                    errors.add("mcp step '" + step.label() + "' sets credentialSource=platform but omits platformCredentialId");
                }
            } else if (credId != null) {
                errors.add(kind + " step '" + step.label() + "' has credentialSource=user but also sets platformCredentialId - ambiguous billing");
            }
        }
    }

    public static final class InvalidMarkupPlanException extends RuntimeException {
        private final List<String> violations;

        public InvalidMarkupPlanException(List<String> violations) {
            super("Plan violates platform-markup invariants: " + String.join("; ", violations));
            this.violations = List.copyOf(violations);
        }

        public List<String> getViolations() {
            return violations;
        }
    }
}

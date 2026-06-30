package com.apimarketplace.orchestrator.validation;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Thrown when a WorkflowPlan contains alias collisions.
 *
 * An alias collision happens when two or more nodes (across triggers, mcps, agents,
 * cores, tables, interfaces) normalize to the same label. The runtime stepOutputs map
 * uses last-wins semantics on aliases, which would silently route a template like
 * {@code {{read_email.output.body}}} to whichever node was inserted last in the alias
 * mapping. The order depends on plan iteration sequence and is non-deterministic
 * across plan edits - a latent bug that surfaces only at runtime.
 *
 * <p>Caught at boot (ExecutionTreeBuilder) so the run refuses to start instead of
 * producing the wrong output silently.
 */
public class PlanAliasValidationException extends RuntimeException {

    private final Map<String, List<String>> collisions;

    public PlanAliasValidationException(Map<String, List<String>> collisions) {
        super(buildMessage(collisions));
        this.collisions = collisions;
    }

    private static String buildMessage(Map<String, List<String>> collisions) {
        String details = collisions.entrySet().stream()
            .map(e -> String.format("'%s' -> %s", e.getKey(), e.getValue()))
            .collect(Collectors.joining("; "));
        return String.format(
            "Plan has %d alias collision(s): %s. " +
            "Each alias must map to exactly one node - rename one of the conflicting nodes.",
            collisions.size(), details
        );
    }

    public Map<String, List<String>> getCollisions() {
        return collisions;
    }
}

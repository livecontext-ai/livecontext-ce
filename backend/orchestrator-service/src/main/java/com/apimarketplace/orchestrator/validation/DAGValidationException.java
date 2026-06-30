package com.apimarketplace.orchestrator.validation;

import java.util.List;
import java.util.Set;

/**
 * Exception thrown when DAG validation fails.
 * Typically occurs when multiple triggers share descendant nodes,
 * which violates the independence requirement for multi-DAG workflows.
 */
public class DAGValidationException extends RuntimeException {

    private final String trigger1Id;
    private final String trigger2Id;
    private final Set<String> sharedNodes;

    public DAGValidationException(String trigger1Id, String trigger2Id, Set<String> sharedNodes) {
        super(buildMessage(trigger1Id, trigger2Id, sharedNodes));
        this.trigger1Id = trigger1Id;
        this.trigger2Id = trigger2Id;
        this.sharedNodes = sharedNodes;
    }

    public DAGValidationException(String message) {
        super(message);
        this.trigger1Id = null;
        this.trigger2Id = null;
        this.sharedNodes = Set.of();
    }

    private static String buildMessage(String trigger1Id, String trigger2Id, Set<String> sharedNodes) {
        return String.format(
            "Triggers '%s' and '%s' share %d node(s): %s. " +
            "Each trigger must define an independent DAG with no shared descendants.",
            trigger1Id, trigger2Id, sharedNodes.size(), sharedNodes
        );
    }

    public String getTrigger1Id() {
        return trigger1Id;
    }

    public String getTrigger2Id() {
        return trigger2Id;
    }

    public Set<String> getSharedNodes() {
        return sharedNodes;
    }
}

package com.apimarketplace.orchestrator.domain.workflow;

import java.util.List;

/**
 * Represents a detected merge node in the workflow graph.
 * A merge node is any node with multiple incoming edges.
 */
public record MergeNode(String stepId, List<String> sources, String strategy) {

    public boolean isValid() {
        return stepId != null && sources != null && sources.size() > 1;
    }

    public int getSourceCount() {
        return sources != null ? sources.size() : 0;
    }
}

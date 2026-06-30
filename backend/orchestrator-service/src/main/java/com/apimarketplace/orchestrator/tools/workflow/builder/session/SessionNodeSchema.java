package com.apimarketplace.orchestrator.tools.workflow.builder.session;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * Schema information for a node (outputs, references).
 */
@Data
@Builder
public class SessionNodeSchema {
    private String nodeId;
    private String nodeType; // trigger, step, agent, loop, decision
    private String label;
    private String toolId; // For steps with catalog tools
    private Map<String, String> outputs; // field -> type
    private Map<String, String> referenceSyntax; // field -> ${...} syntax
}

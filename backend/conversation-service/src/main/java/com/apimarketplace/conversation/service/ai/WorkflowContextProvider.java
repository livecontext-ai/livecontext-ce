package com.apimarketplace.conversation.service.ai;

import com.apimarketplace.common.web.OrgContextHeaderForwarder;
import com.apimarketplace.conversation.entity.Conversation;
import com.apimarketplace.conversation.repository.ConversationRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Provides workflow context for conversation-linked workflows.
 * Fetches workflow info from orchestrator-service to build specialized prompts.
 */
@Slf4j
@Service
public class WorkflowContextProvider {

    private final ConversationRepository conversationRepository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${orchestrator.service.url:http://localhost:8099}")
    private String orchestratorUrl;

    @Value("${services.agent-service.url:http://localhost:8090}")
    private String agentServiceUrl;

    public WorkflowContextProvider(ConversationRepository conversationRepository, RestTemplate restTemplate) {
        this.conversationRepository = conversationRepository;
        this.restTemplate = restTemplate;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Context holder for workflow information.
     * Matches DefaultSystemPrompts.buildWorkflowPrompt() signature.
     */
    public record WorkflowContext(
        String workflowId,
        String workflowName,
        String workflowStatus,
        String flowDiagram,
        String datasourceId,
        String lastRunInfo,
        boolean readOnly
    ) {
        public boolean isPresent() {
            return workflowId != null && !workflowId.isBlank();
        }
    }

    /**
     * Lightweight metadata from a single findById - shared by workflow context and agent resolution.
     */
    public record ConversationMeta(String workflowId, String agentId, Map<String, Object> chatConfig,
                                   Map<String, Object> summaryCold) {
        /**
         * Backward-compatible 3-arg form for call sites that don't carry a COLD
         * compaction summary (tests, synthetic metadata). Equivalent to a null
         * {@code summaryCold}.
         */
        public ConversationMeta(String workflowId, String agentId, Map<String, Object> chatConfig) {
            this(workflowId, agentId, chatConfig, null);
        }

        public static ConversationMeta empty() { return new ConversationMeta(null, null, null, null); }
    }

    /**
     * Load conversation metadata (workflowId + agentId) with a single DB query.
     * Called once per request; results are passed to getWorkflowContext and used for agent resolution.
     */
    public ConversationMeta getConversationMeta(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return ConversationMeta.empty();
        }
        try {
            return conversationRepository.findById(conversationId)
                .map(c -> new ConversationMeta(c.getWorkflowId(), c.getAgentId(), c.getChatConfig(), c.getSummaryCold()))
                .orElse(ConversationMeta.empty());
        } catch (Exception e) {
            log.warn("Error loading conversation meta for {}: {}", conversationId, e.getMessage());
            return ConversationMeta.empty();
        }
    }

    /**
     * Get workflow context for a conversation.
     *
     * @param conversationId The conversation ID
     * @param tenantId The tenant ID for authorization
     * @return WorkflowContext if conversation is linked to a workflow, empty context otherwise
     */
    public WorkflowContext getWorkflowContext(String conversationId, String tenantId) {
        return getWorkflowContext(getConversationMeta(conversationId), tenantId);
    }

    /**
     * Get workflow context from pre-loaded metadata (avoids duplicate findById).
     */
    public WorkflowContext getWorkflowContext(ConversationMeta meta, String tenantId) {
        if (meta == null || meta.workflowId() == null || meta.workflowId().isBlank()) {
            return emptyContext();
        }

        try {
            log.info("Fetching workflow context for workflow: {}", meta.workflowId());
            return fetchWorkflowContext(meta.workflowId(), tenantId);
        } catch (Exception e) {
            log.warn("Error getting workflow context for workflow {}: {}", meta.workflowId(), e.getMessage());
            return emptyContext();
        }
    }

    private WorkflowContext fetchWorkflowContext(String workflowId, String tenantId) {
        try {
            // Fetch workflow details from orchestrator-service
            String url = orchestratorUrl + "/api/workflows/" + workflowId;

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (tenantId != null) {
                headers.set("X-User-ID", tenantId);
            }
            // 2026-05-21 - forward org context so workflow lookup resolves
            // the caller's active workspace instead of personal scope.
            OrgContextHeaderForwarder.forward(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
            );

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("Failed to fetch workflow {}: {}", workflowId, response.getStatusCode());
                return simpleContext(workflowId);
            }

            JsonNode workflow = objectMapper.readTree(response.getBody());
            return parseWorkflowContext(workflowId, workflow);

        } catch (Exception e) {
            log.warn("Error fetching workflow {}: {}", workflowId, e.getMessage());
            return simpleContext(workflowId);
        }
    }

    private WorkflowContext parseWorkflowContext(String workflowId, JsonNode workflow) {
        String name = getTextOrDefault(workflow, "name", "Unknown Workflow");
        String status = getTextOrDefault(workflow, "status", "UNKNOWN");

        // Build flow diagram
        String flowDiagram = buildFlowDiagram(workflow);

        // Extract datasource ID from trigger
        String datasourceId = extractDatasourceId(workflow);

        // Parse last run info
        String lastRunInfo = parseLastRunInfo(workflow);

        // Extract readOnly flag (marketplace-acquired workflows)
        boolean readOnly = workflow.path("readOnly").asBoolean(false);

        return new WorkflowContext(workflowId, name, status, flowDiagram, datasourceId, lastRunInfo, readOnly);
    }

    /**
     * Build a compact flow diagram showing node connections.
     * Format: [trigger] → node1 → node2 → [Interface]
     *                        └→ branch ┘  (for forks/merges)
     *
     * Includes all node types:
     * - Triggers, Steps, Agents ([AI]), Loops (⟳), Decisions (◇), Interfaces ([UI])
     *
     * Interfaces are terminal nodes that only receive connections.
     */
    private String buildFlowDiagram(JsonNode workflow) {
        JsonNode plan = workflow.path("plan");
        if (plan.isMissingNode()) {
            return "(no plan)";
        }

        // Collect all nodes
        Map<String, String> nodeLabels = new HashMap<>(); // id -> label
        Map<String, String> nodeTypes = new HashMap<>();  // id -> type

        // Track interface connections (nodeId -> list of interfaceIds)
        Map<String, List<String>> interfaceLinks = new HashMap<>();

        // Parse triggers - use normalized label as key (matches edge format)
        JsonNode triggers = plan.path("triggers");
        if (triggers.isArray()) {
            for (JsonNode trigger : triggers) {
                String label = getTextOrDefault(trigger, "label", "trigger");
                String nodeKey = "trigger:" + normalizeLabel(label);
                nodeLabels.put(nodeKey, label);
                nodeTypes.put(nodeKey, "trigger");
                // Extract linked interfaces
                extractInterfaceIds(trigger, nodeKey, interfaceLinks, nodeLabels, nodeTypes);
            }
        }

        // Parse mcps (steps) - use normalized label as key
        // Support both "mcps" (new format) and "steps" (legacy) for backward compatibility
        JsonNode mcps = plan.path("mcps");
        if (mcps.isMissingNode()) {
            mcps = plan.path("steps");  // Fallback to legacy "steps" field
        }
        if (mcps.isArray()) {
            for (JsonNode mcp : mcps) {
                String label = getTextOrDefault(mcp, "label", "step");
                String nodeKey = "mcp:" + normalizeLabel(label);
                nodeLabels.put(nodeKey, label);
                nodeTypes.put(nodeKey, "mcp");
                // Extract linked interfaces
                extractInterfaceIds(mcp, nodeKey, interfaceLinks, nodeLabels, nodeTypes);
            }
        }

        // Parse agents - use normalized label as key
        JsonNode agents = plan.path("agents");
        if (agents.isArray()) {
            for (JsonNode agent : agents) {
                String label = getTextOrDefault(agent, "label", "agent");
                String nodeKey = "agent:" + normalizeLabel(label);
                nodeLabels.put(nodeKey, "[AI] " + label);
                nodeTypes.put(nodeKey, "agent");
                // Extract linked interfaces
                extractInterfaceIds(agent, nodeKey, interfaceLinks, nodeLabels, nodeTypes);
            }
        }

        // Parse cores (loops, decisions) - use normalized label as key
        JsonNode cores = plan.path("cores");
        if (cores.isArray()) {
            for (JsonNode cn : cores) {
                String label = getTextOrDefault(cn, "label", "control");
                String type = getTextOrDefault(cn, "type", "unknown");
                String prefix = "core:";
                String nodeKey = prefix + normalizeLabel(label);
                String displayPrefix = "loop".equals(type) ? "⟳ " : "◇ ";
                nodeLabels.put(nodeKey, displayPrefix + label);
                nodeTypes.put(nodeKey, type);
                // Extract linked interfaces
                extractInterfaceIds(cn, nodeKey, interfaceLinks, nodeLabels, nodeTypes);
            }
        }

        // Parse edges
        JsonNode edges = plan.path("edges");
        Map<String, List<String>> outgoing = new HashMap<>(); // from -> [to1, to2, ...]
        Map<String, List<String>> incoming = new HashMap<>(); // to -> [from1, from2, ...]

        if (edges.isArray()) {
            for (JsonNode edge : edges) {
                String from = getTextOrDefault(edge, "from", null);
                String to = getTextOrDefault(edge, "to", null);
                if (from != null && to != null) {
                    outgoing.computeIfAbsent(from, k -> new ArrayList<>()).add(to);
                    incoming.computeIfAbsent(to, k -> new ArrayList<>()).add(from);
                }
            }
        }

        // Add interface connections to outgoing edges
        // Interfaces are terminal nodes (receive connections, don't generate any)
        for (Map.Entry<String, List<String>> entry : interfaceLinks.entrySet()) {
            String nodeId = entry.getKey();
            for (String interfaceId : entry.getValue()) {
                outgoing.computeIfAbsent(nodeId, k -> new ArrayList<>()).add(interfaceId);
                incoming.computeIfAbsent(interfaceId, k -> new ArrayList<>()).add(nodeId);
            }
        }

        // Build flow representation
        return buildFlowRepresentation(nodeLabels, nodeTypes, outgoing, incoming);
    }

    /**
     * Extract interfaceIds from a node and add them to the tracking maps.
     * Interfaces are displayed with [UI] prefix as terminal nodes.
     */
    private void extractInterfaceIds(
            JsonNode node,
            String nodeId,
            Map<String, List<String>> interfaceLinks,
            Map<String, String> nodeLabels,
            Map<String, String> nodeTypes) {

        JsonNode interfaceIds = node.path("interfaceIds");
        if (interfaceIds.isArray()) {
            for (JsonNode ifaceId : interfaceIds) {
                String interfaceId = ifaceId.asText();
                if (interfaceId != null && !interfaceId.isBlank()) {
                    // Add interface as a node (if not already added)
                    if (!nodeLabels.containsKey(interfaceId)) {
                        // Use shortened ID for display
                        String shortId = interfaceId.length() > 8
                            ? interfaceId.substring(0, 8) + "..."
                            : interfaceId;
                        nodeLabels.put(interfaceId, "[UI] " + shortId);
                        nodeTypes.put(interfaceId, "interface");
                    }
                    // Link node to interface
                    interfaceLinks.computeIfAbsent(nodeId, k -> new ArrayList<>()).add(interfaceId);
                }
            }
        }

        // Also check for single interfaceId field
        String singleInterfaceId = getTextOrDefault(node, "interfaceId", null);
        if (singleInterfaceId != null && !singleInterfaceId.isBlank()) {
            if (!nodeLabels.containsKey(singleInterfaceId)) {
                String shortId = singleInterfaceId.length() > 8
                    ? singleInterfaceId.substring(0, 8) + "..."
                    : singleInterfaceId;
                nodeLabels.put(singleInterfaceId, "[UI] " + shortId);
                nodeTypes.put(singleInterfaceId, "interface");
            }
            interfaceLinks.computeIfAbsent(nodeId, k -> new ArrayList<>()).add(singleInterfaceId);
        }
    }

    private String buildFlowRepresentation(
            Map<String, String> nodeLabels,
            Map<String, String> nodeTypes,
            Map<String, List<String>> outgoing,
            Map<String, List<String>> incoming) {

        if (nodeLabels.isEmpty()) {
            return "(empty workflow)";
        }

        // Find start nodes (triggers or nodes with no incoming edges)
        List<String> startNodes = new ArrayList<>();
        for (String id : nodeLabels.keySet()) {
            if ("trigger".equals(nodeTypes.get(id)) || !incoming.containsKey(id)) {
                startNodes.add(id);
            }
        }

        if (startNodes.isEmpty() && !nodeLabels.isEmpty()) {
            startNodes.add(nodeLabels.keySet().iterator().next());
        }

        StringBuilder diagram = new StringBuilder();
        Set<String> visited = new HashSet<>();

        for (String startId : startNodes) {
            if (visited.contains(startId)) continue;

            if (diagram.length() > 0) {
                diagram.append("\n");
            }

            // Build path from this start node
            buildPath(startId, nodeLabels, outgoing, incoming, visited, diagram, 0);
        }

        return diagram.length() > 0 ? diagram.toString() : "(no connections)";
    }

    private void buildPath(
            String nodeId,
            Map<String, String> nodeLabels,
            Map<String, List<String>> outgoing,
            Map<String, List<String>> incoming,
            Set<String> visited,
            StringBuilder diagram,
            int depth) {

        if (visited.contains(nodeId) || depth > 20) {
            // Already visited or too deep (avoid infinite loops)
            return;
        }
        visited.add(nodeId);

        String label = nodeLabels.getOrDefault(nodeId, nodeId);
        diagram.append(label);

        List<String> next = outgoing.get(nodeId);
        if (next == null || next.isEmpty()) {
            return;
        }

        if (next.size() == 1) {
            // Simple linear flow
            diagram.append(" → ");
            buildPath(next.get(0), nodeLabels, outgoing, incoming, visited, diagram, depth + 1);
        } else {
            // Fork: multiple outputs
            diagram.append(" →┬→ ");
            boolean first = true;
            for (String nextId : next) {
                if (!first) {
                    diagram.append("\n").append(" ".repeat(Math.max(0, diagram.lastIndexOf("\n") + 1)));
                    diagram.append("   └→ ");
                }
                first = false;

                String nextLabel = nodeLabels.getOrDefault(nextId, nextId);
                diagram.append(nextLabel);

                // Check if this branch continues
                List<String> afterNext = outgoing.get(nextId);
                if (afterNext != null && !afterNext.isEmpty() && !visited.contains(afterNext.get(0))) {
                    diagram.append(" → ...");
                }
                visited.add(nextId);
            }
        }
    }

    private String extractDatasourceId(JsonNode workflow) {
        JsonNode plan = workflow.path("plan");
        JsonNode triggers = plan.path("triggers");

        if (triggers.isArray() && triggers.size() > 0) {
            JsonNode trigger = triggers.get(0);
            if (trigger.has("datasource_id")) {
                return trigger.get("datasource_id").asText();
            }
        }
        return null;
    }

    private String parseLastRunInfo(JsonNode workflow) {
        String status = getTextOrDefault(workflow, "status", null);
        String updatedAt = getTextOrDefault(workflow, "updatedAt", null);

        if (status == null) {
            return "No execution history";
        }

        StringBuilder info = new StringBuilder();
        info.append("Status: ").append(status);

        if (updatedAt != null) {
            info.append(" | Last updated: ").append(updatedAt);
        }

        return info.toString();
    }

    private String getTextOrDefault(JsonNode node, String field, String defaultValue) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return defaultValue;
        }
        return node.get(field).asText(defaultValue);
    }

    /**
     * Parse a JsonNode as a Map<String, Object>.
     * Returns null if the node is missing or not an object.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonNodeAsMap(JsonNode node) {
        if (node == null || node.isMissingNode() || !node.isObject()) {
            return null;
        }
        try {
            return objectMapper.convertValue(node, Map.class);
        } catch (Exception e) {
            log.warn("Failed to parse JsonNode as Map: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Normalize a label to match edge key format.
     * Same logic as LabelNormalizer in orchestrator-service.
     */
    private String normalizeLabel(String label) {
        if (label == null || label.isBlank()) {
            return "unknown";
        }
        // Transliterate accented characters, lowercase, replace non-alphanumeric with underscore
        String normalized = java.text.Normalizer.normalize(label, java.text.Normalizer.Form.NFD)
            .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
            .toLowerCase()
            .replaceAll("[^a-z0-9]+", "_")
            .replaceAll("^_+|_+$", "")  // trim leading/trailing underscores
            .replaceAll("_+", "_");      // collapse multiple underscores
        return normalized.isEmpty() ? "unknown" : normalized;
    }

    private WorkflowContext emptyContext() {
        return new WorkflowContext(null, null, null, null, null, null, false);
    }

    private WorkflowContext simpleContext(String workflowId) {
        return new WorkflowContext(
            workflowId,
            "Workflow " + workflowId.substring(0, Math.min(8, workflowId.length())),
            "UNKNOWN",
            "(unable to load flow)",
            null,
            "Unable to load execution history",
            false
        );
    }

    // ========================================================================
    // WORKFLOW BUILDER SESSION CONTEXT
    // ========================================================================

    /**
     * Context holder for active workflow builder session.
     * Used to inform the agent about existing sessions at conversation start.
     *
     * Now includes the full plan and context (rules, variable_syntax, etc.)
     * to provide rich guidance when resuming a session.
     */
    public record WorkflowBuilderSessionContext(
        boolean hasActiveSession,
        String sessionId,
        String workflowName,
        String workflowDescription,
        String draftId,
        // Full plan (like load returns)
        Map<String, Object> plan,
        // Context (rules, variable_syntax, available_node_types, actions, NEXT)
        Map<String, Object> context
    ) {
        public static WorkflowBuilderSessionContext empty() {
            return new WorkflowBuilderSessionContext(false, null, null, null, null, null, null);
        }

        public boolean hasTrigger() {
            if (plan == null) return false;
            Object triggers = plan.get("triggers");
            if (triggers instanceof List<?> list) {
                return !list.isEmpty();
            }
            return false;
        }

        public int nodeCount() {
            if (plan == null) return 0;
            int count = 0;
            for (String key : List.of("triggers", "mcps", "cores")) {
                Object nodes = plan.get(key);
                if (nodes instanceof List<?> list) {
                    count += list.size();
                }
            }
            return count;
        }
    }

    /**
     * Get active workflow builder session for a tenant and conversation.
     * Called at conversation start to inject session info into agent context.
     *
     * @param tenantId The tenant ID
     * @param conversationId The conversation ID for conversation-scoped lookup
     * @return WorkflowBuilderSessionContext with session info, or empty if no active session
     */
    public WorkflowBuilderSessionContext getActiveWorkflowBuilderSession(String tenantId, String conversationId) {
        if (tenantId == null || tenantId.isBlank()) {
            return WorkflowBuilderSessionContext.empty();
        }

        try {
            // Include conversationId as query param for conversation-scoped lookup
            String url = orchestratorUrl + "/api/workflow-builder/sessions/active";
            if (conversationId != null && !conversationId.isBlank()) {
                url += "?conversationId=" + conversationId;
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-User-ID", tenantId);
            // 2026-05-21 - forward org context to scope the active session lookup.
            OrgContextHeaderForwarder.forward(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
            );

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.debug("No active workflow builder session for tenant: {}", tenantId);
                return WorkflowBuilderSessionContext.empty();
            }

            JsonNode body = objectMapper.readTree(response.getBody());
            boolean hasActiveSession = body.path("hasActiveSession").asBoolean(false);

            if (!hasActiveSession) {
                return WorkflowBuilderSessionContext.empty();
            }

            // Get session info (if single session, use 'session', otherwise first from 'sessions')
            JsonNode sessionNode = body.path("session");
            if (sessionNode.isMissingNode()) {
                JsonNode sessions = body.path("sessions");
                if (sessions.isArray() && sessions.size() > 0) {
                    sessionNode = sessions.get(0);
                } else {
                    return WorkflowBuilderSessionContext.empty();
                }
            }

            String sessionId = getTextOrDefault(sessionNode, "sessionId", null);
            String workflowName = getTextOrDefault(sessionNode, "name", "Untitled");
            String workflowDescription = getTextOrDefault(sessionNode, "description", null);
            String draftId = getTextOrDefault(sessionNode, "draftId", null);

            // Parse plan (full plan like load returns)
            Map<String, Object> plan = parseJsonNodeAsMap(sessionNode.path("plan"));

            // Parse context (rules, variable_syntax, available_node_types, actions, NEXT)
            Map<String, Object> context = parseJsonNodeAsMap(sessionNode.path("context"));

            // Calculate node count for logging
            int nodeCount = 0;
            if (plan != null) {
                for (String key : List.of("triggers", "mcps", "cores")) {
                    Object nodes = plan.get(key);
                    if (nodes instanceof List<?> list) {
                        nodeCount += list.size();
                    }
                }
            }

            log.info("Found active workflow builder session: {} '{}' ({} nodes)",
                sessionId, workflowName, nodeCount);

            return new WorkflowBuilderSessionContext(
                true, sessionId, workflowName, workflowDescription, draftId, plan, context
            );

        } catch (Exception e) {
            log.warn("Error fetching active workflow builder session for tenant {}: {}", tenantId, e.getMessage());
            return WorkflowBuilderSessionContext.empty();
        }
    }

    /**
     * Get just the agent name for a given agentId.
     * Used for dynamic conversation titles (agent conversations).
     *
     * @param agentId The agent ID
     * @param tenantId The tenant ID for authorization
     * @return The agent name, or null if not found
     */
    public String getAgentName(String agentId, String tenantId) {
        if (agentId == null || agentId.isBlank()) {
            return null;
        }

        try {
            String url = agentServiceUrl + "/api/agents/" + agentId;

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (tenantId != null) {
                headers.set("X-User-ID", tenantId);
            }
            // 2026-05-21 - forward org context so agent lookup resolves the
            // caller's workspace (org-shared agents not in personal scope).
            OrgContextHeaderForwarder.forward(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode agent = objectMapper.readTree(response.getBody());
                return getTextOrDefault(agent, "name", null);
            }
        } catch (Exception e) {
            log.debug("Could not fetch agent name for {}: {}", agentId, e.getMessage());
        }

        return null;
    }

    /**
     * Get just the workflow name for a given workflowId.
     * Used for dynamic conversation titles.
     *
     * @param workflowId The workflow ID
     * @param tenantId The tenant ID for authorization
     * @return The workflow name, or null if not found
     */
    public String getWorkflowName(String workflowId, String tenantId) {
        if (workflowId == null || workflowId.isBlank()) {
            return null;
        }

        try {
            String url = orchestratorUrl + "/api/workflows/" + workflowId;

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (tenantId != null) {
                headers.set("X-User-ID", tenantId);
            }
            // 2026-05-21 - forward org context for workflow-name lookup.
            OrgContextHeaderForwarder.forward(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode workflow = objectMapper.readTree(response.getBody());
                return getTextOrDefault(workflow, "name", null);
            }
        } catch (Exception e) {
            log.debug("Could not fetch workflow name for {}: {}", workflowId, e.getMessage());
        }

        return null;
    }
}

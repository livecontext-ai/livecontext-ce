package com.apimarketplace.orchestrator.services.mcp;

import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.registry.AgentToolRegistry;
import com.apimarketplace.agent.registry.ToolCategory;
import com.apimarketplace.agent.registry.ToolSchemaGenerator;
import com.apimarketplace.agent.tools.ToolsProvider;
import com.apimarketplace.agent.tools.ToolsRegistrationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Shared MCP server logic used by both MCP surfaces:
 * the REST-shaped endpoints under /api/mcp ({@code McpServerController}) and the
 * standard Streamable HTTP transport at /mcp ({@code McpStreamableHttpController}).
 * Both surfaces expose the same {@link AgentToolRegistry} tools and the same
 * schema/documentation resources; only the wire framing differs.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpProtocolService {

    private final AgentToolRegistry registry;
    private final ToolsRegistrationService registrationService;
    private final ObjectMapper objectMapper;

    /**
     * Cloud tool aggregation (agent/datasource/interface/catalog siblings). Present
     * only in microservice mode; absent (empty) in the CE monolith, where every
     * provider is already in the local registry. See {@code RemoteToolAggregationConfig}.
     */
    private final ObjectProvider<AggregatedToolCatalog> aggregatedCatalogProvider;
    private final ObjectProvider<RemoteToolGateway> remoteToolGatewayProvider;

    /** Unscoped variant kept for the legacy /api/mcp surface (full access). */
    public boolean hasTool(String toolName) {
        return hasTool(toolName, null);
    }

    /**
     * Whether the tool exists AND is visible to the caller's API-key scopes.
     * A tool outside the scopes is reported exactly like a nonexistent one so
     * that scoped keys cannot enumerate the tool catalog.
     *
     * @param scopes lowercase allowed tool names; {@code null} = full access
     *               (no scoped key), an EMPTY set = access to no tools.
     */
    public boolean hasTool(String toolName, Set<String> scopes) {
        if (!inScope(toolName, scopes)) {
            return false;
        }
        if (registry.hasTool(toolName)) {
            return true;
        }
        AggregatedToolCatalog aggregated = aggregatedCatalogProvider.getIfAvailable();
        return aggregated != null && aggregated.knows(toolName);
    }

    /** Unscoped variant kept for the legacy /api/mcp surface (full access). */
    public List<Map<String, Object>> listTools() {
        return listTools(null);
    }

    /**
     * All available tools in MCP {@code tools/list} format
     * ({@code name} / {@code description} / {@code inputSchema}): the local registry
     * UNION the aggregated sibling-service tools (cloud only). Local wins on a name
     * clash; the merged list is name-sorted for a stable ordering.
     *
     * @param scopes lowercase allowed tool names; when non-null the merged list is
     *               filtered to tools whose name (lowercased) is in the set
     *               ({@code null} = unfiltered, empty set = no tools).
     */
    public List<Map<String, Object>> listTools(Set<String> scopes) {
        List<Map<String, Object>> merged = mergedTools();
        if (scopes == null) {
            return merged;
        }
        return merged.stream()
                .filter(tool -> inScope(String.valueOf(tool.get("name")), scopes))
                .toList();
    }

    private List<Map<String, Object>> mergedTools() {
        List<Map<String, Object>> local = registry.getToolsInMcpFormat();
        AggregatedToolCatalog aggregated = aggregatedCatalogProvider.getIfAvailable();
        if (aggregated == null) {
            return local; // monolith: every provider is already local
        }
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        for (Map<String, Object> tool : local) {
            byName.putIfAbsent(String.valueOf(tool.get("name")), tool);
        }
        for (Map<String, Object> tool : aggregated.mcpTools()) {
            byName.putIfAbsent(String.valueOf(tool.get("name")), tool);
        }
        return byName.values().stream()
                .sorted(Comparator.comparing(t -> String.valueOf(t.get("name"))))
                .toList();
    }

    /**
     * Whether the key may see this tool AT ALL.
     *
     * <p>A scope entry is either a bare tool name ({@code catalog}, the whole tool) or a
     * {@code tool.action} pair ({@code catalog.search}, that action only). Both make the
     * tool visible: a key restricted to {@code catalog.search} must still find
     * {@code catalog} in {@code tools/list}, or the client cannot call the action it was
     * granted. WHICH action it may then run is decided by {@link #actionInScope}.
     */
    private static boolean inScope(String toolName, Set<String> scopes) {
        if (scopes == null) {
            return true;
        }
        if (toolName == null) {
            return false;
        }
        String tool = toolName.toLowerCase(Locale.ROOT);
        if (scopes.contains(tool)) {
            return true;
        }
        String prefix = tool + SCOPE_ACTION_SEPARATOR;
        return scopes.stream().anyMatch(s -> s.startsWith(prefix));
    }

    /** Separator between the tool name and the action in a {@code tool.action} scope. */
    static final char SCOPE_ACTION_SEPARATOR = '.';

    /** The parameter every multi-action MCP tool dispatches on. */
    private static final String ACTION_ARGUMENT = "action";

    /**
     * Whether the key may run THIS action of the tool (LC-054, security audit 2026-08-13).
     *
     * <p>Scopes used to be one entry per top-level tool, and a top-level tool is not a
     * permission: {@code catalog} covers {@code search} and {@code execute}, and
     * {@code execute} spends the owner's stored OAuth credentials against a third party.
     * A key created to let a client SEARCH the catalog could therefore call any API the
     * owner had ever connected, register a new one, or delete one. The vocabulary is now
     * {@code tool.action}, and a bare tool name keeps its old meaning (every action), so
     * existing keys are unaffected.
     *
     * <p>Fail closed on a missing action: when the only grants for a tool are
     * action-scoped, a call that names no action cannot be matched against them, and
     * guessing which action the tool would default to is exactly the assumption this
     * check exists to remove.
     *
     * @return true when the call may proceed
     */
    static boolean actionInScope(String toolName, Map<String, Object> arguments, Set<String> scopes) {
        if (scopes == null) {
            return true; // full-access key or JWT session
        }
        if (toolName == null) {
            return false;
        }
        String tool = toolName.toLowerCase(Locale.ROOT);
        if (scopes.contains(tool)) {
            return true; // whole tool granted
        }
        Set<String> grantedActions = grantedActions(tool, scopes);
        if (grantedActions.isEmpty()) {
            return false; // tool not granted at all - hasTool already refused, defence in depth
        }
        Object action = arguments == null ? null : arguments.get(ACTION_ARGUMENT);
        if (action == null || action.toString().isBlank()) {
            return false;
        }
        return grantedActions.contains(action.toString().trim().toLowerCase(Locale.ROOT));
    }

    /** The actions this key was granted on {@code tool}, empty when none were. */
    private static Set<String> grantedActions(String tool, Set<String> scopes) {
        String prefix = tool + SCOPE_ACTION_SEPARATOR;
        return scopes.stream()
                .filter(s -> s.startsWith(prefix) && s.length() > prefix.length())
                .map(s -> s.substring(prefix.length()))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * The per-tool access modes this key implies, in the shape
     * {@code ToolAccessControl.checkWriteAccess} reads (LC-055, security audit 2026-08-13).
     *
     * <p>The MCP execution context used to be built with an empty credentials map, and an
     * absent access mode means UNRESTRICTED to every tool module, so a scoped key ran with
     * the same authority inside a tool as a full session. The map is now derived from the
     * key's own scopes: a tool whose granted actions are ALL reads runs in {@code read}
     * mode, which is what refuses the write actions a module dispatches internally without
     * surfacing them as an {@code action} argument.
     *
     * <p>Deliberately additive, not a change of the absent-means-allow default: that
     * default is read by ~20 other call sites (the agent loop, every service tool
     * controller, workflow nodes), and flipping it globally would lock out internal callers
     * that legitimately pass no credentials. Here the map is never empty for a scoped key,
     * so the MCP path no longer relies on the default at all.
     *
     * <p>The SAME map travels to an aggregated tool: {@code callTool} hands it to
     * {@link RemoteToolGateway#execute}, which puts it in the execute request body under the
     * key names every {@code /api/agent-tools/execute} receiver reads. So a scoped key is
     * restricted identically whether the tool happens to be hosted in this process or in a
     * sibling. Every category survives that hop: sender and receivers now match on the
     * {@code AccessMode} SUFFIX instead of enumerating categories, so {@code catalogAccessMode}
     * (the one that gates spending the owner's stored OAuth credentials) and any category added
     * later are carried instead of dropped. One gap is left, and it is a MISSING gate rather
     * than a dropped one: the generation surface checks the {@code catalog} category, not the
     * {@code generation} category its own tool name derives here, so a
     * {@code generationAccessMode} is copied but read by nobody. It cannot currently widen a
     * key, because no generation action is classified as a read, so that mode is only ever
     * {@code write}.
     *
     * @param scopes the key's scopes; {@code null} (full access) yields an empty map, which
     *               is exactly the unrestricted meaning a full-access key should have
     */
    static Map<String, Object> restrictionCredentials(Set<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> credentials = new LinkedHashMap<>();
        for (String scope : scopes) {
            int sep = scope.indexOf(SCOPE_ACTION_SEPARATOR);
            String tool = sep < 0 ? scope : scope.substring(0, sep);
            String category = accessControlCategory(tool);
            if (sep < 0) {
                credentials.put(category + "AccessMode", "write"); // whole tool granted
                continue;
            }
            String action = scope.substring(sep + 1);
            boolean isRead = com.apimarketplace.agent.config.ToolAccessControl.isReadAction(category, action);
            // A single write action anywhere in the tool's grants makes the tool writable.
            credentials.merge(category + "AccessMode", isRead ? "read" : "write",
                    (existing, incoming) -> "write".equals(existing) || "write".equals(incoming)
                            ? "write" : "read");
        }
        return credentials;
    }

    /**
     * MCP tool name to {@code ToolAccessControl} category. They match everywhere except
     * the files tool, which is exposed as {@code files} but categorised (and keyed in
     * credentials) in the singular.
     */
    private static String accessControlCategory(String toolName) {
        return "files".equals(toolName) ? "file" : toolName;
    }

    /**
     * Executes a tool and wraps the outcome as an MCP tool result
     * ({@code content} blocks + {@code isError}). Execution failures are reported
     * in-band ({@code isError: true}), matching the MCP convention that tool
     * errors are results, not protocol errors.
     */
    public Map<String, Object> callTool(String toolName,
                                        Map<String, Object> arguments,
                                        String tenantId,
                                        String orgId,
                                        String orgRole) throws JsonProcessingException {
        // Unscoped variant kept for the legacy /api/mcp surface (full access).
        return callTool(toolName, arguments, tenantId, orgId, orgRole, null);
    }

    /**
     * Scope-aware execution ({@code scopes}: lowercase allowed tool names,
     * {@code null} = full access). An out-of-scope tool fails with the SAME
     * "Unknown tool" error as a nonexistent one (defence in depth behind the
     * controller's {@link #hasTool(String, Set)} guard, and anti-enumeration).
     */
    public Map<String, Object> callTool(String toolName,
                                        Map<String, Object> arguments,
                                        String tenantId,
                                        String orgId,
                                        String orgRole,
                                        Set<String> scopes) throws JsonProcessingException {
        ToolsProvider.ToolExecutionResult result;
        if (scopes != null && !hasTool(toolName, scopes)) {
            result = ToolsProvider.ToolExecutionResult.failure(
                    com.apimarketplace.agent.tools.ToolErrorCode.TOOL_NOT_FOUND,
                    "Unknown tool: " + toolName);
        } else if (!actionInScope(toolName, arguments, scopes)) {
            // Distinct from "Unknown tool": the tool IS granted, this action is not, and the
            // agent can act on that - it can retry with an action the key allows, or ask the
            // key's owner for a wider one. Naming the allowed actions is not an enumeration
            // leak: they are this key's own grants.
            result = ToolsProvider.ToolExecutionResult.failure(
                    com.apimarketplace.agent.tools.ToolErrorCode.PERMISSION_DENIED,
                    "This API key is not allowed to run " + toolName + "(action='"
                            + (arguments == null ? null : arguments.get(ACTION_ARGUMENT)) + "'). "
                            + "Allowed on " + toolName + ": "
                            + new java.util.TreeSet<>(grantedActions(
                                    toolName.toLowerCase(Locale.ROOT), scopes))
                            + ". Call one of those, or ask the key's owner to widen its scopes.");
        } else if (registry.hasTool(toolName)) {
            // Local tool: execute in-process (fast path, also the only path in the monolith).
            ToolsProvider.ToolExecutionContext context = new ToolsProvider.ToolExecutionContext(
                    tenantId,
                    // Per-tool access modes derived from the key's scopes (LC-055). Empty for a
                    // full-access key, which is what unrestricted means.
                    restrictionCredentials(scopes),
                    Map.of(),
                    Set.of(),  // No approved services for MCP calls
                    null,      // viewingWorkflowId
                    null,      // viewingWorkflowName
                    orgId,
                    orgRole
            );
            result = registrationService.executeTool(toolName, arguments, context);
        } else {
            // Aggregated sibling tool (cloud only): route to the owning service, carrying the
            // SAME derived access modes the local branch puts in its ToolExecutionContext
            // (LC-055). Without them the sibling rebuilds its credentials map from a request
            // that names no mode, and an absent mode means UNRESTRICTED to every tool module:
            // a scoped key had full write authority inside a tool this process does not host,
            // while the identical key was restricted on a tool it does.
            RemoteToolGateway gateway = remoteToolGatewayProvider.getIfAvailable();
            if (gateway == null) {
                result = ToolsProvider.ToolExecutionResult.failure(
                        com.apimarketplace.agent.tools.ToolErrorCode.TOOL_NOT_FOUND,
                        "Unknown tool: " + toolName);
            } else {
                result = gateway.execute(toolName, arguments, tenantId, orgId, orgRole,
                        restrictionCredentials(scopes));
            }
        }

        if (result.success()) {
            String textContent;
            if (result.data() instanceof Map || result.data() instanceof List) {
                textContent = objectMapper.writeValueAsString(result.data());
            } else {
                textContent = result.data() != null ? result.data().toString() : "";
            }
            return Map.of(
                    "content", List.of(Map.of(
                            "type", "text",
                            "text", textContent
                    )),
                    "isError", false
            );
        }
        return Map.of(
                "content", List.of(Map.of(
                        "type", "text",
                        "text", result.error() != null ? result.error() : "Tool execution failed"
                )),
                "isError", true
        );
    }

    /**
     * Static resource catalog: schemas and the generated tools documentation.
     *
     * <p>Deliberately UNSCOPED: resources (and {@link #getResourceContent}) are
     * readable by any authenticated key, scoped or not. API-key scopes restrict
     * tool visibility/execution only; the schemas and docs carry no per-tenant
     * data and are needed by clients regardless of which tools a key may call.</p>
     */
    public List<Map<String, Object>> listResources() {
        return List.of(
                Map.of(
                        "uri", "schema://workflow",
                        "name", "Workflow Schema",
                        "description", "JSON Schema for workflow plans",
                        "mimeType", "application/json"
                ),
                Map.of(
                        "uri", "schema://agent",
                        "name", "Agent Schema",
                        "description", "JSON Schema for agent configuration",
                        "mimeType", "application/json"
                ),
                Map.of(
                        "uri", "schema://interface",
                        "name", "Interface Schema",
                        "description", "JSON Schema for interfaces (display, interactive apps, multi-page)",
                        "mimeType", "application/json"
                ),
                Map.of(
                        "uri", "schema://datasource",
                        "name", "DataSource Schema",
                        "description", "JSON Schema for data sources",
                        "mimeType", "application/json"
                ),
                Map.of(
                        "uri", "docs://tools",
                        "name", "Tools Documentation",
                        "description", "Full documentation of all available tools",
                        "mimeType", "text/markdown"
                )
        );
    }

    public String resourceMimeType(String uri) {
        return uri.startsWith("schema://") ? "application/json" : "text/markdown";
    }

    /**
     * Resource content by URI, or {@code null} when the URI is unknown.
     */
    public String getResourceContent(String uri) {
        try {
            return switch (uri) {
                case "schema://workflow" -> objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(ToolSchemaGenerator.getWorkflowPlanSchema());
                case "schema://agent" -> objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(ToolSchemaGenerator.getAgentConfigSchema());
                case "schema://interface" -> objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(ToolSchemaGenerator.getInterfaceSchema());
                case "schema://datasource" -> objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(ToolSchemaGenerator.getDataSourceSchema());
                case "docs://tools" -> generateToolsDocumentation();
                default -> null;
            };
        } catch (Exception e) {
            log.error("Error generating resource content for {}: {}", uri, e.getMessage());
            return null;
        }
    }

    private String generateToolsDocumentation() {
        StringBuilder sb = new StringBuilder();
        sb.append("# LiveContext Agent Tools\n\n");
        sb.append("This document lists all available tools for AI agents.\n\n");

        for (ToolCategory category : ToolCategory.values()) {
            List<AgentToolDefinition> tools = registry.getToolsByCategory(category);
            if (tools.isEmpty()) continue;

            sb.append("## ").append(category.getDisplayName()).append("\n\n");
            sb.append(category.getDescription()).append("\n\n");

            for (AgentToolDefinition tool : tools) {
                sb.append("### `").append(tool.name()).append("`\n\n");
                sb.append(tool.description()).append("\n\n");

                if (tool.helpText() != null && !tool.helpText().isBlank()) {
                    sb.append(tool.helpText()).append("\n\n");
                }

                if (!tool.requiredParameters().isEmpty()) {
                    sb.append("**Required Parameters:** ");
                    sb.append(String.join(", ", tool.requiredParameters())).append("\n\n");
                }
            }
        }

        return sb.toString();
    }
}

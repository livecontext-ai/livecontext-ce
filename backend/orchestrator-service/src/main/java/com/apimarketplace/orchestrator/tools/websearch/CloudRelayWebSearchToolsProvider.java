package com.apimarketplace.orchestrator.tools.websearch;

import com.apimarketplace.agent.cloud.CloudLlmRuntimeAccess;
import com.apimarketplace.agent.cloud.CloudLlmRuntimeCredentials;
import com.apimarketplace.agent.domain.ToolParameter;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.registry.ToolCategory;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.apimarketplace.agent.registry.ToolSchemaGenerator.generateInputSchema;
import static com.apimarketplace.agent.registry.ToolSchemaGenerator.intParam;
import static com.apimarketplace.agent.registry.ToolSchemaGenerator.stringParam;

/**
 * CE replacement for {@link WebSearchToolsProvider}: when the local websearch engine is
 * disabled ({@code websearch.enabled=false}, the CE monolith default) but the install is
 * cloud-linked with the CLOUD LLM source, the {@code web_search} tool relays searches to
 * the linked cloud deployment, which executes and bills them on the linked cloud account.
 *
 * <p>Mirrors the CE→cloud LLM relay shape ({@code CloudRelayProvider} /
 * {@code RuntimeLlmProviderResolver}): the link state is checked at RUNTIME per call -
 * never via a static conditional - because the cloud link can be created or revoked while
 * the CE instance is running. Unlinked / BYOK tenants never trigger a relay call.
 *
 * <p>Scope: only {@code search} (and {@code help}) - fetch and browser automation are NOT
 * relayed; they require the websearch-service runtime that CE does not ship.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "websearch.enabled", havingValue = "false")
public class CloudRelayWebSearchToolsProvider implements ToolsProvider {

    static final String TOOL_NAME = "web_search";
    private static final List<String> VALID_ACTIONS = List.of("search", "help");

    static final String UNAVAILABLE_MESSAGE =
            "Web search is not available in this workspace. No other action of this tool can "
            + "enable it - answer from your own knowledge, or tell the user that web search "
            + "requires the installation's cloud link with the Cloud LLM source (only the "
            + "user can set that up).";

    private final CloudLlmRuntimeAccess runtimeAccess;
    private final CloudWebSearchRelayClient relayClient;

    public CloudRelayWebSearchToolsProvider(
            @Autowired(required = false) CloudLlmRuntimeAccess runtimeAccess,
            CloudWebSearchRelayClient relayClient) {
        this.runtimeAccess = runtimeAccess;
        this.relayClient = relayClient;
    }

    @Override
    public ToolCategory getCategory() {
        return ToolCategory.WEB_SEARCH;
    }

    @Override
    public List<AgentToolDefinition> getTools() {
        // No relay wiring (CE without marketplace.mode=remote) → no tool at all,
        // same as the pre-relay behavior.
        if (runtimeAccess == null) {
            return List.of();
        }
        return List.of(buildSearchTool());
    }

    @Override
    public ToolExecutionResult execute(String toolName, Map<String, Object> parameters, ToolExecutionContext context) {
        if (!TOOL_NAME.equals(toolName)) {
            return ToolExecutionResult.failure(ToolErrorCode.TOOL_NOT_FOUND, "Unknown tool: " + toolName);
        }
        String action = parameters != null ? (String) parameters.get("action") : null;
        if (action == null || action.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                    "action is required. Valid actions: " + String.join(", ", VALID_ACTIONS));
        }
        if ("help".equals(action)) {
            return ToolExecutionResult.success(buildHelpPayload());
        }
        if (!"search".equals(action)) {
            return ToolExecutionResult.failure(ToolErrorCode.VALIDATION_ERROR,
                    "Invalid action: " + action + ". Valid actions: " + String.join(", ", VALID_ACTIONS)
                    + ". Only 'search' is available in this workspace - fetch and browser "
                    + "automation are not supported here.");
        }
        return executeSearch(parameters, context);
    }

    private ToolExecutionResult executeSearch(Map<String, Object> parameters, ToolExecutionContext context) {
        String query = (String) parameters.get("query");
        if (query == null || query.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                    "Parameter 'query' is required for action 'search'");
        }

        String tenantId = context != null ? context.tenantId() : null;
        if (!isRelayAvailable(tenantId)) {
            return ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED, UNAVAILABLE_MESSAGE);
        }
        Optional<CloudLlmRuntimeCredentials> credentials = resolveCredentials(tenantId);
        if (credentials.isEmpty()) {
            return ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED, UNAVAILABLE_MESSAGE);
        }

        Integer maxResults = null;
        if (parameters.get("max_results") instanceof Number n) {
            maxResults = n.intValue();
        }
        String timeRange = parameters.get("time_range") instanceof String s ? s : null;
        Map<String, Object> creds = context.credentials() != null ? context.credentials() : Map.of();
        CeWebSearchRelayRequest request = new CeWebSearchRelayRequest(
                query,
                maxResults,
                timeRange,
                asString(creds.get("__streamId__")),
                asString(creds.get("__toolCallId__")));

        try {
            Map<String, Object> response = relayClient.search(credentials.get(), request);
            return ToolExecutionResult.success(response);
        } catch (Exception e) {
            log.warn("CE web search relay failed for tenant {}: {}", tenantId, e.getMessage());
            return ToolExecutionResult.failure(ToolErrorCode.EXTERNAL_SERVICE_ERROR,
                    "Web search failed: " + e.getMessage());
        }
    }

    /**
     * Runtime link check - fail-closed (no relay call) when the tenant is not
     * cloud-selected or the link state cannot be resolved.
     */
    private boolean isRelayAvailable(String tenantId) {
        if (runtimeAccess == null || tenantId == null || tenantId.isBlank()) {
            return false;
        }
        try {
            return runtimeAccess.isCloudSelected(tenantId);
        } catch (RuntimeException e) {
            log.warn("CE web search relay: could not resolve cloud-link state for tenant {}: {}",
                    tenantId, e.getMessage());
            return false;
        }
    }

    private Optional<CloudLlmRuntimeCredentials> resolveCredentials(String tenantId) {
        try {
            return runtimeAccess.resolveCloudRuntime(tenantId);
        } catch (RuntimeException e) {
            log.warn("CE web search relay: could not resolve cloud runtime credentials for tenant {}: {}",
                    tenantId, e.getMessage());
            return Optional.empty();
        }
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private AgentToolDefinition buildSearchTool() {
        var params = List.of(
                ToolParameter.builder()
                        .name("action")
                        .type("string")
                        .description("search | help")
                        .required(true)
                        .enumValues(VALID_ACTIONS)
                        .build(),
                stringParam("query", "Search query (search)", false),
                intParam("max_results", "Max results, default 10, cap 50 (search)", false, 10),
                stringParam("time_range", "'day' | 'week' | 'month' | 'year' (search)", false)
        );

        String description = "Web search.\n"
                + "- search: query the web (~1s). Returns URL/title/snippet.\n"
                + "- help: action reference.\n"
                + "Only 'search' is available in this workspace - there is no fetch or browser "
                + "automation action.";

        return AgentToolDefinition.builder()
                .name(TOOL_NAME)
                .description(description)
                .category(ToolCategory.WEB_SEARCH)
                .parameters(params)
                .requiredParameters(List.of("action"))
                .inputSchema(generateInputSchema(params, List.of("action")))
                .helpText("Call web_search(action='help') for the action reference.")
                .requiresAuth(false)
                .tags(List.of("websearch", "search", "web", "unified"))
                .timeoutMs(60_000L)
                .build();
    }

    private Map<String, Object> buildHelpPayload() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("description",
                "WEB_SEARCH TOOL - multi-engine web search. 'search' is the only data action "
                + "available in this workspace: there is no fetch or browser automation here, "
                + "so use search results' snippets directly and cite the result URLs.");

        Map<String, Object> actions = new LinkedHashMap<>();
        actions.put("search", Map.of(
                "summary", "Multi-engine web search (~1s). First-pass for any factual lookup.",
                "params", Map.of(
                        "query", "required - the search query",
                        "max_results", "optional, default 10, cap 50",
                        "time_range", "optional - 'day' | 'week' | 'month' | 'year'"),
                "returns", "results[]: {url, title, snippet}"
        ));
        actions.put("help", Map.of(
                "summary", "This action reference.",
                "params", Map.of("(none)", "no parameters")
        ));
        out.put("actions", actions);

        Map<String, Object> concepts = new HashMap<>();
        concepts.put("snippets_only",
                "Results carry url/title/snippet only. You cannot open the pages with this tool "
                + "- if a snippet is not enough, refine the query (quotes, site:, time_range) "
                + "instead of trying to fetch the page.");
        out.put("concepts", concepts);
        return out;
    }
}

package com.apimarketplace.orchestrator.tools.websearch;

import com.apimarketplace.agent.cloud.CloudLlmRuntimeAccess;
import com.apimarketplace.agent.cloud.CloudLlmRuntimeCredentials;
import com.apimarketplace.agent.config.ToolAccessControl;
import com.apimarketplace.agent.domain.ToolParameter;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.registry.ToolCategory;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.apimarketplace.agent.registry.ToolSchemaGenerator.arrayParam;
import static com.apimarketplace.agent.registry.ToolSchemaGenerator.generateInputSchema;
import static com.apimarketplace.agent.registry.ToolSchemaGenerator.intParam;
import static com.apimarketplace.agent.registry.ToolSchemaGenerator.objectParam;
import static com.apimarketplace.agent.registry.ToolSchemaGenerator.stringParam;

/**
 * CE replacement for the {@code agent_browse} slice of {@link WebSearchToolsProvider}:
 * when the local browser stack is disabled ({@code websearch.enabled=false}, the CE
 * monolith default) but the install is cloud-linked with the CLOUD LLM source, the
 * {@code agent_browse} tool relays browser-agent sessions (and their session-control
 * calls) to the linked cloud deployment, which owns the whole browser stack, executes
 * and bills them on the linked cloud account, and returns a cloud-hosted CDP live-view
 * URL/token so the CE frontend can watch the session directly.
 *
 * <p>Mirrors {@link CloudRelayWebSearchToolsProvider}: the link state is checked at
 * RUNTIME per call (never via a static conditional) because the cloud link can be
 * created or revoked while the CE instance is running. Unlinked / BYOK tenants never
 * trigger a relay call.
 *
 * <p>The search relay ({@link CloudRelayWebSearchToolsProvider}) keeps owning the
 * {@code web_search} tool; this provider owns a distinct {@code agent_browse} tool, so
 * the two never collide (tools are registered by name).
 *
 * <p><b>LC-029 parity with the cloud edition.</b> Because the capability is registered here
 * under a DIFFERENT tool name, every control keyed on the tool name has to name it too, and
 * none of them did:
 * <ol>
 *   <li>the synchronous user card, raised before execution by {@code ToolAuthorizationGuard}
 *       from {@code ToolAuthorizationPolicy.SENSITIVE_ACTIONS}, which now carries an
 *       {@code "agent_browse"} key;</li>
 *   <li>the agent's read/write mode, checked in {@link #execute} through
 *       {@code ToolAccessControl.checkWriteAccess} under the {@code "agent_browse"}
 *       category;</li>
 *   <li>the restricted-scope destination bound, which refuses a browse outright exactly as
 *       {@link WebSearchToolsProvider} does.</li>
 * </ol>
 * The corresponding controls on {@link CloudRelayWebSearchToolsProvider} would be no-ops and
 * are deliberately absent: that provider exposes only {@code search} and {@code help}, and
 * {@code search} is classified READ under {@code web_search}, so
 * {@code checkWriteAccess} short-circuits before it reads any mode.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "websearch.enabled", havingValue = "false")
public class CloudRelayBrowserAgentToolsProvider implements ToolsProvider {

    static final String TOOL_NAME = "agent_browse";

    private static final String ACTION_AGENT_BROWSE = "agent_browse";
    private static final String ACTION_BROWSE_STATUS = "browse_status";
    private static final String ACTION_BROWSE_INTERVENE = "browse_intervene";
    private static final String ACTION_BROWSE_ABORT = "browse_abort";
    private static final String ACTION_BROWSE_SCREENSHOT = "browse_screenshot";

    private static final List<String> VALID_ACTIONS = List.of(
            ACTION_AGENT_BROWSE, ACTION_BROWSE_STATUS, ACTION_BROWSE_INTERVENE,
            ACTION_BROWSE_ABORT, ACTION_BROWSE_SCREENSHOT, "help");

    /** Optional pass-through params the runner honours (mirrors BrowserAgentModule.buildJobParameters). */
    private static final List<String> OPTION_KEYS = List.of(
            "expected_output_schema", "interaction_mode", "domain_allowlist",
            "domain_denylist", "screenshot_policy", "session");

    static final String UNAVAILABLE_MESSAGE =
            "Browser automation is not available in this workspace. No other action of this "
            + "tool can enable it - it requires the installation's cloud link with the Cloud "
            + "LLM source (only the user can set that up). Fall back to web_search instead, or "
            + "tell the user browser automation needs the cloud link.";

    static final String RESTRICTED_BROWSE_MESSAGE =
            "This run is treated as carrying restricted-scope data, so agent_browse is not "
            + "available: a browser session navigates on its own and its destinations cannot be "
            + "bounded in advance. Use web_search instead, or continue without browsing.";

    private final CloudLlmRuntimeAccess runtimeAccess;
    private final CloudBrowserAgentRelayClient relayClient;

    /**
     * Tenant-level restricted-scope evidence, shared with {@link WebSearchToolsProvider}.
     * Field-injected and optional so a unit-constructed provider has no tenant evidence
     * rather than an invented denial, and so the existing constructor keeps its shape.
     */
    @Autowired(required = false)
    RestrictedScopeTenantDetector restrictedScopeTenantDetector;

    public CloudRelayBrowserAgentToolsProvider(
            @Autowired(required = false) CloudLlmRuntimeAccess runtimeAccess,
            CloudBrowserAgentRelayClient relayClient) {
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
        // same as the pre-relay behaviour.
        if (runtimeAccess == null) {
            return List.of();
        }
        return List.of(buildBrowseTool());
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
        if (!VALID_ACTIONS.contains(action)) {
            return ToolExecutionResult.failure(ToolErrorCode.VALIDATION_ERROR,
                    "Invalid action: " + action + ". Valid actions: " + String.join(", ", VALID_ACTIONS));
        }

        // LC-029, CE edition. The cloud edition gates the identical capability inside
        // WebSearchToolsProvider; this provider is the CE twin and carried none of it, so a
        // prompt-injected agent could drive a browser session against a model-chosen host with
        // no access-mode check at all. Both checks below mirror WebSearchToolsProvider.execute
        // one for one. 'help' is exempt for the same stated reason: it reaches nothing, costs
        // nothing, and an agent that cannot read the contract cannot use the tool correctly.
        // The third layer (the synchronous user card) is not applied here: it is raised BEFORE
        // execution by ToolAuthorizationGuard, which matches this tool through the
        // "agent_browse" entry of ToolAuthorizationPolicy.SENSITIVE_ACTIONS.
        var accessDenied = ToolAccessControl.checkWriteAccess(
                context != null ? context.credentials() : null, TOOL_NAME, action);
        if (accessDenied.isPresent()) {
            return ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED, accessDenied.get());
        }

        // Destination bound for an execution carrying restricted-scope content. A browser
        // session navigates on its own after the first page, so like the cloud twin it is
        // refused outright rather than allow-listed. Both evidence sources are read through
        // the SAME helpers the cloud twin uses, so the two editions cannot answer differently:
        // the explicit execution tag, and the tenant holding a restricted-scope credential
        // (RestrictedScopeTenantDetector). The unbounded channel is refused on either, which
        // is why this provider has no mode branch - there is nothing here to bound.
        if (ACTION_AGENT_BROWSE.equals(action)
                && (WebSearchToolsProvider.isRestrictedExecution(context)
                    || WebSearchToolsProvider.isRestrictedScopeTenant(context, restrictedScopeTenantDetector))) {
            return ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED, RESTRICTED_BROWSE_MESSAGE);
        }

        String tenantId = context != null ? context.tenantId() : null;
        if (!isRelayAvailable(tenantId)) {
            return ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED, UNAVAILABLE_MESSAGE);
        }
        Optional<CloudLlmRuntimeCredentials> credentials = resolveCredentials(tenantId);
        if (credentials.isEmpty()) {
            return ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED, UNAVAILABLE_MESSAGE);
        }

        if (ACTION_AGENT_BROWSE.equals(action)) {
            return executeBrowse(parameters, context, credentials.get());
        }
        return executeControl(action, parameters, credentials.get());
    }

    private ToolExecutionResult executeBrowse(Map<String, Object> parameters, ToolExecutionContext context,
                                              CloudLlmRuntimeCredentials credentials) {
        String task = asString(parameters.get("task"));
        if (task == null || task.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                    "Parameter 'task' is required for action 'agent_browse'");
        }

        Map<String, Object> creds = context != null && context.credentials() != null
                ? context.credentials() : Map.of();
        CeBrowseRelayRequest request = new CeBrowseRelayRequest(
                task,
                asString(parameters.get("start_url")),
                asMap(parameters.get("llm")),
                asInteger(parameters.get("max_steps")),
                collectOptions(parameters),
                asString(creds.get("__streamId__")),
                asString(creds.get("__toolCallId__")));

        try {
            Map<String, Object> response = relayClient.agentBrowse(credentials, request);
            return ToolExecutionResult.success(response);
        } catch (Exception e) {
            log.warn("CE browser agent relay failed: {}", e.getMessage());
            return ToolExecutionResult.failure(ToolErrorCode.EXTERNAL_SERVICE_ERROR,
                    "Browser session failed: " + e.getMessage());
        }
    }

    private ToolExecutionResult executeControl(String action, Map<String, Object> parameters,
                                               CloudLlmRuntimeCredentials credentials) {
        String sessionId = asString(parameters.get("session_id"));
        if (sessionId == null || sessionId.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                    "session_id is required for action '" + action + "'");
        }
        String verb = action.substring("browse_".length()); // status / intervene / abort / screenshot
        CeBrowseControlRequest request = new CeBrowseControlRequest(
                sessionId,
                ACTION_BROWSE_INTERVENE.equals(action) ? asString(parameters.get("hint")) : null);
        try {
            Map<String, Object> response = relayClient.browseControl(credentials, sessionId, verb, request);
            return ToolExecutionResult.success(response);
        } catch (Exception e) {
            log.warn("CE browser agent {} relay failed: {}", action, e.getMessage());
            return ToolExecutionResult.failure(ToolErrorCode.EXTERNAL_SERVICE_ERROR,
                    "Browser session " + verb + " failed: " + e.getMessage());
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
            log.warn("CE browser agent relay: could not resolve cloud-link state for tenant {}: {}",
                    tenantId, e.getMessage());
            return false;
        }
    }

    private Optional<CloudLlmRuntimeCredentials> resolveCredentials(String tenantId) {
        try {
            return runtimeAccess.resolveCloudRuntime(tenantId);
        } catch (RuntimeException e) {
            log.warn("CE browser agent relay: could not resolve cloud runtime credentials for tenant {}: {}",
                    tenantId, e.getMessage());
            return Optional.empty();
        }
    }

    private static Map<String, Object> collectOptions(Map<String, Object> parameters) {
        Map<String, Object> options = new LinkedHashMap<>();
        for (String key : OPTION_KEYS) {
            Object value = parameters.get(key);
            if (value != null) {
                options.put(key, value);
            }
        }
        return options.isEmpty() ? null : options;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    private static Integer asInteger(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private AgentToolDefinition buildBrowseTool() {
        var params = List.of(
                ToolParameter.builder()
                        .name("action")
                        .type("string")
                        .description("agent_browse | browse_status | browse_intervene | browse_abort "
                                + "| browse_screenshot | help")
                        .required(true)
                        .enumValues(VALID_ACTIONS)
                        .build(),
                stringParam("task", "Goal for the browser agent (agent_browse). Be specific.", false),
                stringParam("start_url", "Optional starting URL (agent_browse)", false),
                objectParam("llm", "OPTIONAL {provider, model} (agent_browse). Omit (the whole block or "
                        + "either field) to use the platform default model.", false),
                intParam("max_steps", "Override the runner's default of 50 steps (agent_browse). "
                        + "Hard cap = 50 enforced runner-side.", false, null),
                objectParam("expected_output_schema", "JSON Schema for extracted_data; mismatch → "
                        + "stop_reason=SCHEMA_MISMATCH (agent_browse)", false),
                stringParam("interaction_mode", "'autonomous' (default) | 'supervised' | 'manual' "
                        + "(agent_browse)", false),
                arrayParam("domain_allowlist", "Allowed domains; off-list → DOMAIN_BLOCKED (agent_browse)", false),
                arrayParam("domain_denylist", "Blocked domains (agent_browse)", false),
                stringParam("screenshot_policy", "'every_step' | 'on_change' (default) | 'final_only' "
                        + "| 'off' (agent_browse)", false),
                objectParam("session", "{headless, viewport:{width,height}, timeout_seconds} (agent_browse)", false),
                stringParam("session_id", "Session id from an earlier agent_browse "
                        + "(browse_status/intervene/abort/screenshot)", false),
                stringParam("hint", "Guidance to inject into a running session (browse_intervene)", false)
        );

        String description = "LLM-driven browser automation.\n"
                + "- agent_browse: spawn a browser session to reach targets fetch/search can't (login, "
                + "JS UI, multi-page nav). ~100x slower, burns LLM tokens - use only when needed.\n"
                + "- browse_status/intervene/abort/screenshot: control a running session by session_id.\n"
                + "- help: action reference.\n"
                + "The llm block is OPTIONAL - omit it to use the platform default model.";

        return AgentToolDefinition.builder()
                .name(TOOL_NAME)
                .description(description)
                .category(ToolCategory.WEB_SEARCH)
                .parameters(params)
                .requiredParameters(List.of("action"))
                .inputSchema(generateInputSchema(params, List.of("action")))
                .helpText("Call agent_browse(action='help') for the action reference.")
                .requiresAuth(false)
                .tags(List.of("browser", "agent_browse", "web", "unified"))
                // 640 s ceiling: matches the local WebSearchToolsProvider agent_browse
                // tool timeout - a browser session routinely needs 200-400 s.
                .timeoutMs(640_000L)
                .build();
    }

    private Map<String, Object> buildHelpPayload() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("description",
                "AGENT_BROWSE TOOL - drive an LLM-powered browser. Reach for it ONLY when web_search "
                + "cannot answer (auth wall, JS-rendered DOM, multi-step interactive flow): it is ~100x "
                + "more expensive than a search and pins a browser session. The llm block is OPTIONAL - "
                + "omit it to use the platform default model.");

        Map<String, Object> actions = new LinkedHashMap<>();
        actions.put("agent_browse", Map.of(
                "summary", "Spawn an LLM-driven browser session and await its final result. "
                        + "Pauses for the user to approve the call in the general chat the user "
                        + "is watching (not when you run as a configured agent, inside a workflow "
                        + "run, as a scheduled task or as a sub-agent), and is unavailable "
                        + "(PERMISSION_DENIED) whenever the workspace can reach restricted-scope "
                        + "data such as a connected Gmail account.",
                "params", Map.of(
                        "task", "required - natural-language goal. Be specific.",
                        "start_url", "optional - if omitted, the agent picks one from the task",
                        "llm", "OPTIONAL - {provider, model}. Omit to use the platform default model.",
                        "max_steps", "optional int - overrides the runner's default of 50 (hard cap 50)",
                        "expected_output_schema", "optional JSON Schema; mismatch → SCHEMA_MISMATCH",
                        "interaction_mode", "'autonomous' (default) | 'supervised' | 'manual'",
                        "screenshot_policy", "'every_step' | 'on_change' (default) | 'final_only' | 'off'"),
                "returns", "{final_result, extracted_data, stop_reason, session_id, cdp_ws_url, cdp_token, "
                        + "steps[], cost}"
        ));
        actions.put("browse_status", Map.of(
                "summary", "Inspect a running session by session_id (returned by agent_browse).",
                "params", Map.of("session_id", "required")));
        actions.put("browse_intervene", Map.of(
                "summary", "Inject guidance into a running session (interaction_mode='supervised' or 'manual').",
                "params", Map.of(
                        "session_id", "required",
                        "hint", "required - free-form text the agent reads on its next step")));
        actions.put("browse_abort", Map.of(
                "summary", "Cancel a running session. It terminates with stop_reason=CANCELLED.",
                "params", Map.of("session_id", "required")));
        actions.put("browse_screenshot", Map.of(
                "summary", "Force an immediate screenshot of the running session.",
                "params", Map.of("session_id", "required")));
        actions.put("help", Map.of(
                "summary", "This action reference.",
                "params", Map.of("(none)", "no parameters")));
        out.put("actions", actions);

        Map<String, Object> concepts = new LinkedHashMap<>();
        concepts.put("cost_asymmetry",
                "agent_browse multiplies cost by ~100x because every step issues an LLM call AND uses "
                + "Chromium. Exhaust web_search first.");
        concepts.put("live_view",
                "A successful agent_browse returns cdp_ws_url + cdp_token for the live browser view. "
                + "They are cloud-hosted - the frontend connects directly to that URL.");
        concepts.put("approval_and_destinations",
                "agent_browse drives a browser against a host you chose, so in the general chat "
                + "the user is watching it pauses for the user to approve the call before it "
                + "runs. That pause does NOT happen when you run as a configured agent, inside a "
                + "workflow run, as a scheduled task or as a sub-agent: nobody is there to "
                + "approve, so the call proceeds and the responsibility for the destination is "
                + "yours. Approve requests are shown to the user, not to you: expect the call to "
                + "take longer, and do not retry it in a loop. The browse_* session controls "
                + "never pause - they address a session that was already approved. Separately, "
                + "if your run is configured read-only for this tool, or the workspace can reach "
                + "restricted-scope data (for example a connected Gmail account), agent_browse "
                + "refuses with PERMISSION_DENIED. The second case applies to the whole "
                + "workspace, not only to a turn that read a mailbox, so expect it on the first "
                + "call. Treat that refusal as final: report it and answer from web_search "
                + "results instead of retrying.");
        out.put("concepts", concepts);
        return out;
    }
}

package com.apimarketplace.orchestrator.tools.application;

import com.apimarketplace.agent.domain.ToolParameter;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.registry.ToolCategory;
import com.apimarketplace.agent.tools.ToolsProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

import static com.apimarketplace.agent.registry.ToolSchemaGenerator.*;
import com.apimarketplace.agent.tools.ToolErrorCode;

/**
 * Provider for unified application tool (facade pattern).
 * Exposes ONE tool "application" with action parameter for all operations.
 *
 * Delegates to specialized modules:
 * - ApplicationCrudModule: create, search, my, get, acquire, visualize, runs, get_run, get_node_output
 * - ApplicationExecuteModule: execute, stop_run
 * - ApplicationHelpModule: help
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApplicationToolsProvider implements ToolsProvider {

    private final ApplicationCrudModule crudModule;
    private final ApplicationExecuteModule executeModule;
    private final ApplicationHelpModule helpModule;

    private static final List<String> VALID_ACTIONS = List.of(
        "create", "search", "my", "get", "acquire", "uninstall", "execute", "stop_run",
        "runs", "get_run", "get_node_output",
        "visualize", "help"
    );

    @Override
    public ToolCategory getCategory() {
        return ToolCategory.APPLICATION;
    }

    @Override
    public List<AgentToolDefinition> getTools() {
        return List.of(buildUnifiedApplicationTool());
    }

    @Override
    public ToolExecutionResult execute(String toolName, Map<String, Object> parameters, ToolExecutionContext context) {
        if (!"application".equals(toolName)) {
            return ToolExecutionResult.failure(ToolErrorCode.TOOL_NOT_FOUND, "Unknown tool: " + toolName);
        }

        String action = (String) parameters.get("action");
        if (action == null || action.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "action is required. Valid actions: " + String.join(", ", VALID_ACTIONS));
        }

        try {
            String tenantId = context.tenantId();
            if (tenantId == null && !"help".equals(action)) {
                return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "tenantId is required");
            }

            if (crudModule.canHandle(action)) {
                return crudModule.execute(action, parameters, tenantId, context)
                    .orElse(ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Module failed for action: " + action));
            }

            if (executeModule.canHandle(action)) {
                return executeModule.execute(action, parameters, tenantId, context)
                    .orElse(ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Execute module failed for action: " + action));
            }

            if (helpModule.canHandle(action)) {
                return helpModule.execute(action, parameters, tenantId, context)
                    .orElse(ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Help module failed"));
            }

            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Invalid action: " + action + ". Valid actions: " + String.join(", ", VALID_ACTIONS));

        } catch (Exception e) {
            log.error("Error executing application action {}: {}", action, e.getMessage(), e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Error: " + e.getMessage());
        }
    }

    private AgentToolDefinition buildUnifiedApplicationTool() {
        List<ToolParameter> params = List.of(
            ToolParameter.builder()
                .name("action")
                .type("string")
                .description("Action to perform: " + String.join(", ", VALID_ACTIONS))
                .required(true)
                .enumValues(VALID_ACTIONS)
                .build(),
            stringParam("workflow_id", "Workflow ID - UUID (for: create). Auto-detected from context if viewing a workflow.", false),
            stringParam("application_id", "Application ID - UUID (for: get, acquire, execute, runs, visualize)", false),
            stringParam("query", "Filter by title/name or description (for: search, my). Case-insensitive substring; for 'my' it is applied before pagination.", false),
            arrayParam("node_types", "Keep only applications that CONTAIN one of these node types (for: my). "
                + "Applied before pagination. Several values mean ANY of them, not all, and at most 50 are read (extra values are ignored). "
                + "Tokens: 'mcp:<integration>' (mcp:gmail), 'core:<type>' (core:loop, core:code), 'agent:<type>' (agent:agent), "
                + "'trigger:<type>' (trigger:webhook), 'table:<type>' (table:find), and 'interface'. "
                + "Run 'my' once without it to read the node_types each application reports, rather than guessing a token.", false),
            stringParam("category", "Category slug to filter (for: search)", false),
            boolParam("studio", "(for: create) Put the app on the Studio shelf - the surface for apps whose point is to produce an image, a video or a sound and show it. This is a SECOND axis, not a category: the app keeps whatever category it has. Set it true only when the app's own output is the media; an app that merely embeds a player is not one. Omit to leave it alone: on a re-publish that keeps the existing shelf placement, and a new app defaults to off. Returned as 'studio' by get/search/my when the app is on the shelf, absent when it is not.", false, null),
            stringParam("title", "Title override (for: create, visualize)", false),
            stringParam("description", "Description override (for: create)", false),
            objectParam("data_inputs", "Input data for execution (for: execute). Format depends on trigger type.", false),
            stringParam("trigger_id", "Trigger ID to fire (for: execute). Optional - defaults to first fireable trigger.", false),
            stringParam("run_id", "Run ID (for: get_run, get_node_output, stop_run; and create to pin the showcase run). Returned by execute. On create, omit to auto-pick the latest successful automatic run (COMPLETED/PARTIAL_SUCCESS/WAITING_TRIGGER). On stop_run, omit ONLY when you are an agent running inside a workflow and want to stop your own run.", false),
            stringParam("reason", "(for: stop_run) Why you are stopping the run, in one sentence. Recorded on the run and returned by get_run as stop_reason, so the user and any agent reading the run later see the cause instead of a bare CANCELLED.", false),
            stringParam("mode", "(for: stop_run) 'cancel' (default) ends the run for good AND suspends the schedules of the app's workflow, so a scheduled app stops firing until it is reactivated. 'graceful' only closes the epoch that is running and returns the run to WAITING_TRIGGER, leaving the schedules alone: prefer it when you only want to end THIS execution. 'graceful' is refused when the run is PENDING or WAITING_TRIGGER (nothing is executing yet).", false),
            intParam("epoch", "Epoch number (for: get_run detail, get_node_output; and create to pin the showcase epoch). On create, omit to leave unpinned - the app then renders the latest epoch.", false, null),
            stringParam("node_id", "Node ID (for: get_node_output). From get_run epoch detail.", false),
            intParam("item_index", "Split item index (for: get_node_output). Optional targeting filter.", false, null),
            intParam("iteration", "Loop iteration (for: get_node_output). Optional targeting filter.", false, null),
            intParam("spawn", "Spawn index (for: get_node_output). Optional targeting filter.", false, null),
            stringParam("field", "(for: get_node_output) Name (dot-path, e.g. 'output.image') of a large TEXT output field to read in full, paging past the 128 KB preview. Follow the NEXT pointer on a truncated field.", false),
            intParam("max_bytes", "(for: get_node_output field-expand) Text window size, default & cap 128 KB.", false, null),
            intParam("limit", "Max results to return. search: default 10, max 25. my: default 25, max 50.", false, 10),
            intParam("offset", "Pagination offset (for: search, my, runs); also the byte offset to expand a field from (for: get_node_output with field=)", false, 0),
            arrayParam("topics", "Optional help filter (for: help): any of 'actions', 'parameters', 'response_fields', 'examples', 'tips', 'troubleshooting', 'response_glossary', 'related_tools'. Omit for the full reference.", false)
        );

        return AgentToolDefinition.builder()
            .name("application")
            .description("""
                Create private apps from workflows, browse/acquire/execute marketplace apps, inspect runs.
                NOT external APIs (those use catalog(action='search')) - NOT the user's own workflows (those use workflow(action='list')).
                USE APPLICATION WHEN THE USER SAYS: "application", "app", "marketplace", "store", "publish", "create app", "run app"

                ACTIONS:
                - create: Publish a workflow as a PRIVATE app (workflow_id required or auto-detected). Requires the workflow to have an interface AND at least one successful automatic run to showcase (COMPLETED, PARTIAL_SUCCESS, or - for reusable triggers like webhook/manual/chat/schedule - WAITING_TRIGGER); run it first via workflow(action='execute') if none. Optional run_id + epoch pin the showcase; omit both to auto-pick the latest.
                - search: Browse / search the marketplace. my: apps in your workspace. Both return SLIM items (default_trigger_id, trigger_types, owned_by_me - NO data_inputs_schema).
                - get: Full details for one app - data_inputs_schema + fireable_triggers[] (application_id required)
                - acquire: Clone someone else's app as your own workflow (application_id required)
                - uninstall: Remove an app you acquired - deletes the local clone + its runs; the marketplace listing stays and you can acquire it again (application_id required)
                - execute: Run an app (application_id required, optional data_inputs/trigger_id)
                - stop_run: End a run that is still going (run_id + optional reason + mode; an agent running inside a workflow can omit run_id to stop its own run). Use it when the execution went wrong instead of letting it finish: it cancels the nodes still in flight, including any browser-agent session. Same action, same parameters on workflow(action='stop_run').
                - runs: List execution history (application_id required)
                - get_run: Inspect a run - macro overview, or epoch detail with epoch=N (run_id required)
                - get_node_output: Full output/error for one node (run_id + epoch + node_id required). A TEXT field >128 KB returns a truncated preview + NEXT pointer; follow it (field=<dot-path> + offset) to page the full value.
                - visualize: Show an app preview card in chat (application_id required)
                - help: Full reference; filter with topics=['actions','parameters','examples',...]

                RULES:
                - owned_by_me=true -> execute directly (acquire is REJECTED). owned_by_me=false -> acquire first, then execute with the SAME application_id (the acquired app shows owned_by_me=true from then on).
                - Before execute with data_inputs: call get and use its data_inputs_schema field names VERBATIM - never guess them.
                - Run inspection flow: execute -> get_run (macro) -> get_run (epoch=N) -> get_node_output (zoom).
                """)
            .category(ToolCategory.APPLICATION)
            .parameters(params)
            .requiredParameters(List.of("action"))
            .inputSchema(generateInputSchema(params, List.of("action")))
            // No helpText block - the `description` above already covers every
            // action with the up-to-date owned_by_me / data_inputs_schema framing.
            // A duplicate would just drift (the 8.6-audit miss was exactly that -
            // helpText lacked the owned_by_me branch the description had).
            // Call application(action='help') for the full reference.
            .requiresAuth(true)
            .tags(List.of("application", "marketplace", "unified"))
            .build();
    }
}

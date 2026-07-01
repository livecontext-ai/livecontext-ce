package com.apimarketplace.interfaces.tools;

import com.apimarketplace.agent.domain.ToolParameter;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.registry.ToolCategory;
import com.apimarketplace.agent.tools.ToolsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

import static com.apimarketplace.agent.registry.ToolSchemaGenerator.*;
import com.apimarketplace.agent.tools.ToolErrorCode;

/**
 * Provider for unified interface tool (facade pattern).
 * Interface-service native version - delegates to modules that use services directly (no HTTP hop).
 * Exposes ONE tool "interface" with action parameter for all operations.
 */
@Component
public class InterfaceToolsProvider implements ToolsProvider {

    private static final Logger log = LoggerFactory.getLogger(InterfaceToolsProvider.class);

    private final InterfaceCrudModule crudModule;
    private final InterfaceHelpModule helpModule;
    private final InterfacePublishModule publishModule;

    public InterfaceToolsProvider(InterfaceCrudModule crudModule,
                                   InterfaceHelpModule helpModule,
                                   InterfacePublishModule publishModule) {
        this.crudModule = crudModule;
        this.helpModule = helpModule;
        this.publishModule = publishModule;
    }

    private static final List<String> VALID_ACTIONS = List.of(
        "create", "get", "list", "update", "patch", "delete",
        // Marketplace publication lifecycle
        "publish", "unpublish",
        "help"
    );

    @Override
    public ToolCategory getCategory() {
        return ToolCategory.INTERFACE;
    }

    @Override
    public List<AgentToolDefinition> getTools() {
        return List.of(buildUnifiedInterfaceTool());
    }

    @Override
    public ToolExecutionResult execute(String toolName, Map<String, Object> parameters, ToolExecutionContext context) {
        if (!"interface".equals(toolName)) {
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
                    .orElse(ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "CRUD module failed for action: " + action));
            }

            if (helpModule.canHandle(action)) {
                return helpModule.execute(action, parameters, tenantId, context)
                    .orElse(ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Help module failed"));
            }

            if (publishModule.canHandle(action)) {
                return publishModule.execute(action, parameters, tenantId, context)
                    .orElse(ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Publish module failed for action: " + action));
            }

            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Invalid action: " + action + ". Valid actions: " + String.join(", ", VALID_ACTIONS));

        } catch (Exception e) {
            log.error("Error executing interface action {}: {}", action, e.getMessage(), e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Error: " + e.getMessage());
        }
    }

    private AgentToolDefinition buildUnifiedInterfaceTool() {
        List<ToolParameter> params = List.of(
            ToolParameter.builder()
                .name("action")
                .type("string")
                .description("Action to perform: create, get, list, update, patch, delete, publish, unpublish, help")
                .required(true)
                .enumValues(VALID_ACTIONS)
                .build(),
            stringParam("interface_id", "Interface ID - UUID (for: get, update, patch, delete)", false),
            stringParam("name", "Interface name (for: create, update)", false),
            stringParam("description", "Description (for: create, update)", false),
            stringParam("html_template", "HTML content - REQUIRED for create. Only {{name|default}} placeholders are resolved - any other template syntax renders as raw text; put logic in js_template. Names are generic (e.g. {{title|Untitled}}, {{items|No data}}). In a workflow, the interface node's variable_mapping maps these to real data (e.g. variable_mapping:{title:'{{agent:x.output.response}}'}). MUST include: <meta name=\"viewport\" content=\"width=1280\">", false),
            stringParam("css_template", "CSS stylesheet - REQUIRED for create. Injected as <style> inside iframe. MUST set body background-color and color (no inherited theme). Light: background-color:#ffffff;color:#111827. Dark: background-color:#171614;color:#edecea. Use CSS classes, not inline styles.", false),
            stringParam("js_template", "JavaScript - REQUIRED for create (empty string '' if none needed). Runs inside iframe after HTML renders. Use for: iterating arrays, conditional display, charts, DOM manipulation. Access resolved data via window.__RESOLVED_DATA__ object. Action buttons (forms/clicks bound to triggers) are handled by auto-injected bridge script - js_template is for CUSTOM logic only.", false),
            intParam("limit", "Max results to return (for: list)", false, 25),
            intParam("offset", "Pagination offset (for: list)", false, 0),

            // ==================== Patch (surgical search/replace edits) ====================
            // Avoids re-sending a whole template to change a few lines. Edits one template
            // column at a time; the agent must copy the 'old' text verbatim from the
            // current content (interface(action='get')).
            enumParam("target", "Which template to patch (for: patch): 'html', 'css', or 'js'. One target per call - " +
                "to edit two templates, call patch twice.", false,
                List.of("html", "css", "js")),
            arrayParam("edits", "Search/replace edits (for: patch). A non-empty list of objects " +
                "[{\"old\":\"<exact current text>\", \"new\":\"<replacement>\"}]. 'old' must match the CURRENT " +
                "content EXACTLY (copy it verbatim - indentation and whitespace included), and by default must be " +
                "UNIQUE; if it appears multiple times, add surrounding context or set replace_all=true. 'new' may be " +
                "an empty string to delete. Edits apply in order, all-or-nothing - if any 'old' is not found, NOTHING " +
                "is written. Tip: call interface(action='get') first to copy exact text.", false),
            boolParam("replace_all", "For patch: if true, replace EVERY occurrence of each edit's 'old' " +
                "(default false = each 'old' must match exactly once).", false, false),

            // ==================== Marketplace publication (publish, unpublish) ====================
            // For INTERFACE publications, the resource itself IS the landing page -
            // pass interface_id (the interface to publish) and title only. No separate landing.
            stringParam("title", "Marketplace listing title - REQUIRED for publish", false),
            enumParam("visibility", "Marketplace visibility: 'PRIVATE' (default), 'PUBLIC', 'UNLISTED' (for: publish)", false,
                List.of("PRIVATE", "PUBLIC", "UNLISTED")),
            intParam("credits_per_use", "Credits charged to acquirers per use. Default 0 (free). (for: publish)", false, 0)
        );

        return AgentToolDefinition.builder()
            .name("interface")
            .description("""
                Create and manage HTML page templates. Works STANDALONE (static pages) or IN WORKFLOWS (dynamic data display + user interaction).
                IMPORTANT: action_mapping and variable_mapping are NOT interface params - they go on the WORKFLOW node: workflow(action='add_node', type='interface', params={interface_id:'...', variable_mapping:{...}, action_mapping:{...}}).
                Native output: the WORKFLOW node can render the page to a screenshot (PNG) or pdf FileRef output - set generateScreenshot / generatePdf on the node (no external tool/API needed).
                MANDATORY: Call interface(action='help') before creating your first interface.
                Editing: 'update' REPLACES a whole template; 'patch' does surgical search/replace edits (target + edits=[{old,new}]) - prefer patch to change a few lines without re-sending everything.
                Marketplace: publish requires interface_id + title (the interface itself IS the landing page - no separate landing). unpublish marks the listing inactive - acquirers keep their copies.
                Actions: create, get, list, update, patch, delete, publish, unpublish, help
                """)
            .category(ToolCategory.INTERFACE)
            .parameters(params)
            .requiredParameters(List.of("action"))
            .inputSchema(generateInputSchema(params, List.of("action")))
            .helpText("Call interface(action='help') for full documentation.")
            .requiresAuth(true)
            .tags(List.of("interface", "crud", "unified"))
            .build();
    }
}

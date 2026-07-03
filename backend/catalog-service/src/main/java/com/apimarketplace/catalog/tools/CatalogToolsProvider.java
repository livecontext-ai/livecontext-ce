package com.apimarketplace.catalog.tools;

import com.apimarketplace.agent.domain.ToolParameter;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.registry.ToolCategory;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.apimarketplace.agent.registry.ToolSchemaGenerator.*;

/**
 * Unified facade provider for catalog tools (tool discovery, execution, response schemas,
 * custom API registration).
 * Exposes a single "catalog" tool with an action parameter.
 *
 * Actions:
 * - search: find external API tools
 * - execute (+ "call" alias): execute an API tool
 * - response_schema: get tool response structure
 * - help: JIT documentation (optional topic: register, schema)
 * - register_api: register a custom API with endpoints
 * - update_api: update an existing custom API
 * - delete_api: delete a custom API
 * - list_custom_apis: list user's custom APIs
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CatalogToolsProvider implements ToolsProvider {

    private final CatalogSearchModule searchModule;
    private final CatalogExecuteModule executeModule;
    private final CatalogSchemaModule schemaModule;
    private final CatalogRegisterModule registerModule;
    private final CatalogHelpModule helpModule;

    private static final Set<String> VALID_ACTIONS = Set.of(
        "search", "execute", "call", "response_schema", "help",
        "register_api", "update_api", "delete_api", "list_custom_apis"
    );

    @Override
    public ToolCategory getCategory() {
        return ToolCategory.CATALOG;
    }

    @Override
    public List<AgentToolDefinition> getTools() {
        return List.of(buildUnifiedCatalogTool());
    }

    @Override
    public ToolExecutionResult execute(String toolName, Map<String, Object> parameters, ToolExecutionContext context) {
        if (!"catalog".equals(toolName)) {
            return ToolExecutionResult.failure(ToolErrorCode.TOOL_NOT_FOUND, "Unknown tool: " + toolName);
        }

        String action = (String) parameters.get("action");
        if (action == null || action.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                "Missing required parameter 'action'. Valid actions: search, execute, response_schema, help, register_api, update_api, delete_api, list_custom_apis");
        }

        if (!VALID_ACTIONS.contains(action)) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE,
                "Unknown action: " + action + ". Valid actions: search, execute, response_schema, help, register_api, update_api, delete_api, list_custom_apis");
        }

        try {
            String tenantId = context != null ? context.tenantId() : null;

            if (searchModule.canHandle(action)) {
                return searchModule.execute(action, parameters, tenantId, context)
                    .orElse(ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Search module failed"));
            }

            if (executeModule.canHandle(action)) {
                return executeModule.execute(action, parameters, tenantId, context)
                    .orElse(ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Execute module failed"));
            }

            if (schemaModule.canHandle(action)) {
                return schemaModule.execute(action, parameters, tenantId, context)
                    .orElse(ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Schema module failed"));
            }

            if (helpModule.canHandle(action)) {
                return helpModule.execute(action, parameters, tenantId, context)
                    .orElse(ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Help module failed"));
            }

            if (registerModule.canHandle(action)) {
                return registerModule.execute(action, parameters, tenantId, context)
                    .orElse(ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Register module failed"));
            }

            return ToolExecutionResult.failure(ToolErrorCode.TOOL_NOT_FOUND, "Unknown action: " + action);

        } catch (Exception e) {
            log.error("Error executing catalog action {}: {}", action, e.getMessage(), e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Catalog action failed");
        }
    }

    private AgentToolDefinition buildUnifiedCatalogTool() {
        List<ToolParameter> params = List.of(
            ToolParameter.builder()
                .name("action")
                .description("Action to perform: search, execute, response_schema, help, register_api, update_api, delete_api, list_custom_apis")
                .type("string")
                .required(true)
                .enumValues(List.of("search", "execute", "response_schema", "help",
                    "register_api", "update_api", "delete_api", "list_custom_apis"))
                .build(),
            stringParam("query", "Search keywords (for action='search'). E.g., 'send slack message'. API-scoped shorthand also works: '[gmail, slack] send message' or 'gmail, list messages'.", false),
            stringParam("api", "Optional API filter for action='search'. One API name, slug, iconSlug, or provider, e.g. 'gmail'.", false),
            arrayParam("apis", "Optional API filters for action='search'. Example: ['gmail','slack'].", false),
            intParam("limit", "Maximum results to return (for action='search', default: 10, max: 25)", false, 10),
            stringParam("tool_id", "Tool UUID (for action='execute' or 'response_schema')", false),
            ToolParameter.builder()
                .name("params")
                .description("Input parameters for the tool as a JSON object (for action='execute')")
                .type("object")
                .required(false)
                .build(),
            ToolParameter.builder()
                .name("expand")
                .description("List of response field paths to keep un-clipped (for action='execute'). Use [] for any array index, e.g. expand=['items[].about']. Call catalog(action='help', topics=['shaping']) for the full response-shaping reference.")
                .type("array")
                .required(false)
                .build(),
            ToolParameter.builder()
                .name("max_items")
                .description("Cap every top-level array at N items (for action='execute'). Extras returned as a digest with pagination hint. Call catalog(action='help', topics=['shaping']) for details.")
                .type("integer")
                .required(false)
                .build(),
            ToolParameter.builder()
                .name("api_definition")
                .description("""
                    API definition object (for action='register_api', 'update_api').
                    Required: apiName, baseUrl, endpoints (with name, endpoint, method, description, params, outputSchema).
                    outputSchema is REQUIRED on every endpoint: [{key, type, description, children?}] - defines the typed response shape.
                    Call catalog(action='help', topics=['register']) for full field reference, examples, and advanced features.""")
                .type("object")
                .required(false)
                .build(),
            stringParam("api_id", "API UUID (for action='update_api', 'delete_api')", false),
            arrayParam("topics", "Help topics (for action='help'): 'register' (API registration fields), 'schema' (response schema & SpEL), 'shaping' (response shaping - expand, max_items, digest, nextAction), 'file_storage' (how file outputs are persisted & rendered). Omit for general overview. Example: ['shaping']", false)
        );

        return AgentToolDefinition.builder()
            .name("catalog")
            .description("""
                Discover, execute, and register external API tools (Gmail, Slack, ...).

                Actions:
                - search: find API tools by keyword. catalog(action='search', query='gmail send email').
                  Scope to known API(s) with api='gmail' or apis=['gmail','slack'].
                - response_schema: full contract of one tool. catalog(action='response_schema', tool_id='<uuid>').
                  Returns the input contract (param names, types, required, defaults; closed enums in
                  inputSchema[*].allowedValues - you must pick ONE of those), the output skeleton
                  (paths + SpEL examples) for mapping into a next step, and `credential`
                  (type: api_key | oauth2 | bearer_token | basic_auth | none; requiredScopes) = what
                  credential(action='require') would ask the user to connect ('none' = no credential, just execute).
                - execute: run a tool with the user's credentials. catalog(action='execute', tool_id='<uuid>', params={...}).
                - register_api / update_api / delete_api / list_custom_apis: manage your own custom APIs.
                  register_api needs api_definition (apiName, baseUrl, endpoints with outputSchema each);
                  call catalog(action='help', topics=['register']) FIRST for the field reference.
                - help: catalog(action='help') overview, or topics=['register'|'schema'|'shaping'|'file_storage'].

                FLOW for a tool you don't already know: search -> response_schema -> execute.
                Never skip response_schema: it tells you which values each param accepts, so execute()
                doesn't fail on a bad enum.

                NOT FOR INTERNAL RESOURCES: tables -> table(action='...'), interfaces -> interface(action='...').
                """)
            .category(ToolCategory.CATALOG)
            .parameters(params)
            .requiredParameters(List.of("action"))
            .inputSchema(generateInputSchema(params, List.of("action")))
            .helpText("""
                Catalog tool for discovering, executing, and registering API tools.

                Search: catalog(action='search', query='slack post message')
                Scoped search: catalog(action='search', api='slack', query='post message') or catalog(action='search', query='[gmail, slack] send message')
                Execute: catalog(action='execute', tool_id='<uuid>', params={...})
                Schema: catalog(action='response_schema', tool_id='<uuid>')  # returns inputSchema (param contract: types, defaults, allowedValues) + output skeleton + SpEL examples
                Help: catalog(action='help') or catalog(action='help', topics=['register'|'schema'])
                Register: catalog(action='register_api', api_definition={...})
                Update: catalog(action='update_api', api_id='<uuid>', api_definition={...})
                Delete: catalog(action='delete_api', api_id='<uuid>')
                List: catalog(action='list_custom_apis')

                Search in English for best results.
                """)
            .requiresAuth(false)
            .tags(List.of("catalog", "search", "execute", "api", "discovery"))
            .build();
    }
}

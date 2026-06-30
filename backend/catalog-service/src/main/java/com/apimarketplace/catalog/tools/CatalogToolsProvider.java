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
            arrayParam("topics", "Help topics (for action='help'): 'register' (API registration fields), 'schema' (response schema & SpEL), 'shaping' (response shaping - expand, max_items, digest, nextAction). Omit for general overview. Example: ['shaping']", false)
        );

        return AgentToolDefinition.builder()
            .name("catalog")
            .description("""
                Unified catalog tool for discovering, executing, and registering API tools.

                Actions:
                - search: Find external API tools by keyword
                  catalog(action='search', query='gmail send email')
                  Restrict to known API(s): catalog(action='search', api='gmail', query='list messages')
                  or catalog(action='search', apis=['gmail','slack'], query='send message')

                - execute: Execute an API tool with real user credentials (one-time actions)
                  catalog(action='execute', tool_id='<uuid>', params={...})

                - response_schema: Get the tool's full schema - the input parameter
                  contract (which values are admissible - names, types, required flag, defaults,
                  closed enums via allowedValues), the output response structure (skeleton,
                  paths, SpEL examples) for mapping into the next step, AND the credential
                  requirement in `credential`: `credential.type` is the kind of credential the
                  tool needs (api_key | oauth2 | bearer_token | basic_auth | none) and
                  `credential.requiredScopes` lists the OAuth scopes a connection would request.
                  Use it to tell the user what request_credential will ask them to connect
                  (`type:"none"` means the tool needs no credential - just execute).
                  catalog(action='response_schema', tool_id='<uuid>')
                  Always call this BEFORE catalog(action='execute') on a tool you don't already know:
                  it tells you which params accept which values so execute() doesn't 400 on a
                  bad enum.

                - help: Get documentation (general overview, or specific topics)
                  catalog(action='help') - general overview
                  catalog(action='help', topics=['register']) - API registration field reference
                  catalog(action='help', topics=['schema']) - response schema & SpEL mapping guide

                - register_api: Register a custom API with endpoints (outputSchema REQUIRED per endpoint)
                  catalog(action='register_api', api_definition={apiName:'My API', baseUrl:'https://...', endpoints:[{name, endpoint, method, description, outputSchema:[{key,type,description}], params:[...]}]})

                - update_api: Update an existing custom API
                  catalog(action='update_api', api_id='<uuid>', api_definition={...updated fields...})

                - delete_api: Delete a custom API you own
                  catalog(action='delete_api', api_id='<uuid>')

                - list_custom_apis: List your registered custom APIs
                  catalog(action='list_custom_apis')

                NOT FOR INTERNAL RESOURCES:
                - Tables -> use table(action='...')
                - Interfaces -> use interface(action='...')

                USAGE FLOW (existing APIs):
                1. catalog(action='search', query='gmail list messages') -> get tool_id
                   If you already know the API: catalog(action='search', api='gmail', query='list messages')
                2. catalog(action='response_schema', tool_id='uuid') -> learn the param contract
                   (especially `inputSchema[*].allowedValues` - must pick ONE of those; default
                   in `inputSchema[*].default` is the recommended pre-fill) and the output skeleton
                3. catalog(action='execute', tool_id='uuid', params={...}) -> execute NOW

                USAGE FLOW (custom APIs):
                1. catalog(action='help', topics=['register']) -> get field reference
                2. catalog(action='register_api', api_definition={...}) -> get apiId
                3. catalog(action='search', query='my custom api') -> find tools
                4. catalog(action='execute', tool_id='uuid', params={...}) -> execute
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

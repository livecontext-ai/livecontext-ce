package com.apimarketplace.catalog.tools;

import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.agent.tools.common.ToolModule;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Unified help module for the catalog tool.
 * <p>Follows the same pattern as {@code workflow(action='help', topics=[...])}:</p>
 * <ul>
 *   <li>{@code catalog(action='help')} - general catalog overview</li>
 *   <li>{@code catalog(action='help', topics=['register'])} - custom API registration reference</li>
 *   <li>{@code catalog(action='help', topics=['schema'])} - response schema &amp; SpEL mapping guide</li>
 *   <li>{@code catalog(action='help', topics=['shaping'])} - response-shaping reference
 *       ({@code expand}, {@code max_items}, array digest, {@code nextAction})</li>
 * </ul>
 */
@Component
public class CatalogHelpModule implements ToolModule {

    private static final Set<String> VALID_TOPICS = Set.of("register", "schema", "shaping", "file_storage");

    @Override
    public List<AgentToolDefinition> getToolDefinitions() {
        return List.of();
    }

    @Override
    public boolean canHandle(String action) {
        return "help".equals(action);
    }

    @Override
    public Optional<ToolExecutionResult> execute(String action, Map<String, Object> parameters,
                                                  String tenantId, ToolExecutionContext context) {
        if (!"help".equals(action)) {
            return Optional.empty();
        }

        Object topicsRaw = parameters.get("topics");
        List<String> topics = extractTopics(topicsRaw);

        if (topics.isEmpty()) {
            return Optional.of(buildGeneralHelp());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        for (String topic : topics) {
            if (VALID_TOPICS.contains(topic)) {
                result.put(topic, switch (topic) {
                    case "register" -> buildRegisterHelp();
                    case "schema" -> buildSchemaHelp();
                    case "shaping" -> buildShapingHelp();
                    case "file_storage" -> com.apimarketplace.agent.tools.help.FileStorageHelp.get();
                    default -> Map.of();
                });
            } else {
                result.put(topic, Map.of("error", "Unknown topic: " + topic, "valid_topics", VALID_TOPICS));
            }
        }
        return Optional.of(ToolExecutionResult.success(result));
    }

    @SuppressWarnings("unchecked")
    private List<String> extractTopics(Object topicsRaw) {
        if (topicsRaw instanceof List<?> list) {
            return list.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
        }
        if (topicsRaw instanceof String s && !s.isBlank()) {
            return List.of(s);
        }
        return List.of();
    }

    // ── General help (no topics) ─────────────────────────────────────────────

    private ToolExecutionResult buildGeneralHelp() {
        return ToolExecutionResult.success(Map.of(
            "title", "Catalog Tool - Help",

            "actions", Map.of(
                "search", "Find API tools. Use query only, or restrict with api/apis: catalog(action='search', api='gmail', query='list messages')",
                "execute", "Execute an API tool - catalog(action='execute', tool_id='<uuid>', params={...})",
                "response_schema", "Get tool response structure - catalog(action='response_schema', tool_id='<uuid>')",
                "register_api", "Register a custom API - catalog(action='register_api', api_definition={...})",
                "update_api", "Update a custom API - catalog(action='update_api', api_id='<uuid>', api_definition={...})",
                "delete_api", "Delete a custom API - catalog(action='delete_api', api_id='<uuid>')",
                "list_custom_apis", "List your custom APIs - catalog(action='list_custom_apis')",
                "help", "This help - catalog(action='help')"
            ),

            "topics", Map.of(
                "register", "Full field reference for custom API registration - catalog(action='help', topics=['register'])",
                "schema", "Response schema & SpEL mapping guide - catalog(action='help', topics=['schema'])",
                "shaping", "How catalog responses are shaped (expand, max_items, digest, nextAction) - catalog(action='help', topics=['shaping'])",
                "file_storage", "How file outputs (image_generation, download_file, …) are persisted and rendered in interfaces - catalog(action='help', topics=['file_storage'])"
            ),

            "usage_flow_existing", List.of(
                "1. catalog(action='search', query='gmail list messages') → get tool_id",
                "2. If you know the API: catalog(action='search', api='gmail', query='list messages')",
                "3. Multiple APIs: catalog(action='search', apis=['gmail','slack'], query='send message')",
                "4. Inline shorthand: query='[gmail, slack] send message' or query='gmail, list messages'",
                "5. catalog(action='execute', tool_id='<uuid>', params={...}) → execute"
            ),

            "usage_flow_custom", List.of(
                "1. catalog(action='help', topics=['register']) → get field reference",
                "2. catalog(action='register_api', api_definition={...}) → get apiId",
                "3. catalog(action='search', query='my api name') → find tools",
                "4. catalog(action='execute', tool_id='<uuid>', params={...}) → execute"
            )
        ));
    }

    // ── Topic: register ──────────────────────────────────────────────────────

    private Map<String, Object> buildRegisterHelp() {
        return Map.ofEntries(
            Map.entry("title", "Custom API Registration - Full Reference"),

            Map.entry("minimum_example", Map.of(
                "apiName", "Stripe Payments",
                "baseUrl", "https://api.stripe.com",
                "endpoints", List.of(Map.ofEntries(
                    Map.entry("name", "create_charge"),
                    Map.entry("endpoint", "/v1/charges"),
                    Map.entry("method", "POST"),
                    Map.entry("description", "Create a payment charge"),
                    Map.entry("params", List.of(
                        Map.of("name", "amount", "in", "body", "type", "integer", "required", true, "description", "Amount in cents"),
                        Map.of("name", "currency", "in", "body", "type", "string", "required", true, "description", "3-letter ISO code")
                    )),
                    Map.entry("outputSchema", List.of(
                        Map.of("key", "id", "type", "string", "description", "Charge ID"),
                        Map.of("key", "amount", "type", "number", "description", "Amount charged in cents"),
                        Map.of("key", "status", "type", "string", "description", "Charge status (succeeded, pending, failed)")
                    ))
                ))
            )),

            Map.entry("api_level_fields", Map.of(
                "required", Map.of(
                    "apiName", "string - unique name",
                    "baseUrl", "string - base URL (https://...)",
                    "endpoints", "array - at least one endpoint"
                ),
                "optional", Map.ofEntries(
                    Map.entry("apiDescription", "string - shown in search results"),
                    Map.entry("authType", "none | apikey | bearer | basic | oauth2 (default: none). Aliases "
                        + "accepted: api_key, bearer_token, basic_auth. An unrecognised value is REJECTED with "
                        + "the list of accepted ones, never silently treated as an API key. 'oauth2' REQUIRES "
                        + "auth[0].oauth2Config and is refused without it - see auth_placement.oauth2 below. "
                        + "'custom' (a credential made of several fields) is not available here. Where the "
                        + "credential is SENT is auth_placement below."),
                    Map.entry("auth", "array - [{type, apiKeyConfig, oauth2Config}], first entry used. Later "
                        + "entries are ignored. 'type' takes the same values as authType. apiKeyConfig and "
                        + "oauth2Config are described in auth_placement below."),
                    Map.entry("apiCategory", "string (default: 'Custom APIs')"),
                    Map.entry("visibility", "private | public (default: private)"),
                    Map.entry("iconSlug", "string - brand icon identifier. It also NAMES the credential every "
                        + "tool of this API requires. When omitted it is derived from apiName: accents "
                        + "stripped, a trailing '-api' removed, then reduced to lowercase letters and digits "
                        + "('Weather API' gives weatherapi, 'Weather-API' gives weather). Read it back from "
                        + "credential_name in the register response rather than deriving it yourself. A key "
                        + "already in use on this installation is refused, whether it belongs to a built-in "
                        + "integration or to someone else's custom API, because one credential name is shared "
                        + "by everyone here. Updating YOUR OWN api keeps its key, so update_api never trips "
                        + "on this."),
                    Map.entry("apiVersion", "string - e.g. 'v2'"),
                    Map.entry("documentation", "string - URL to external docs (max 1000 chars)"),
                    Map.entry("rateLimits", "{ requestsPerSecond?: number, requestsPerDay?: number }"),
                    Map.entry("apiFixtures", "array - example responses for schema inference (see fixtures section)"),
                    Map.entry("requiredHeaders", "{ name: value } - constant headers sent on EVERY endpoint (an API version pin). See static_headers below.")
                )
            )),

            Map.entry("auth_placement", Map.ofEntries(
                Map.entry("what", "Where the credential is placed on each request. Declare it as "
                    + "auth[0].apiKeyConfig. Omit it and the default for your authType is used."),
                Map.entry("defaults", Map.of(
                    "bearer", "Authorization header, value prefixed 'Bearer '",
                    "apikey", "X-API-Key header, value sent raw",
                    "basic", "Authorization header, 'Basic ' + base64(username:password); the connection "
                        + "asks for a username and a password instead of one key",
                    "oauth2", "Authorization header with the access token the consent flow returns; "
                        + "apiKeyConfig does not apply, see auth_placement.oauth2",
                    "none", "no credential is sent and no connection is required"
                )),
                Map.entry("fields", Map.ofEntries(
                    Map.entry("location", "header (default) | query"),
                    Map.entry("headerName", "header carrying the credential when location=header, e.g. "
                        + "'X-Api-Token'. Any name is accepted."),
                    Map.entry("queryParamName", "query parameter carrying it when location=query, e.g. 'api_key'"),
                    Map.entry("keyName", "name of the credential field the secret is read from at "
                        + "execution (default: access_token for bearer, api_key otherwise). The connection "
                        + "form asks for one key whatever this says, so set it only when the provider's own "
                        + "field name matters."),
                    Map.entry("prefix", "string put in front of the value, e.g. 'Token '. OMIT it to get the "
                        + "default for your authType. Set it to \"\" to send the value raw, which some "
                        + "providers require on the Authorization header.")
                )),
                Map.entry("example_custom_header", Map.of(
                    "auth", List.of(Map.of("type", "apikey",
                        "apiKeyConfig", Map.of("location", "header", "headerName", "X-Api-Token"))))),
                Map.entry("example_query_key", Map.of(
                    "auth", List.of(Map.of("type", "apikey",
                        "apiKeyConfig", Map.of("location", "query", "queryParamName", "api_key"))))),
                Map.entry("example_non_bearer_prefix", Map.of(
                    "auth", List.of(Map.of("type", "bearer",
                        "apiKeyConfig", Map.of("headerName", "Authorization", "prefix", "Token "))))),
                Map.entry("oauth2", Map.ofEntries(
                    Map.entry("what", "For authType 'oauth2', declare the provider's endpoints in "
                        + "auth[0].oauth2Config. Without them the connection can never complete, so "
                        + "registration REFUSES an oauth2 API that omits the block."),
                    Map.entry("authorizationUrl", "REQUIRED - the provider's authorize endpoint (https)"),
                    Map.entry("tokenUrl", "REQUIRED - the provider's token endpoint (https)"),
                    Map.entry("refreshUrl", "optional - defaults to tokenUrl"),
                    Map.entry("scopes", "optional - array of scope strings requested at consent"),
                    Map.entry("example", Map.of("auth", List.of(Map.of("type", "oauth2",
                        "oauth2Config", Map.of(
                            "authorizationUrl", "https://provider.example.com/oauth/authorize",
                            "tokenUrl", "https://provider.example.com/oauth/token",
                            "scopes", List.of("read", "write")))))),
                    Map.entry("what_the_user_does", "The user creates an application on the provider's side "
                        + "and supplies its client id and secret when they connect. You cannot do that step "
                        + "for them; request the connection with credential(action='require', "
                        + "services=['<credential_name>']) and the user resolves it."),
                    Map.entry("an_endpoint_that_is_refused", "Both URLs are checked for shape: https, a "
                        + "hostname, and not a literal private or loopback address. The host is NOT resolved, so "
                        + "a name pointing at a private address still passes here and fails later at the "
                        + "provider. A refused URL comes back naming which field failed.")
                )),

                Map.entry("updating_keeps_nothing_you_omit", "update_api replaces the whole definition. An "
                    + "update that omits auth[0].apiKeyConfig RESETS the placement to the default for its "
                    + "auth type, and one that omits auth[0].oauth2Config on an oauth2 API is refused. Read "
                    + "the current definition with get_custom_api_details and resubmit the auth block with "
                    + "your changes."),

                Map.entry("after_registering", "An API with an authType other than 'none' has NO usable tools "
                    + "until a credential exists for this user. The register response returns credential_name; "
                    + "ask the user for the key with credential(action='require', services=['<credential_name>'], "
                    + "reason='...'), then call the tool to confirm. Executing before that returns "
                    + "approval_needed, not a result."),
                Map.entry("wrong_placement_symptom", "If the placement is wrong the provider answers 401 or 403 "
                    + "and the error comes from the provider, not from this tool - nothing here can detect it. "
                    + "Check the provider's documentation for the exact header or query parameter name and "
                    + "prefix, then apply it with update_api.")
            )),

            Map.entry("static_headers", Map.of(
                "what", "A header whose value never changes. Declare it as requiredHeaders {name: value} at the "
                      + "api level when every endpoint needs it, or headers {name: value} on one endpoint. It is "
                      + "sent automatically on every call, so nothing has to pass it: execute the tool without it. "
                      + "A value that changes per call is a param with location query/path/body/header instead.",
                "example", Map.of("requiredHeaders", Map.of("anthropic-version", "2023-06-01")),
                "accepted_values", "Short literals only: non-empty, at most 64 characters, no whitespace at all, no { } "
                      + "placeholder. Anything longer or templated is documentation or a runtime template and is refused.",
                "never_sent", "Headers the transport owns (Content-Type, Content-Length, Host, Connection, "
                      + "Transfer-Encoding, Accept-Encoding, plus the hop-by-hop ones: Expect, Upgrade, TE, "
                      + "Trailer, Keep-Alive, Proxy-Authenticate, Proxy-Authorization); the header the "
                      + "credential fills, which depends on this API's authType and auth[0].apiKeyConfig (see "
                      + "auth_placement); and Accept, which the platform sets to application/json before a "
                      + "declared header could apply.",
                "a_refused_value_is_simply_absent", "A value the rules below refuse is not registered at all, "
                      + "and nothing says so at registration: the upstream then answers 4xx without naming the "
                      + "missing header. Check each value against accepted_values BEFORE registering. To confirm "
                      + "afterwards, call response_schema for the tool: a header that was kept appears in its "
                      + "input schema with location 'header', and the value that will be sent is its default.",
                "endpoint_wins", "When both levels declare the same header name, the endpoint value is used."
            )),

            Map.entry("endpoint_fields", Map.of(
                "required", Map.of(
                    "name", "string - tool name (snake_case recommended, e.g. 'list_users')",
                    "endpoint", "string - path (e.g. '/users/{id}')",
                    "method", "GET | POST | PUT | PATCH | DELETE",
                    "description", "string - what this endpoint does",
                    "outputSchema", "array - REQUIRED typed response shape [{key, type, description, children?}], see output_schema below"
                ),
                "optional", Map.of(
                    "toolCategory", "string - grouping label (e.g. 'Data Access')",
                    "headers", "{ name: value } - constant headers sent on THIS endpoint only. See static_headers below.",
                    "nextHint", "string - LLM hint: what to do after using this tool",
                    "execution", "object - see execution_modes below",
                    "synthesis", "object - search index metadata (resource, action, summary, keywords)",
                    "pagination", "object - { type: 'cursor'|'offset', cursorParam, cursorPath, limitParam, maxLimit }"
                )
            )),

            Map.entry("param_fields", Map.of(
                "required", Map.of(
                    "name", "string - parameter name",
                    "in", "query | path | body | header (alias: 'location'). in='header' declares a header "
                        + "the CALLER fills per request; it is rejected if it collides with the header the "
                        + "credential occupies, or with a header the transport owns.",
                    "type", "string | integer | boolean | number",
                    "required", "boolean",
                    "description", "string"
                ),
                "optional", Map.of(
                    "hidden", "boolean - hide from user (e.g. internal IDs injected by system)",
                    "default", "string - default value if not provided",
                    "example", "string|object - example value for documentation"
                )
            )),

            Map.entry("execution_modes", Map.of(
                "sync", Map.of(
                    "description", "Standard request/response (default). For binary responses (image, PDF), set response.type='binary'",
                    "example_json", Map.of("mode", "sync", "request", Map.of("bodyType", "json"), "response", Map.of("type", "json")),
                    "example_binary", Map.of("mode", "sync", "request", Map.of("bodyType", "json"),
                        "response", Map.of("type", "binary"))
                ),
                "async_poll", Map.of(
                    "description", "Submit job → poll until complete",
                    "example", Map.of("mode", "async_poll",
                        "request", Map.of("bodyType", "json"),
                        "response", Map.of("type", "json"),
                        "async", Map.of(
                            "submit", Map.of("responseIdPath", "$.id"),
                            "poll", Map.of("method", "GET", "path", "/v1/jobs/{id}", "intervalMs", 2000, "maxWaitMs", 300000),
                            "status", Map.of("path", "$.status", "successValues", List.of("completed"), "failureValues", List.of("failed")),
                            "resultPath", "$.data"
                        ))
                ),
                "upload", Map.of(
                    "description", "Send file via multipart",
                    "example", Map.of("mode", "upload",
                        "request", Map.of("bodyType", "multipart",
                            "multipartFields", List.of(Map.of("name", "file", "source", "fileRef", "paramName", "audio"))))
                ),
                "streaming", Map.of(
                    "description", "Server-Sent Events (LLM streaming)",
                    "example", Map.of("mode", "streaming", "request", Map.of("bodyType", "json"), "response", Map.of("type", "sse"))
                )
            )),

            Map.entry("body_types", Map.of(
                "description", "Available request.bodyType values. Pick the one matching the upstream contract.",
                "json", "Default. Params serialized as JSON.",
                "multipart", "Multipart form data. Required for file uploads. Per-field multipartFields[].source: 'fileRef' (always upload a FileRef param), 'param' (always a text field), 'auto' (polymorphic: FileRef→file upload, object/array→JSON string, scalar→text). Use 'auto' for one field that accepts a file OR a string id/URL under the same name, e.g. Telegram send_photo 'photo'.",
                "form_urlencoded", "application/x-www-form-urlencoded body.",
                "raw_binary", "Raw bytes (or fileRef → bytes from MinIO) sent with the declared Content-Type.",
                "graphql", Map.of(
                    "description", "GraphQL request - produces { query, operationName?, variables? }. " +
                            "Only mode=sync + response.type=json supported. Author-declared variables override runtime params. " +
                            "Response { data, errors } is auto-unwrapped: non-empty errors → success=false, otherwise data is exposed at the root.",
                    "example", Map.of(
                        "mode", "sync",
                        "request", Map.of(
                            "bodyType", "graphql",
                            "graphql", Map.of(
                                "query", "query GetUser($id: ID!) { user(id: $id) { name email } }",
                                "operationName", "GetUser",
                                "variables", Map.of("apiVersion", "v2")
                            )
                        ),
                        "response", Map.of("type", "json")
                    )
                )
            )),

            Map.entry("output_schema", Map.of(
                "description", "Typed response shape. Declares what fields the next workflow node will see.",
                "allowed_types", "string | number | boolean | datetime | object | array | fileRef",
                "example", List.of(
                    Map.of("key", "users", "type", "array", "description", "List of users",
                        "children", List.of(
                            Map.of("key", "id", "type", "string", "description", "User ID"),
                            Map.of("key", "email", "type", "string", "description", "Email address"),
                            Map.of("key", "active", "type", "boolean", "description", "Account active")
                        )),
                    Map.of("key", "total", "type", "number", "description", "Total count")
                ),
                "note", "For async_poll, outputSchema describes the FINAL result (at resultPath), not the polling envelope"
            )),

            Map.entry("synthesis_fields", Map.of(
                "description", "Search index metadata - improves discoverability via catalog(action='search')",
                "fields", Map.of(
                    "resource", "string - what entity (e.g. 'emails', 'users', 'invoices')",
                    "action", "string - what operation (e.g. 'list', 'create', 'delete')",
                    "summary", "string - one-line description",
                    "summaryExtended", "string - longer description",
                    "keywordsPrimary", "string[] - main search keywords",
                    "keywordsSecondary", "string[] - synonym/alternate keywords"
                )
            )),

            Map.entry("fixtures", Map.of(
                "description", "Example responses stored as tool_responses for schema inference",
                "format", "apiFixtures: [{ endpointName: 'tool_name', response: { ...example JSON response... } }]"
            )),

            Map.entry("registration_steps", List.of(
                "1. Register: catalog(action='register_api', api_definition={...})",
                "2. Find tools: catalog(action='search', query='your api name')",
                "3. Check schema: catalog(action='response_schema', tool_id='<uuid>')",
                "4. Execute: catalog(action='execute', tool_id='<uuid>', params={...})",
                "5. List yours: catalog(action='list_custom_apis')",
                "6. Update: catalog(action='update_api', api_id='<uuid>', api_definition={...})",
                "7. Delete: catalog(action='delete_api', api_id='<uuid>')"
            ))
        );
    }

    // ── Topic: schema ────────────────────────────────────────────────────────

    private Map<String, Object> buildSchemaHelp() {
        return Map.of(
            "title", "Tool Response Schema & SpEL Mapping Guide",

            "response_shape", Map.of(
                "description", "catalog(action='response_schema', tool_id='<uuid>') returns BOTH the output skeleton AND the input contract.",
                "fields", Map.of(
                    "skeleton", "Output structure (see skeleton_format below). NULL when the tool has never returned a non-empty payload.",
                    "paths", "Flat list of dotted paths into the skeleton, ready for SpEL.",
                    "spelExamples", "Up to 5 ready-made SpEL expressions for the most common paths (only when paths is non-empty).",
                    "inputSchema", "Array of {name, type, location, required, default, allowedValues, example, description} - the param contract for action='execute'. Read this BEFORE building the execute() call: defaults and allowedValues are pinned here.",
                    "hint", "Present only when skeleton is null. Tells you what to do (execute the tool first, or pass different params if you already executed and got an empty payload). NOT an error - the call still succeeded."
                ),
                "cold_start", "If skeleton is null: call catalog(action='execute', tool_id='<uuid>', params={...}) once with valid params, then re-call response_schema. The first non-empty result auto-seeds the skeleton."
            ),

            "skeleton_format", Map.of(
                "description", "Skeletons use a compact format to describe JSON structure without data",
                "types", Map.of(
                    "_t: obj", "Object with named properties in 'props'",
                    "_t: arr", "Array with item structure in 'items'",
                    "string", "Text value",
                    "number", "Numeric value (int or float)",
                    "boolean", "true/false value",
                    "null", "Null value"
                ),
                "example", Map.of(
                    "skeleton", """
                        {
                          "_t": "obj",
                          "props": {
                            "data": {
                              "_t": "obj",
                              "props": {
                                "user": {
                                  "_t": "obj",
                                  "props": {
                                    "id": "string",
                                    "name": "string",
                                    "followers": "number"
                                  }
                                }
                              }
                            },
                            "status": "string"
                          }
                        }
                        """,
                    "meaning", "Object with 'data' (containing nested 'user' object) and 'status' string"
                )
            ),

            "spel_mapping", Map.of(
                "description", "SpEL (Spring Expression Language) is used to extract values from tool responses",
                "syntax", Map.of(
                    "access_field", "#result['fieldName']",
                    "nested_access", "#result['data']['user']['id']",
                    "array_first", "#result['items'][0]",
                    "array_size", "#result['items'].size()",
                    "null_safe", "#result['data']?.['optional']",
                    "default_value", "#result['field'] ?: 'default'"
                ),
                "examples", List.of(
                    Map.of(
                        "path", "data.user.id",
                        "spel", "#result['data']['user']['id']",
                        "description", "Get user ID from nested object"
                    ),
                    Map.of(
                        "path", "data.user.followers",
                        "spel", "#result['data']['user']['followers']",
                        "description", "Get follower count (number)"
                    ),
                    Map.of(
                        "path", "status",
                        "spel", "#result['status']",
                        "description", "Get status from root level"
                    )
                )
            ),

            "workflow_usage", Map.of(
                "description", "How to reference tool output in workflow steps using {{mcp:label.output.field}} syntax",
                "cross_step_reference", Map.of(
                    "description", "Reference output from a previous step in a downstream step's input",
                    "syntax", "{{mcp:<step_label>.output.<field_path>}}",
                    "example", """
                        Step 1 - "Get User Info" (tool: instagram_get_user_info)
                          input: { "username": "championsleague" }
                          → response contains data.user.id, data.user.follower_count

                        Step 2 - "Get User Posts" (tool: instagram_get_user_posts)
                          input: {
                            "user_id": "{{mcp:get_user_info.output.data.user.id}}",
                            "count": 10
                          }
                        """
                ),
                "note", "Use catalog(action='response_schema', tool_id='<uuid>') to see the exact response structure before writing references"
            ),

            "best_practices", List.of(
                "Use catalog(action='response_schema', tool_id='<uuid>') to see the exact structure before writing SpEL",
                "The skeleton saves tokens by showing structure without actual data",
                "Use the 'paths' array for quick reference to all available fields",
                "Arrays are shown with [] suffix (e.g., 'items[]') in paths",
                "Always use null-safe access (?.) for optional fields"
            )
        );
    }

    // ── Topic: shaping ───────────────────────────────────────────────────────

    private Map<String, Object> buildShapingHelp() {
        return Map.ofEntries(
            Map.entry("title", "Catalog Response Shaping - Reference"),

            Map.entry("why", "Large API responses are shaped before reaching you to keep the conversation context lean. " +
                "Each call's response carries the truth shape (total_items, sizes) and ONE concrete next action you can copy."),

            Map.entry("default_behavior", Map.ofEntries(
                Map.entry("per_leaf_cap", "String fields > 4 KB are clipped to a 200-char preview + '[TRUNCATED: <size>]' marker."),
                Map.entry("total_budget", "If the whole response exceeds 64 KB, the largest array is replaced with an array_digest."),
                Map.entry("array_digest_shape", Map.of(
                    "_shape", "array_digest",
                    "total_items", 10,
                    "preview_items", 3,
                    "items", "[...first 3 items, full content...]",
                    "skipped_from", 3,
                    "skipped_to", 9
                )),
                Map.entry("oversize_fallback", "If even after digesting the tree is still > 64 KB, you receive " +
                    "{ _shape: 'oversize', total_size_bytes, skeleton } - the structure but no values. Re-call with max_items=1 to walk items one at a time.")
            )),

            Map.entry("response_metadata_to_read", Map.of(
                "metadata.truncatedFields",
                    "Array of patterns: [{path: 'items[].about', count: 10, bytes: 5132}, ...]. " +
                    "Tells you which leaves were clipped and how many leaves matched each canonical path. " +
                    "[] in a path is the array-index wildcard.",
                "metadata.nextAction",
                    "ONE concrete next call. Has shape {tool, hint, params:{tool_id, parameters}}. " +
                    "Copy params verbatim. Ignore at your peril - it's how you recover from truncation/digest.",
                "metadata.credentialSource",
                    "Which key answered: 'user' (a key the account configured itself) or 'platform'. " +
                    "It is what HAPPENED, not what you asked for: leave credential_source unset and the " +
                    "call tries the account's own key first and falls back to the platform's.",
                "metadata.billedCredits",
                    "Credits this call was charged, present ONLY when the platform key answered and the " +
                    "charge went through whole. ABSENT MEANS NOT CHARGED HERE, never zero: the account's " +
                    "own key pays its provider directly, and most endpoints carry no platform price at " +
                    "all. Report it when the user asks what something cost; never add it up as though a " +
                    "missing one were a nothing."
            )),

            Map.entry("when_to_use_expand", List.of(
                "metadata.truncatedFields lists patterns you care about (e.g. items[].about).",
                "Re-call with expand=['items[].about'] to get those fields un-clipped on every item.",
                "expand uses the SAME [] wildcard syntax as truncatedFields - copy a path verbatim."
            )),

            Map.entry("when_to_use_max_items", List.of(
                "An array_digest is in the response and the API has NO offset/cursor (nextAction is prose-only).",
                "Re-call with max_items=1 to walk items one at a time.",
                "Or max_items=N for a structured peek at the first N items."
            )),

            Map.entry("when_nextAction_has_concrete_params", Map.of(
                "case", "An array digest fired AND the tool's input schema advertises a cursor (offset, cursor, pageToken, start_cursor, starting_after, etc.) and a size (limit, pageSize, max_results, etc.).",
                "what_you_do", "Copy nextAction.params.parameters verbatim. To jump to item N, edit the cursor value to N (zero-based for integer cursors).",
                "example", Map.of(
                    "hint", "Showing 3/10 items. The suggested call paginates from offset=3 with limit=1 (next item). Change `offset` to any value in [3..9] (zero-based) to jump to a specific item.",
                    "tool", "catalog",
                    "params", Map.of(
                        "tool_id", "01d07247-...",
                        "parameters", Map.of("dataset_id", "X", "offset", 3, "limit", 1)
                    )
                )
            )),

            Map.entry("when_nextAction_is_prose_only", Map.of(
                "case", "Array digest fired but the tool has no cursor/offset/page param.",
                "what_you_do", "Either call catalog(action='response_schema', tool_id='<uuid>') to inspect available pagination params, OR re-call with max_items=1."
            )),

            Map.entry("filerefs", "Maps shaped { _type: 'file', path, name, mimeType, size } are binary attachments. " +
                "They are NEVER digested or clipped. They also surface in metadata.attachments[] for direct rendering."),

            Map.entry("opaque_cursors", "Some APIs (e.g. Notion) use opaque base64 cursor tokens - the suggested " +
                "integer value will 400. If you get an error, fall back to catalog(action='response_schema', tool_id='<uuid>') to see the cursor's real shape."),

            Map.entry("dont", List.of(
                "Don't call get_tool_result for a result you JUST received - that tool is for transcripts compacted out of history. " +
                "For shaped output of the current turn, use expand / max_items / nextAction.params instead.",
                "Don't enumerate concrete indices like 'items[3].about' in expand - use the [] wildcard form already shown in truncatedFields.",
                "Don't loop on the same tool with no parameter change - read nextAction first."
            ))
        );
    }
}

package com.apimarketplace.agent.tools.help;

import com.apimarketplace.agent.domain.ToolParameter;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.registry.AgentToolRegistry;
import com.apimarketplace.agent.registry.ToolCategory;
import com.apimarketplace.agent.registry.ToolSchemaGenerator;
import com.apimarketplace.agent.tools.ToolsProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

import static com.apimarketplace.agent.registry.ToolSchemaGenerator.*;
import com.apimarketplace.agent.tools.ToolErrorCode;

/**
 * Provider for help and documentation tools.
 * Allows agents to discover available tools and get detailed documentation.
 */
@Slf4j
@Component
public class HelpToolsProvider implements ToolsProvider {

    private final AgentToolRegistry toolRegistry;

    public HelpToolsProvider(AgentToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @Override
    public ToolCategory getCategory() {
        return ToolCategory.HELP;
    }

    @Override
    public List<AgentToolDefinition> getTools() {
        return List.of(
            buildListAllToolsTool(),
            buildGetToolHelpTool(),
            buildGetResourceSchemaTool(),
            buildGetExamplesTool(),
            buildExpressionHelpTool()
        );
    }

    @Override
    public ToolExecutionResult execute(String toolName, Map<String, Object> parameters, ToolExecutionContext context) {
        try {
            return switch (toolName) {
                case "list_all_tools" -> executeListAllTools(parameters);
                case "get_tool_help" -> executeGetToolHelp(parameters);
                case "get_resource_schema" -> executeGetResourceSchema(parameters);
                case "get_examples" -> executeGetExamples(parameters);
                case "expression_help" -> executeExpressionHelp(parameters);
                default -> ToolExecutionResult.failure(ToolErrorCode.TOOL_NOT_FOUND, "Unknown tool: " + toolName);
            };
        } catch (Exception e) {
            log.error("Error executing help tool {}: {}", toolName, e.getMessage(), e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Error: " + e.getMessage());
        }
    }

    // ==================== Tool Definitions ====================

    private AgentToolDefinition buildListAllToolsTool() {
        List<ToolParameter> params = List.of(
            enumParam("category", "Filter by category (optional)", false,
                List.of("workflow", "agent", "interface", "datasource", "catalog", "visualization", "tasks", "utility", "application", "websearch", "help"))
        );

        return AgentToolDefinition.builder()
            .name("list_all_tools")
            .description("List all available tools. Optionally filter by category.")
            .category(ToolCategory.HELP)
            .parameters(params)
            .requiredParameters(List.of())
            .inputSchema(generateInputSchema(params, List.of()))
            .outputSchema(Map.of(
                "type", "object",
                "properties", Map.of(
                    "tools", Map.of("type", "array", "items", Map.of("type", "object")),
                    "count", Map.of("type", "integer"),
                    "categories", Map.of("type", "object")
                )
            ))
            .examples(List.of(
                "{\"tool\": \"list_all_tools\"}",
                "{\"tool\": \"list_all_tools\", \"parameters\": {\"category\": \"workflow\"}}"
            ))
            .helpText("""
                Lists all available tools that can be used by agents.

                Use this tool first to discover what operations are available.
                You can filter by category to see only tools for a specific domain.

                Categories:
                - workflow: Create and manage workflows
                - agent: Configure AI agents and skills
                - interface: Create visual interfaces (display data or interactive apps)
                - datasource: Manage data sources and tables
                - catalog: Discover API tools and their schemas
                - visualization: Display workflows, datasources, and interfaces in chat
                - tasks: Plan and track tasks
                - utility: File operations and data transformation
                - application: Browse and acquire marketplace applications
                - websearch: Web search and page content extraction
                - help: Get documentation and examples
                """)
            .requiresAuth(false)
            .tags(List.of("discovery", "documentation"))
            .build();
    }

    private AgentToolDefinition buildGetToolHelpTool() {
        List<ToolParameter> params = List.of(
            stringParam("tool_name", "Name of the tool to get help for", true)
        );

        return AgentToolDefinition.builder()
            .name("get_tool_help")
            .description("Get detailed help and documentation for a specific tool.")
            .category(ToolCategory.HELP)
            .parameters(params)
            .requiredParameters(List.of("tool_name"))
            .inputSchema(generateInputSchema(params, List.of("tool_name")))
            .outputSchema(Map.of(
                "type", "object",
                "properties", Map.of(
                    "name", Map.of("type", "string"),
                    "description", Map.of("type", "string"),
                    "helpText", Map.of("type", "string"),
                    "parameters", Map.of("type", "array"),
                    "examples", Map.of("type", "array"),
                    "inputSchema", Map.of("type", "object")
                )
            ))
            .examples(List.of(
                "{\"tool\": \"get_tool_help\", \"parameters\": {\"tool_name\": \"workflow_create\"}}",
                "{\"tool\": \"get_tool_help\", \"parameters\": {\"tool_name\": \"table\"}}"
            ))
            .helpText("""
                Gets detailed documentation for a specific tool including:
                - Full description
                - Parameter specifications
                - JSON Schema for input validation
                - Usage examples

                Use this before calling a tool to understand its requirements.
                """)
            .requiresAuth(false)
            .tags(List.of("documentation", "help"))
            .build();
    }

    private AgentToolDefinition buildGetResourceSchemaTool() {
        List<ToolParameter> params = List.of(
            enumParam("resource_type", "Type of resource to get schema for", true,
                List.of("workflow", "agent", "interface", "table"))
        );

        return AgentToolDefinition.builder()
            .name("get_resource_schema")
            .description("Get the JSON Schema for a resource type (workflow, agent, interface, table).")
            .category(ToolCategory.HELP)
            .parameters(params)
            .requiredParameters(List.of("resource_type"))
            .inputSchema(generateInputSchema(params, List.of("resource_type")))
            .outputSchema(Map.of(
                "type", "object",
                "properties", Map.of(
                    "resourceType", Map.of("type", "string"),
                    "schema", Map.of("type", "object"),
                    "description", Map.of("type", "string")
                )
            ))
            .examples(List.of(
                "{\"tool\": \"get_resource_schema\", \"parameters\": {\"resource_type\": \"workflow\"}}",
                "{\"tool\": \"get_resource_schema\", \"parameters\": {\"resource_type\": \"table\"}}"
            ))
            .helpText("""
                Gets the full JSON Schema for a resource type.

                Use this to understand the structure required when creating resources:
                - workflow: Workflow plan with triggers, mcps, tables, agents, cores, interfaces, notes, edges
                - agent: AI agent configuration with model, prompt, tools
                - interface: Visual interface (display data or interactive app with action_mapping)
                - table: Database table with columns and data
                """)
            .requiresAuth(false)
            .tags(List.of("schema", "documentation"))
            .build();
    }

    private AgentToolDefinition buildGetExamplesTool() {
        List<ToolParameter> params = List.of(
            enumParam("resource_type", "Type of resource", true,
                List.of("workflow", "agent", "interface", "table")),
            enumParam("operation", "Operation type (optional)", false,
                List.of("create", "update", "all"))
        );

        return AgentToolDefinition.builder()
            .name("get_examples")
            .description("Get usage examples for a resource type.")
            .category(ToolCategory.HELP)
            .parameters(params)
            .requiredParameters(List.of("resource_type"))
            .inputSchema(generateInputSchema(params, List.of("resource_type")))
            .outputSchema(Map.of(
                "type", "object",
                "properties", Map.of(
                    "resourceType", Map.of("type", "string"),
                    "examples", Map.of("type", "array", "items", Map.of("type", "object"))
                )
            ))
            .examples(List.of(
                "{\"tool\": \"get_examples\", \"parameters\": {\"resource_type\": \"workflow\"}}",
                "{\"tool\": \"get_examples\", \"parameters\": {\"resource_type\": \"table\", \"operation\": \"create\"}}"
            ))
            .helpText("""
                Gets practical examples for creating or updating resources.

                Each example includes:
                - Description of what it does
                - Complete JSON payload
                - Expected result
                """)
            .requiresAuth(false)
            .tags(List.of("examples", "documentation"))
            .build();
    }

    private AgentToolDefinition buildExpressionHelpTool() {
        List<ToolParameter> params = List.of(
            enumParam("category", "Filter by function category (optional)", false,
                List.of("all", "type", "utility", "math", "string", "date", "format"))
        );

        return AgentToolDefinition.builder()
            .name("expression_help")
            .description("Get documentation for the expression rendering engine. Lists all available functions, collection operators, and syntax rules for {{...}} expressions in interfaces and workflows.")
            .category(ToolCategory.HELP)
            .parameters(params)
            .requiredParameters(List.of())
            .inputSchema(generateInputSchema(params, List.of()))
            .helpText("""
                Documents the expression rendering engine used in interfaces and workflows.

                All expressions use {{...}} syntax:
                - {{variable|default}} - variables with optional pipe defaults (interface HTML only)
                - {{mcp:step.output.field}} - workflow step references
                - {{function(args)}} - custom functions (case-insensitive)
                - {{list.?[condition]}} - SpEL collection operators

                Use this tool to see all available functions, operators, and syntax rules.
                """)
            .requiresAuth(false)
            .tags(List.of("expressions", "functions", "documentation"))
            .build();
    }

    // ==================== Tool Execution ====================

    private ToolExecutionResult executeListAllTools(Map<String, Object> parameters) {
        String category = (String) parameters.get("category");

        List<AgentToolDefinition> tools;
        if (category != null && !category.isBlank()) {
            ToolCategory cat = ToolCategory.fromSlug(category);
            if (cat == null) {
                return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, "Invalid category: " + category);
            }
            tools = toolRegistry.getToolsByCategory(cat);
        } else {
            tools = toolRegistry.getAllTools();
        }

        List<Map<String, Object>> toolSummaries = tools.stream()
            .map(AgentToolDefinition::toSummary)
            .toList();

        return ToolExecutionResult.success(Map.of(
            "tools", toolSummaries,
            "count", toolSummaries.size(),
            "categories", toolRegistry.getCategoryCounts()
        ));
    }

    private ToolExecutionResult executeGetToolHelp(Map<String, Object> parameters) {
        String toolName = (String) parameters.get("tool_name");
        if (toolName == null || toolName.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "tool_name is required");
        }

        var doc = toolRegistry.getToolDocumentation(toolName);
        if (doc == null) {
            return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, "Tool not found: " + toolName);
        }

        return ToolExecutionResult.success(Map.of(
            "name", doc.name(),
            "description", doc.description(),
            "helpText", doc.helpText() != null ? doc.helpText() : "",
            "category", doc.category().getSlug(),
            "parameters", toolRegistry.getToolByName(toolName)
                .map(AgentToolDefinition::parameters)
                .orElse(List.of()),
            "examples", doc.examples() != null ? doc.examples() : List.of(),
            "inputSchema", doc.inputSchema() != null ? doc.inputSchema() : Map.of(),
            "outputSchema", doc.outputSchema() != null ? doc.outputSchema() : Map.of(),
            "tags", doc.tags() != null ? doc.tags() : List.of()
        ));
    }

    private ToolExecutionResult executeGetResourceSchema(Map<String, Object> parameters) {
        String resourceType = (String) parameters.get("resource_type");
        if (resourceType == null || resourceType.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "resource_type is required");
        }

        Map<String, Object> schema = switch (resourceType.toLowerCase()) {
            case "workflow" -> ToolSchemaGenerator.getWorkflowPlanSchema();
            case "agent" -> ToolSchemaGenerator.getAgentConfigSchema();
            case "interface" -> ToolSchemaGenerator.getInterfaceSchema();
            case "table", "datasource" -> ToolSchemaGenerator.getDataSourceSchema();
            default -> null;
        };

        if (schema == null) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_ENUM_VALUE, "Unknown resource type: " + resourceType);
        }

        return ToolExecutionResult.success(Map.of(
            "resourceType", resourceType,
            "schema", schema,
            "description", getResourceDescription(resourceType)
        ));
    }

    private ToolExecutionResult executeGetExamples(Map<String, Object> parameters) {
        String resourceType = (String) parameters.get("resource_type");
        String operation = (String) parameters.get("operation");

        if (resourceType == null || resourceType.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "resource_type is required");
        }

        List<Map<String, Object>> examples = getExamplesForResource(resourceType, operation);

        return ToolExecutionResult.success(Map.of(
            "resourceType", resourceType,
            "operation", operation != null ? operation : "all",
            "examples", examples
        ));
    }

    private ToolExecutionResult executeExpressionHelp(Map<String, Object> parameters) {
        String category = (String) parameters.get("category");
        if (category == null) category = "all";

        Map<String, Object> result = new java.util.LinkedHashMap<>();

        result.put("description", """
            Resolves placeholders in interface templates and workflow inputs.

            ONE ENGINE - all expressions use {{...}} syntax:
            - {{variable|default}} - interface template variable with pipe default
            - {{mcp:step.output.field}} - workflow step output (for workflow params, NOT interface templates)
            - {{functionName(args)}} - function call (case-insensitive, no # prefix)
            - {{list.?[condition]}} - SpEL collection filter (works inside {{...}})
            - {{list.![field]}} - SpEL collection projection (works inside {{...}})

            KEY RULES:
            - Interface templates use GENERIC names: {{title|My Product}} - never {{mcp:...}} in HTML
            - Variable mapping on the workflow node maps generic → workflow data
            - Functions are case-insensitive, no # prefix needed
            - Collection operators (.?[], .![], .^[], .$[]) also use {{...}} syntax
            """);

        result.put("syntax", Map.ofEntries(
            Map.entry("simpleVariable", "{{fieldName}} - Replace with value of fieldName (returns empty string if unresolved)"),
            Map.entry("pipeDefault", "{{fieldName|default}} - Variable with inline default (shows 'default' when no data)"),
            Map.entry("nestedPath", "{{user.address.city}} - Navigate nested objects"),
            Map.entry("withFunction", "{{formatDate(created_at, 'DD/MM/YYYY')}} - Apply function"),
            Map.entry("withDefault", "{{default(image_url, '/placeholder.jpg')}} - Fallback if null (function-based)"),
            Map.entry("workflowStep", "{{mcp:alias.output.field}} - Reference MCP step output (for workflow params)"),
            Map.entry("workflowTrigger", "{{trigger:name.output.field}} - Reference trigger output (for workflow params)"),
            Map.entry("agentStep", "{{agent:alias.output.field}} - Reference agent step output"),
            Map.entry("coreStep", "{{core:alias.output.field}} - Reference core node output (decision, loop, etc.)"),
            Map.entry("tableStep", "{{table:alias.output.field}} - Reference table operation output"),
            Map.entry("interfaceStep", "{{interface:alias.output.action_name.field_name}} - Reference user-submitted form data from an interface action (action_name is the normalized trigger label, e.g. {{interface:my_form.output.submit.email}})"),
            Map.entry("arrayAccess", "{{items[0]}} - Access array element by index. Nested: {{items[0].name}}"),
            Map.entry("interfaceTemplate", "{{title|My Product}} - Interface templates use GENERIC names with pipe defaults. SCOPE: pipe defaults ({{var|fallback}}) work ONLY in interface HTML templates, never in workflow step inputs. In workflow params, use {{default(var, 'fallback')}} instead. Never use {{mcp:...}} in interface HTML - map generic names via variable_mapping.")
        ));

        result.put("when_to_use", Map.of(
            "variables_and_functions", "{{variable}}, {{functionName(args)}} - for simple values and custom functions (case-insensitive, no # prefix). Preferred for most cases.",
            "collection_operations", "{{list.?[condition]}}, {{list.![field]}} - SpEL collection operators also work inside {{...}}. No separate syntax needed.",
            "java_methods", "{{name.toUpperCase()}} works but is NOT null-safe. Prefer custom functions ({{uppercase(name)}}) for null safety."
        ));

        result.put("typeFunctions", List.of(
            Map.of("name", "int(value)", "description", "Convert to integer", "example", "{{int(quantity)}}"),
            Map.of("name", "double(value)", "description", "Convert to decimal", "example", "{{double(price)}}"),
            Map.of("name", "long(value)", "description", "Convert to long integer", "example", "{{long(timestamp)}}"),
            Map.of("name", "float(value)", "description", "Convert to float", "example", "{{float(ratio)}}"),
            Map.of("name", "string(value)", "description", "Convert to string", "example", "{{string(id)}}"),
            Map.of("name", "bool(value)", "description", "Convert to boolean", "example", "{{bool(active)}}"),
            Map.of("name", "typeof(value)", "description", "Get type name of value", "example", "{{typeof(data)}}")
        ));

        result.put("utilityFunctions", List.of(
            Map.of("name", "size(value)", "description", "Get size of collection/string", "example", "{{size(items)}}"),
            Map.of("name", "len(value)", "description", "Alias for size()", "example", "{{len(items)}}"),
            Map.of("name", "default(value, fallback)", "description", "Return fallback if value is null, empty string, empty collection, or empty map", "example", "{{default(name, 'Unknown')}}"),
            Map.of("name", "coalesce(a, b, ...)", "description", "Return first non-null, non-empty-string value (skips both null and '')", "example", "{{coalesce(nickname, name, 'User')}}"),
            Map.of("name", "isempty(value)", "description", "Check if null/empty", "example", "{{isempty(description)}}"),
            Map.of("name", "isnull(value)", "description", "Check if null", "example", "{{isnull(field)}}"),
            Map.of("name", "ifempty(value, fallback)", "description", "Return fallback if value is null or empty string (NOT empty collections - use default() for collections)", "example", "{{ifempty(name, 'N/A')}}")
        ));

        result.put("mathFunctions", List.of(
            Map.of("name", "abs(value)", "description", "Absolute value", "example", "{{abs(difference)}}"),
            Map.of("name", "round(value, decimals)", "description", "Round to N decimals", "example", "{{round(price, 2)}}"),
            Map.of("name", "floor(value)", "description", "Round down", "example", "{{floor(score)}}"),
            Map.of("name", "ceil(value)", "description", "Round up", "example", "{{ceil(rating)}}"),
            Map.of("name", "min(a, b)", "description", "Minimum", "example", "{{min(price, max_price)}}"),
            Map.of("name", "max(a, b)", "description", "Maximum", "example", "{{max(quantity, 1)}}"),
            Map.of("name", "pow(base, exponent)", "description", "Power function", "example", "{{pow(2, 10)}}"),
            Map.of("name", "sqrt(value)", "description", "Square root", "example", "{{sqrt(area)}}")
        ));

        result.put("stringFunctions", List.of(
            Map.of("name", "uppercase(value)", "description", "Convert to UPPERCASE", "example", "{{uppercase(code)}}"),
            Map.of("name", "lowercase(value)", "description", "Convert to lowercase", "example", "{{lowercase(email)}}"),
            Map.of("name", "capitalize(value)", "description", "Capitalize first letter, lowercase rest", "example", "{{capitalize(name)}}"),
            Map.of("name", "trim(value)", "description", "Remove whitespace", "example", "{{trim(input)}}"),
            Map.of("name", "truncate(value, max, suffix)", "description", "Truncate text (suffix defaults to '...' if null)", "example", "{{truncate(desc, 100, '...')}}"),
            Map.of("name", "padleft(value, length, char)", "description", "Left-pad to length with char", "example", "{{padleft(id, 5, '0')}}"),
            Map.of("name", "padright(value, length, char)", "description", "Right-pad to length with char", "example", "{{padright(name, 20, ' ')}}"),
            Map.of("name", "replace(value, search, replacement)", "description", "Replace text", "example", "{{replace(text, '_', ' ')}}"),
            Map.of("name", "substring(value, start, end)", "description", "Extract substring", "example", "{{substring(code, 0, 3)}}"),
            Map.of("name", "join(list, delimiter)", "description", "Join list to string", "example", "{{join(items, ', ')}}"),
            Map.of("name", "split(value, delimiter)", "description", "Split string to list", "example", "{{split(tags, ',')}}"),
            Map.of("name", "contains(value, search)", "description", "Check if string contains substring OR if collection contains element", "example", "{{contains(text, 'error')}} or {{contains(tags, 'urgent')}}"),
            Map.of("name", "startswith(value, prefix)", "description", "Check if string starts with prefix", "example", "{{startswith(url, 'https')}}"),
            Map.of("name", "endswith(value, suffix)", "description", "Check if string ends with suffix", "example", "{{endswith(file, '.pdf')}}"),
            Map.of("name", "matches(value, regex)", "description", "Check if value matches regex pattern", "example", "{{matches(email, '.*@.*\\\\.com')}}"),
            Map.of("name", "length(value)", "description", "Get string length", "example", "{{length(name)}}")
        ));

        result.put("dateFunctions", List.of(
            Map.of("name", "formatdate(value, pattern)", "description", "Format date (patterns auto-converted: DD→dd, YYYY→yyyy)", "example", "{{formatdate(created_at, 'DD/MM/YYYY')}}"),
            Map.of("name", "now()", "description", "Current server timestamp as ISO string (e.g. '2026-03-25T15:30:45')", "example", "{{now()}}"),
            Map.of("name", "today()", "description", "Today's date as ISO string (e.g. '2026-03-25'), server timezone", "example", "{{today()}}")
        ));

        result.put("datePatterns", List.of("DD/MM/YYYY", "YYYY-MM-DD", "YYYY-MM-DD HH:mm:ss", "DD MMM YYYY", "HH:mm:ss"));

        result.put("formatFunctions", List.of(
            Map.of("name", "formatnumber(value, decimals)", "description", "Format number with decimal places", "example", "{{formatnumber(total, 2)}}"),
            Map.of("name", "formatcurrency(value, code)", "description", "Format as currency (ISO 4217 code)", "example", "{{formatcurrency(price, 'EUR')}}")
        ));

        result.put("jsonFunctions", List.of(
            Map.of("name", "json(value)", "description", "Parse a JSON string into a typed Map/List/scalar. IDEMPOTENT on already-typed Map/List/Number/Boolean. null/blank input → null. Throws structured error with field name + value preview on invalid JSON. Use to deliver an object/array to a tool param when the source is a JSON string.", "example", "{{json('{\"responseModalities\":[\"IMAGE\"]}')}} or {{json(mcp:fetch.output.body)}}"),
            Map.of("name", "fromjson(value)", "description", "Alias for json() - GitHub Actions parity (matches fromJSON).", "example", "{{fromjson(trigger:webhook.output.raw_body)}}"),
            Map.of("name", "tojson(value)", "description", "Serialize Map/List/scalar to a compact JSON string. Inverse of json(): json(tojson(map)) round-trips.", "example", "{{tojson(mcp:list.output.items)}}")
        ));

        result.put("nullBehavior", "Type-cast functions on null return ZERO values: int(null)→0, long(null)→0, float(null)→0.0, double(null)→0.0, bool(null)→false, string(null)→''. String functions on null return empty string. size(null)→0, len(null)→0. default(null, x)→x, default('', x)→x, default(emptyList, x)→x. ifempty(null, x)→x, ifempty('', x)→x but ifempty(emptyList, x)→emptyList (only checks null/empty-string). json(null)→null, json('')→null, json('   ')→null (blank treated as null); json(mapOrList)→same Map/List (idempotent). now() returns ISO string like '2026-03-25T15:30:45', today() returns '2026-03-25' (both server timezone).");

        result.put("compositeExamples", List.of(
            "{{formatcurrency(round(price * int(quantity), 2), 'EUR')}} - chain math + formatting",
            "{{uppercase(default(user.name, 'anonymous'))}} - chain fallback + transform",
            "{{truncate(join(tags, ', '), 50, '...')}} - chain join + truncate",
            "{{json(trigger:webhook.output.raw_body).foo.bar}} - parse stringified JSON then walk into typed Map",
            "{{tojson(mcp:list.output.items)}} - turn a List back into a JSON string (e.g. for an HTTP query param)"
        ));

        result.put("collectionOperations", Map.of(
            "note", "Collection operators work INSIDE {{...}} - no separate syntax needed.",
            "filter", "{{list.?[condition]}} - Filter: {{users.?[age > 18]}}",
            "projection", "{{list.![field]}} - Map: {{users.![name]}}",
            "firstMatch", "{{list.^[condition]}} - First: {{users.^[role == 'admin']}}",
            "lastMatch", "{{list.$[condition]}} - Last: {{logs.$[level == 'error']}}",
            "size", "{{list.size()}} - Get size",
            "isEmpty", "{{list.empty}} - Check if empty",
            "access", "{{list[0]}} - Access by index"
        ));

        result.put("ternaryOperator", Map.of(
            "syntax", "{{condition ? valueIfTrue : valueIfFalse}}",
            "examples", List.of(
                "{{active ? 'Yes' : 'No'}}",
                "{{score > 50 ? 'Pass' : 'Fail'}}",
                "{{user.name != null ? user.name : 'Anonymous'}}"
            )
        ));

        result.put("operators", Map.of(
            "arithmetic", "+ - * / %",
            "comparison", "== != < > <= >=",
            "logical", "&& || !",
            "examples", List.of("{{price * quantity}}", "{{score >= 50 && attempts < 3}}")
        ));

        result.put("stringMethods", Map.of(
            "note", "Native Java String methods work inside {{...}} expressions. WARNING: These throw NullPointerException on null. Prefer custom functions (uppercase() instead of .toUpperCase()) for null safety.",
            "examples", List.of(
                "{{name.toUpperCase()}}",
                "{{text.toLowerCase()}}",
                "{{title.length()}}",
                "{{email.contains('@')}}",
                "{{url.startsWith('https')}}",
                "{{text.substring(0, 10)}}"
            )
        ));

        result.put("wrongUsage", List.of(
            Map.of("wrong", "{{rows.map(r => r.name)}}", "correct", "{{rows.![name]}}"),
            Map.of("wrong", "{{items.filter(i => i.active)}}", "correct", "{{items.?[active == true]}}"),
            Map.of("wrong", "{{arr.join(', ')}}", "correct", "{{join(arr, ', ')}}"),
            Map.of("wrong", "${#formatdate(date, 'DD')}", "correct", "{{formatdate(date, 'DD')}} - all expressions use {{...}}, no # prefix needed"),
            Map.of("wrong", "{{#default(val, 'x')}}", "correct", "{{default(val, 'x')}} - no # prefix needed"),
            Map.of("wrong", "${items.?[active]}", "correct", "{{items.?[active]}} - collection operations also use {{...}} syntax"),
            Map.of("wrong", "{{isnull(x) ? 'empty' : x}}", "correct", "{{ifempty(x, 'empty')}} - isnull checks null only; isempty checks null/empty; ifempty returns fallback"),
            Map.of("wrong", "{{length(items)}}", "correct", "{{size(items)}} or {{len(items)}} - length() converts to string first and returns character count; use size()/len() for collection element count")
        ));

        // Apply category filter if specified
        if (!"all".equals(category)) {
            Map<String, Object> filtered = new java.util.LinkedHashMap<>();
            filtered.put("description", result.get("description"));
            filtered.put("syntax", result.get("syntax"));
            switch (category) {
                case "type" -> filtered.put("typeFunctions", result.get("typeFunctions"));
                case "utility" -> filtered.put("utilityFunctions", result.get("utilityFunctions"));
                case "math" -> filtered.put("mathFunctions", result.get("mathFunctions"));
                case "string" -> {
                    filtered.put("stringFunctions", result.get("stringFunctions"));
                    filtered.put("stringMethods", result.get("stringMethods"));
                }
                case "date" -> {
                    filtered.put("dateFunctions", result.get("dateFunctions"));
                    filtered.put("datePatterns", result.get("datePatterns"));
                    filtered.put("formatFunctions", result.get("formatFunctions"));
                }
                case "format" -> filtered.put("formatFunctions", result.get("formatFunctions"));
            }
            filtered.put("wrongUsage", result.get("wrongUsage"));
            return ToolExecutionResult.success(filtered);
        }

        return ToolExecutionResult.success(result);
    }

    private String getResourceDescription(String resourceType) {
        return switch (resourceType.toLowerCase()) {
            case "workflow" -> """
                A workflow defines a series of nodes connected by edges. Uses 7-prefix system:
                - triggers: Entry points (webhook, chat, schedule, datasource, manual)
                - mcps: MCP catalog tool calls
                - tables: CRUD operations on datasources
                - agents: AI agents (agent, guardrail, classify)
                - cores: Control flow (decision, switch, loop, split, merge, fork, transform, wait)
                - notes: Documentation
                - interfaces: Visual interfaces (display data or interactive apps with action_mapping + triggers)
                """;
            case "agent" -> "An AI agent configuration specifies the model, system prompt, temperature, and available tools for an AI assistant.";
            case "interface" -> "Visual HTML template with 3 modes: (1) DISPLAY: variable_mapping binds generic {{var|default}} to workflow data. (2) APPLICATION: action_mapping binds forms/buttons to triggers → user submits → workflow runs → results displayed (loop). (3) MULTI-PAGE: navigate action switches between interfaces. Format: {cssSelector: 'trigger:label:actiontype'}. Types: submit (form), click (manual), message (chat), navigate (interface).";
            case "table", "datasource" -> "A table stores data permanently in the database. It can contain inline data, with support for columns, rows, and SQL-like operations.";
            default -> "";
        };
    }

    private List<Map<String, Object>> getExamplesForResource(String resourceType, String operation) {
        return switch (resourceType.toLowerCase()) {
            case "workflow" -> getWorkflowExamples(operation);
            case "agent" -> getAgentExamples(operation);
            case "interface" -> getInterfaceExamples(operation);
            case "table", "datasource" -> getDataSourceExamples(operation);
            default -> List.of();
        };
    }

    private List<Map<String, Object>> getWorkflowExamples(String operation) {
        return List.of(
            Map.of(
                "title", "Simple Slack notification workflow",
                "description", "A workflow that sends a Slack message when triggered",
                "operation", "create",
                "payload", Map.of(
                    "name", "Slack Notification",
                    "description", "Send a message to Slack",
                    "plan", Map.of(
                        "triggers", List.of(Map.of(
                            "id", "trigger-1",
                            "type", "manual",
                            "label", "Start",
                            "strategy", "single"
                        )),
                        "mcps", List.of(Map.of(
                            "id", "slack/send_message",
                            "type", "mcp",
                            "label", "Send Message"
                        )),
                        "edges", List.of(Map.of("from", "trigger:start", "to", "mcp:send_message"))
                    )
                )
            ),
            Map.of(
                "title", "Workflow with decision",
                "description", "A workflow with conditional branching",
                "operation", "create",
                "payload", Map.of(
                    "name", "Conditional Workflow",
                    "plan", Map.of(
                        "triggers", List.of(Map.of(
                            "id", "trigger-1",
                            "type", "manual",
                            "label", "Start"
                        )),
                        "mcps", List.of(
                            Map.of("id", "api/check_status", "type", "mcp", "label", "Check Status"),
                            Map.of("id", "notify/success", "type", "mcp", "label", "Success Action"),
                            Map.of("id", "notify/failure", "type", "mcp", "label", "Failure Action")
                        ),
                        "cores", List.of(Map.of(
                            "id", "decision-1",
                            "type", "decision",
                            "label", "Status Check",
                            "decisionConditions", List.of(
                                Map.of("id", "if", "type", "if", "label", "Success", "expression", "mcp:check_status.output.status == 'ok'"),
                                Map.of("id", "else", "type", "else", "label", "Failure")
                            )
                        )),
                        "edges", List.of(
                            Map.of("from", "trigger:start", "to", "mcp:check_status"),
                            Map.of("from", "mcp:check_status", "to", "core:status_check"),
                            Map.of("from", "core:status_check:if", "to", "mcp:success_action"),
                            Map.of("from", "core:status_check:else", "to", "mcp:failure_action")
                        )
                    )
                )
            )
        );
    }

    private List<Map<String, Object>> getAgentExamples(String operation) {
        return List.of(
            Map.of(
                "title", "Simple OpenAI agent",
                "description", "An agent using GPT-4 for general assistance",
                "operation", "create",
                "payload", Map.of(
                    "name", "Assistant Agent",
                    "systemPrompt", "You are a helpful assistant. Answer questions clearly and concisely.",
                    "provider", "openai",
                    "model", "gpt-4",
                    "temperature", 0.7,
                    "maxTokens", 1000
                )
            ),
            Map.of(
                "title", "Tool-enabled agent",
                "description", "An agent that can use external tools",
                "operation", "create",
                "payload", Map.of(
                    "name", "Tool Agent",
                    "systemPrompt", "You are an assistant with access to tools. Use them when needed.",
                    "provider", "openai",
                    "model", "gpt-4",
                    "temperature", 0.3,
                    "maxIterations", 5,
                    "tools", List.of("search_tools", "workflow")
                )
            )
        );
    }

    private List<Map<String, Object>> getInterfaceExamples(String operation) {
        return List.of(
            Map.of(
                "title", "DISPLAY mode: Product card",
                "description", "Read-only display of workflow data using variable_mapping",
                "operation", "create + add_to_workflow",
                "payload", Map.ofEntries(
                    Map.entry("step1_create", "interface(action='create', name='Product Card', description='Displays product information', html_template='<div style=\"padding:16px\"><h2>{{title|My Product}}</h2><p>{{description|Product description}}</p><span>{{price|0.00}} EUR</span></div>')"),
                    Map.entry("step2_add_with_mapping", "workflow(action='add_node', type='interface', label='Display', params={interface_id: '<uuid>', variable_mapping: {'title': '{{mcp:fetch.output.name}}', 'price': '{{mcp:fetch.output.price}}'}}, connect_after='FetchData')")
                )
            ),
            Map.of(
                "title", "APPLICATION mode: Search app",
                "description", "Interactive app - user submits form → triggers workflow → results displayed (loop)",
                "operation", "create + add_to_workflow",
                "payload", Map.ofEntries(
                    Map.entry("step1_create", "interface(action='create', name='Search App', description='Search form with results', html_template='<form id=\"search\"><input name=\"query\" placeholder=\"Search...\"/><button type=\"submit\">Go</button></form><div>{{results|No results}}</div>')"),
                    Map.entry("step2_add_as_app", "workflow(action='add_node', type='interface', label='Search Page', params={interface_id: '<uuid>', variable_mapping: {'results': '{{mcp:search.output.items}}'}, action_mapping: {'#search': 'trigger:search_input:submit'}}, connect_after='Search')"),
                    Map.entry("pattern", "APPLICATION = interface + action_mapping + trigger. User submits → workflow runs → interface displays results → user submits again (loop).")
                )
            ),
            Map.of(
                "title", "MULTI-PAGE mode: Navigation between interfaces",
                "description", "Multiple interfaces with navigate action for page switching",
                "operation", "add_to_workflow",
                "payload", Map.ofEntries(
                    Map.entry("main_page", "workflow(action='add_node', type='interface', label='Main Page', params={interface_id: '<uuid>', action_mapping: {'#go-settings': 'interface:settings_page:navigate'}}, connect_after='...')"),
                    Map.entry("settings_page", "workflow(action='add_node', type='interface', label='Settings Page', params={interface_id: '<uuid2>', action_mapping: {'#back': 'interface:main_page:navigate'}}, connect_after='...')"),
                    Map.entry("pattern", "MULTI-PAGE = navigate action switches between interface nodes without API call.")
                )
            )
        );
    }

    private List<Map<String, Object>> getDataSourceExamples(String operation) {
        return List.of(
            Map.of(
                "title", "Inline data source",
                "description", "A data source with static inline data",
                "operation", "create",
                "payload", Map.of(
                    "name", "Sample Data",
                    "sourceType", "INLINE",
                    "sourceConfig", Map.of(
                        "data", List.of(
                            Map.of("id", 1, "name", "Item 1"),
                            Map.of("id", 2, "name", "Item 2")
                        )
                    ),
                    "columnOrder", List.of("id", "name")
                )
            ),
            Map.of(
                "title", "API data source",
                "description", "A data source that fetches from an API",
                "operation", "create",
                "payload", Map.of(
                    "name", "API Data",
                    "sourceType", "API",
                    "sourceConfig", Map.of(
                        "url", "https://api.example.com/data",
                        "method", "GET",
                        "headers", Map.of("Authorization", "Bearer {{token}}")
                    )
                )
            )
        );
    }
}

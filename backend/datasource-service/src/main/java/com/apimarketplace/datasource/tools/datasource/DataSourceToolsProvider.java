package com.apimarketplace.datasource.tools.datasource;

import com.apimarketplace.agent.domain.ToolParameter;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.registry.ToolCategory;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.apimarketplace.agent.registry.ToolSchemaGenerator.*;

/**
 * Provider for unified datasource tool (facade pattern).
 * Datasource-service native version - delegates to modules that use services directly (no HTTP hop).
 * Exposes ONE tool "table" with action parameter for all operations.
 */
@Component
public class DataSourceToolsProvider implements ToolsProvider {

    private static final Logger log = LoggerFactory.getLogger(DataSourceToolsProvider.class);

    private final DataSourceTableModule tableModule;
    private final DataSourceRowModule rowModule;
    private final DataSourceSchemaModule schemaModule;
    private final TablePublishModule publishModule;
    private final com.apimarketplace.datasource.services.VectorFeatureGate vectorFeatureGate;

    public DataSourceToolsProvider(DataSourceTableModule tableModule,
                                    DataSourceRowModule rowModule,
                                    DataSourceSchemaModule schemaModule,
                                    TablePublishModule publishModule,
                                    com.apimarketplace.datasource.services.VectorFeatureGate vectorFeatureGate) {
        this.tableModule = tableModule;
        this.rowModule = rowModule;
        this.schemaModule = schemaModule;
        this.publishModule = publishModule;
        this.vectorFeatureGate = vectorFeatureGate;
    }

    private static final List<String> VALID_ACTIONS = List.of(
        "create", "get", "list", "update", "delete",
        "query_rows", "insert_rows", "update_rows", "delete_rows",
        "add_columns",
        // Marketplace publication lifecycle
        "publish", "unpublish",
        "help"
    );

    @Override
    public ToolCategory getCategory() {
        return ToolCategory.DATASOURCE;
    }

    @Override
    public List<AgentToolDefinition> getTools() {
        return List.of(buildUnifiedDatasourceTool());
    }

    @Override
    public ToolExecutionResult execute(String toolName, Map<String, Object> parameters, ToolExecutionContext context) {
        if (!"table".equals(toolName)) {
            return ToolExecutionResult.failure(ToolErrorCode.TOOL_NOT_FOUND, "Unknown tool: " + toolName);
        }

        // Map table_id to datasource_id internally
        if (parameters.get("table_id") != null && parameters.get("datasource_id") == null) {
            parameters = new HashMap<>(parameters);
            parameters.put("datasource_id", parameters.get("table_id"));
        }

        String action = (String) parameters.get("action");
        if (action == null || action.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                "action is required. Valid actions: " + String.join(", ", VALID_ACTIONS));
        }

        try {
            String tenantId = context.tenantId();
            if (tenantId == null && !"help".equals(action)) {
                return ToolExecutionResult.failure(ToolErrorCode.TENANT_NOT_FOUND, "tenantId is required");
            }

            if (tableModule.canHandle(action)) {
                return tableModule.execute(action, parameters, tenantId, context)
                    .orElse(ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Table module failed"));
            }

            if (rowModule.canHandle(action)) {
                return rowModule.execute(action, parameters, tenantId, context)
                    .orElse(ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Row module failed"));
            }

            if (schemaModule.canHandle(action)) {
                return schemaModule.execute(action, parameters, tenantId, context)
                    .orElse(ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Schema module failed"));
            }

            if (publishModule.canHandle(action)) {
                return publishModule.execute(action, parameters, tenantId, context)
                    .orElse(ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Publish module failed"));
            }

            return ToolExecutionResult.failure(ToolErrorCode.VALIDATION_ERROR,
                "Invalid action: " + action + ". Valid actions: " + String.join(", ", VALID_ACTIONS));

        } catch (Exception e) {
            log.error("Error executing datasource action {}: {}", action, e.getMessage(), e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Error: " + e.getMessage());
        }
    }

    private AgentToolDefinition buildUnifiedDatasourceTool() {
        List<ToolParameter> params = List.of(
            ToolParameter.builder()
                .name("action")
                .type("string")
                .description("Action to perform. "
                    + "Table CRUD: create (data and/or columns), list, get, update (name/description), delete. "
                    + "Rows: query_rows (where and/or similarity, limit), insert_rows (rows), update_rows (where + set), "
                    + "delete_rows (where REQUIRED - there is no truncate action: to delete ALL rows use "
                    + "where={column:'id', operator:'IS NOT NULL'}). "
                    + "Schema: add_columns (columns). "
                    + "Marketplace: publish (title + interface_id), unpublish. "
                    + "help: full reference (column types, WHERE syntax, examples).")
                .required(true)
                .enumValues(VALID_ACTIONS)
                .build(),
            ToolParameter.builder()
                .name("table_id")
                .type("integer")
                .description("Table ID (required for most operations except create, list, help)")
                .required(false)
                .build(),
            stringParam("name", "Table name (for: create, update)", false),
            stringParam("description", "Table description (for: create, update)", false),
            ToolParameter.builder()
                .name("data")
                .type("array")
                .description("Data rows as array of objects (for: create). Object keys become column names - use meaningful names from user context (e.g. {category: 'Food', price: 10}), NEVER generic names like 'Colonne 1' or 'col1'")
                .required(false)
                .build(),
            ToolParameter.builder()
                .name("rows")
                .type("array")
                .description("Rows to insert (for: insert_rows). Flat format: [{name: 'Jane', age: 25}]. Keys must match existing column names.")
                .required(false)
                .build(),
            ToolParameter.builder()
                .name("columns")
                .type("array")
                // Advertise vector only where the edition accepts it - an
                // advertised-but-rejected type sends the agent into retries.
                .description("Column schema [{name, type, display?, defaultValue?}]. Use alone for empty tables, or with data to set types. Column names must match data keys. Types: text, number, date, checkbox, select, multi_select, rating, sentiment, progress, file, image, email, phone, url"
                    + (vectorFeatureGate.isVectorAllowed() ? ", vector" : ""))
                .required(false)
                .build(),
            ToolParameter.builder()
                .name("where")
                .type("object")
                .description("WHERE condition (for: query_rows, update_rows, delete_rows). Format: {column, operator, value}. "
                    + "Operators: '=', '!=', '>', '<', '>=', '<=', 'LIKE', 'IN' (value = non-empty array), "
                    + "'IS NULL', 'IS NOT NULL' (no value needed). "
                    + "column = bare column name, no 'data.' prefix; 'id' = the row's primary key. "
                    + "All comparisons are TEXTUAL: '>' '<' '>=' '<=' compare as strings (lexicographic), NOT numerically - "
                    + "do not rely on them for number/date ranges.")
                .required(false)
                .build(),
            ToolParameter.builder()
                .name("set")
                .type("object")
                .description("Column values to set (for: update_rows). Format: {col1: val1, ...}")
                .required(false)
                .build(),
            ToolParameter.builder()
                .name("similarity")
                .type("object")
                .description(vectorFeatureGate.isVectorAllowed()
                    ? "Vector similarity search (for: query_rows). Format: {column: 'embedding', queryVector: [0.1,...], topK?: 5, threshold?: 0.8}. Use with vector columns for RAG/nearest-neighbor search. Can be combined with where for hybrid filtering."
                    : "Not available on this deployment (self-hosted-only feature). Use where filters for querying.")
                .required(false)
                .build(),
            intParam("limit", "Max results to return. Default 25 for list, 20 for query_rows. query_rows has NO offset - to page a large result, narrow with where instead of raising limit. (for: list, query_rows)", false, 25),
            intParam("offset", "Pagination offset (for: list ONLY - query_rows does not support it)", false, 0),

            // ==================== Marketplace publication (publish, unpublish) ====================
            stringParam("title", "Marketplace listing title - REQUIRED for publish", false),
            stringParam("interface_id", "Landing interface UUID - REQUIRED for publish (the public-facing page presented to acquirers before they install the table).", false),
            enumParam("visibility", "Marketplace visibility: 'PRIVATE' (default), 'PUBLIC', 'UNLISTED' (for: publish)", false,
                List.of("PRIVATE", "PUBLIC", "UNLISTED")),
            intParam("credits_per_use", "Credits charged to acquirers per use. Default 0 (free). (for: publish)", false, 0)
        );

        return AgentToolDefinition.builder()
            .name("table")
            .description("""
                Persistent database table storage. For create: pass data=[{colName: value, ...}] - keys become column names (use descriptive names from user context, NEVER 'Colonne 1' or 'col1'). Or columns=[{name, type}] for empty schema.
                Call table(action='help') for column types, WHERE syntax, and examples.
                Marketplace: publish requires title + interface_id (landing page). unpublish marks the listing inactive - acquirers keep their copies.
                """)
            .category(ToolCategory.DATASOURCE)
            .parameters(params)
            .requiredParameters(List.of("action"))
            .inputSchema(generateInputSchema(params, List.of("action")))
            .helpText("Call table(action='help') for full documentation.")
            .requiresAuth(true)
            .tags(List.of("table", "crud", "unified", "sql"))
            .build();
    }
}

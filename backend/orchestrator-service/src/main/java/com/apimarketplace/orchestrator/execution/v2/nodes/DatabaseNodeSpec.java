package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Node specification for the Database node.
 *
 * Output schema:
 * - node_type: string ("DATABASE")
 * - success: boolean
 * - operation: string (select, insert, update, delete, execute)
 * - rows: array (for select/execute with result set)
 * - columns: array (column names for select/execute)
 * - row_count: number (number of rows returned)
 * - affected_rows: number (for insert/update/delete)
 * - duration_ms: number
 */
@Component
public class DatabaseNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("DATABASE")
            .label("Database")
            .category("core")
            .variablePrefix("core")
            .description("Execute SQL queries against databases (PostgreSQL, MySQL, MSSQL)")
            .terminal(false)
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("node_type")
                    .type("string")
                    .description("Always 'DATABASE'")
                    .defaultValue("DATABASE")
                    .build(),
                OutputFieldDef.builder()
                    .key("success")
                    .type("boolean")
                    .description("True if the query executed successfully")
                    .build(),
                OutputFieldDef.builder()
                    .key("operation")
                    .type("string")
                    .description("The database operation (select, insert, update, delete, execute)")
                    .build(),
                OutputFieldDef.builder()
                    .key("rows")
                    .type("array")
                    .description("Array of row objects for SELECT queries")
                    .build(),
                OutputFieldDef.builder()
                    .key("columns")
                    .type("array")
                    .description("Array of column names for SELECT queries")
                    .build(),
                OutputFieldDef.builder()
                    .key("row_count")
                    .type("number")
                    .description("Number of rows returned for SELECT queries")
                    .build(),
                OutputFieldDef.builder()
                    .key("affected_rows")
                    .type("number")
                    .description("Number of rows affected for INSERT/UPDATE/DELETE")
                    .build(),
                OutputFieldDef.builder()
                    .key("duration_ms")
                    .type("number")
                    .description("Total query execution time in milliseconds")
                    .build()
            ))
            .keywords(List.of("database", "sql", "query", "postgres", "mysql", "mssql", "select", "insert", "update", "delete"))
            .build();
    }
}

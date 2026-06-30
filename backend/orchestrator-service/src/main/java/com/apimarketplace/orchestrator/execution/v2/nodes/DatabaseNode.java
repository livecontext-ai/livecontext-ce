package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.credential.client.dto.CredentialSummaryDto;
import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;

/**
 * Database node - Execute SQL queries against databases (PostgreSQL, MySQL, MSSQL).
 *
 * Connection credentials (host, port, username, password, dbType, databaseName, ssl)
 * are loaded from the credential system (Settings > Credentials > Database) when a
 * credentialId is configured. Falls back to inline config fields for backward compatibility.
 *
 * Supports operations: select, insert, update, delete, execute.
 * ALWAYS uses PreparedStatement with parameterized queries.
 *
 * SECURITY: NEVER concatenates user input into SQL. NEVER logs passwords.
 */
public class DatabaseNode extends BaseNode {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseNode.class);
    private static final String DATABASE_INTEGRATION = "database";
    private static final int DEFAULT_TIMEOUT = 30000;
    private static final int MAX_ROWS = 10000;

    private final Core.DatabaseConfig config;
    private CredentialClient credentialClient;

    public DatabaseNode(String nodeId, Core.DatabaseConfig config) {
        super(nodeId, NodeType.DATABASE);
        this.config = config;
    }

    @Override
    public void acceptServices(ServiceRegistry registry) {
        super.acceptServices(registry);
        this.credentialClient = registry.getCredentialClient();
    }

    @Override
    public NodeExecutionResult execute(ExecutionContext context) {
        long startTime = System.currentTimeMillis();

        if (config == null) {
            return NodeExecutionResult.failureWithOutput(nodeId,
                "Database configuration is required.",
                Map.of("node_type", "DATABASE", "resolved_params", Map.of()),
                System.currentTimeMillis() - startTime);
        }

        // Resolve connection from credential system or inline config
        String dbType, host, databaseName, username, password;
        int port;
        boolean sslEnabled;

        Long credentialId = config.credentialId();
        if (credentialId != null && credentialClient != null) {
            Optional<CredentialSummaryDto> cred = credentialClient.getCredentialById(context.tenantId(), credentialId);
            if (cred.isEmpty()) {
                logger.warn("Database credential {} not found, falling back to default", credentialId);
                cred = credentialClient.getDefaultCredential(context.tenantId(), DATABASE_INTEGRATION);
            }
            if (cred.isEmpty()) {
                return NodeExecutionResult.failureWithOutput(nodeId,
                    "Database credential not found. Configure a Database credential and set it on this node before running.",
                    Map.of("node_type", "DATABASE"),
                    System.currentTimeMillis() - startTime);
            }
            Map<String, Object> data = cred.get().getCredentialData();
            dbType = getString(data, "db_type");
            host = getString(data, "host");
            port = getInt(data, "port", 5432);
            databaseName = getString(data, "database_name");
            username = getString(data, "username");
            password = getString(data, "password");
            sslEnabled = "true".equalsIgnoreCase(getString(data, "ssl_enabled"));
        } else {
            // Fallback: inline config (backward compatibility)
            dbType = config.dbType();
            host = resolveTemplateString(config.host(), context);
            port = config.port();
            databaseName = resolveTemplateString(config.databaseName(), context);
            username = resolveTemplateString(config.username(), context);
            password = resolveTemplateString(config.password(), context);
            sslEnabled = config.sslEnabled() != null ? config.sslEnabled() : false;
        }

        // Operation-specific fields always come from config
        String query = resolveTemplateString(config.query(), context);
        // Accept PostgreSQL-style `$N` placeholders (what docs advertise and what LLMs
        // naturally emit for a Postgres-default node) by rewriting them to JDBC's `?`
        // before PreparedStatement#prepare. JDBC does not understand `$N` natively, so
        // without this rewrite queries like `SELECT ... WHERE x = $1` fail with
        // "parameter index out of range" even when queryParams is correctly ordered (#DB1).
        query = normalizePlaceholders(query);
        String operation = config.operation();
        int timeout = config.timeout() != null ? config.timeout() : DEFAULT_TIMEOUT;
        if (dbType == null) dbType = "postgresql";

        // Resolve query params
        List<String> queryParams = new ArrayList<>();
        if (config.queryParams() != null) {
            for (String param : config.queryParams()) {
                queryParams.add(resolveTemplateString(param, context));
            }
        }

        // Snapshot resolved configuration for the inspector "Resolved parameters" panel.
        // Built once, used in every exit path (validation failure, success, exception).
        // Connection password/private-key never go in here - secrets must not leak into
        // workflow_step_data.input_data.
        Map<String, Object> resolvedParams = new LinkedHashMap<>();
        resolvedParams.put("dbType", dbType);
        resolvedParams.put("host", host);
        resolvedParams.put("port", port);
        resolvedParams.put("databaseName", databaseName);
        resolvedParams.put("username", username);
        resolvedParams.put("operation", operation);
        resolvedParams.put("query", query);
        if (!queryParams.isEmpty()) resolvedParams.put("queryParams", queryParams);
        resolvedParams.put("timeout", timeout);
        resolvedParams.put("sslEnabled", sslEnabled);

        logger.info("Database node executing: nodeId={}, dbType={}, host={}, database={}, operation={}, itemId={}",
            nodeId, dbType, host, databaseName, operation, context.itemId());

        // Validate required fields
        if (host == null || host.isBlank()) {
            return NodeExecutionResult.failureWithOutput(nodeId,
                "Database: 'host' is required. Configure it in the Database credential.",
                buildErrorResult(operation, startTime, resolvedParams),
                System.currentTimeMillis() - startTime);
        }
        if (databaseName == null || databaseName.isBlank()) {
            return NodeExecutionResult.failureWithOutput(nodeId,
                "Database: 'databaseName' is required. Configure it in the Database credential.",
                buildErrorResult(operation, startTime, resolvedParams),
                System.currentTimeMillis() - startTime);
        }
        if (query == null || query.isBlank()) {
            return NodeExecutionResult.failureWithOutput(nodeId,
                "Database: 'query' is required.",
                buildErrorResult(operation, startTime, resolvedParams),
                System.currentTimeMillis() - startTime);
        }

        String jdbcUrl = buildJdbcUrl(dbType, host, port, databaseName, sslEnabled);

        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password)) {
            conn.setNetworkTimeout(Runnable::run, timeout);

            Map<String, Object> result;
            if ("select".equals(operation)) {
                result = executeSelect(conn, query, queryParams);
            } else if ("insert".equals(operation) || "update".equals(operation) || "delete".equals(operation)) {
                result = executeUpdate(conn, query, queryParams, operation);
            } else if ("execute".equals(operation)) {
                result = executeGeneric(conn, query, queryParams);
            } else {
                throw new IllegalArgumentException(
                    "Unknown database operation: " + operation +
                    ". Valid: select, insert, update, delete, execute");
            }

            long durationMs = System.currentTimeMillis() - startTime;
            result.put("node_type", "DATABASE");
            result.put("resolved_params", resolvedParams);
            result.put("success", true);
            result.put("operation", operation);
            result.put("duration_ms", durationMs);

            logger.info("Database node completed: nodeId={}, operation={}, durationMs={}",
                nodeId, operation, durationMs);

            return successWithMetadata(result, context);

        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startTime;
            logger.error("Database node failed: nodeId={}, operation={}, error={}",
                nodeId, operation, e.getMessage());

            Map<String, Object> errorResult = buildErrorResult(operation, startTime, resolvedParams);
            return NodeExecutionResult.failureWithOutput(nodeId, e.getMessage(), errorResult, durationMs);
        }
    }

    private Map<String, Object> executeSelect(Connection conn, String query, List<String> params)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(query)) {
            setParameters(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();

                List<String> columns = new ArrayList<>();
                for (int i = 1; i <= colCount; i++) {
                    columns.add(meta.getColumnLabel(i));
                }

                List<Map<String, Object>> rows = new ArrayList<>();
                boolean truncated = false;
                while (rs.next()) {
                    if (rows.size() >= MAX_ROWS) {
                        truncated = true;
                        break;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= colCount; i++) {
                        row.put(columns.get(i - 1), rs.getObject(i));
                    }
                    rows.add(row);
                }

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("rows", rows);
                result.put("columns", columns);
                result.put("row_count", rows.size());
                if (truncated) {
                    result.put("truncated", true);
                }
                return result;
            }
        }
    }

    private Map<String, Object> executeUpdate(Connection conn, String query, List<String> params, String operation)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(query)) {
            setParameters(ps, params);
            int affected = ps.executeUpdate();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("affected_rows", affected);
            return result;
        }
    }

    private Map<String, Object> executeGeneric(Connection conn, String query, List<String> params)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(query)) {
            setParameters(ps, params);
            boolean hasResultSet = ps.execute();

            Map<String, Object> result = new LinkedHashMap<>();
            if (hasResultSet) {
                try (ResultSet rs = ps.getResultSet()) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int colCount = meta.getColumnCount();

                    List<String> columns = new ArrayList<>();
                    for (int i = 1; i <= colCount; i++) {
                        columns.add(meta.getColumnLabel(i));
                    }

                    List<Map<String, Object>> rows = new ArrayList<>();
                    boolean truncated = false;
                    while (rs.next()) {
                        if (rows.size() >= MAX_ROWS) {
                            truncated = true;
                            break;
                        }
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= colCount; i++) {
                            row.put(columns.get(i - 1), rs.getObject(i));
                        }
                        rows.add(row);
                    }

                    result.put("rows", rows);
                    result.put("columns", columns);
                    result.put("row_count", rows.size());
                    if (truncated) {
                        result.put("truncated", true);
                    }
                }
            } else {
                result.put("affected_rows", ps.getUpdateCount());
            }
            return result;
        }
    }

    private void setParameters(PreparedStatement ps, List<String> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            ps.setString(i + 1, params.get(i));
        }
    }

    /**
     * Rewrite PostgreSQL-style `$1, $2, …` placeholders to JDBC's `?` while leaving
     * occurrences inside quoted literals untouched:
     *  - single-quoted string literals (`'...'`, `''` escape)
     *  - E-string literals (`E'...'`, backslash-escape aware - #DB1)
     *  - double-quoted identifiers (`"col$1"`, `""` escape - #DB1)
     *  - dollar-quoted bodies (`$tag$ … $tag$`)
     *
     * Visible for testing.
     */
    static String normalizePlaceholders(String query) {
        if (query == null || query.isEmpty()) return query;

        StringBuilder out = new StringBuilder(query.length());
        int i = 0;
        int len = query.length();
        while (i < len) {
            char c = query.charAt(i);

            // #DB1: E-string literals - `E'...'` with `\'` (and `''`) as escapes.
            // Only treated as e-string when the `E`/`e` is a standalone token (not part
            // of an identifier), otherwise `someColumnE'x'` is a syntax error anyway.
            if ((c == 'E' || c == 'e') && i + 1 < len && query.charAt(i + 1) == '\''
                && (i == 0 || !isIdentifierChar(query.charAt(i - 1)))) {
                out.append(c);          // E
                out.append('\'');       // opening quote
                i += 2;
                while (i < len) {
                    char s = query.charAt(i);
                    out.append(s);
                    i++;
                    if (s == '\\' && i < len) {
                        // backslash escape: copy next char verbatim, don't let it terminate the string
                        out.append(query.charAt(i));
                        i++;
                        continue;
                    }
                    if (s == '\'') {
                        if (i < len && query.charAt(i) == '\'') {
                            out.append('\'');
                            i++;
                        } else {
                            break;
                        }
                    }
                }
                continue;
            }

            // Skip single-quoted string literals: '...' with '' as an escape for a single quote.
            if (c == '\'') {
                out.append(c);
                i++;
                while (i < len) {
                    char s = query.charAt(i);
                    out.append(s);
                    i++;
                    if (s == '\'') {
                        if (i < len && query.charAt(i) == '\'') {
                            out.append('\'');
                            i++;
                        } else {
                            break;
                        }
                    }
                }
                continue;
            }

            // #DB1: Skip double-quoted identifiers: "col$1" preserves `$1` verbatim.
            // `""` is the escape for a literal `"` inside the identifier.
            if (c == '"') {
                out.append(c);
                i++;
                while (i < len) {
                    char s = query.charAt(i);
                    out.append(s);
                    i++;
                    if (s == '"') {
                        if (i < len && query.charAt(i) == '"') {
                            out.append('"');
                            i++;
                        } else {
                            break;
                        }
                    }
                }
                continue;
            }

            // Skip PostgreSQL dollar-quoted string literals: $tag$ ... $tag$ (tag may be empty).
            if (c == '$') {
                int tagEnd = i + 1;
                while (tagEnd < len) {
                    char t = query.charAt(tagEnd);
                    if (t == '$') break;
                    if (!(Character.isLetterOrDigit(t) || t == '_')) { tagEnd = -1; break; }
                    tagEnd++;
                }
                if (tagEnd > 0 && tagEnd < len && query.charAt(tagEnd) == '$') {
                    String tag = query.substring(i, tagEnd + 1);
                    out.append(tag);
                    i = tagEnd + 1;
                    int close = query.indexOf(tag, i);
                    if (close < 0) {
                        out.append(query, i, len);
                        return out.toString();
                    }
                    out.append(query, i, close + tag.length());
                    i = close + tag.length();
                    continue;
                }

                // Placeholder `$<digits>` - rewrite to `?`.
                int digitsEnd = i + 1;
                while (digitsEnd < len && Character.isDigit(query.charAt(digitsEnd))) digitsEnd++;
                if (digitsEnd > i + 1) {
                    out.append('?');
                    i = digitsEnd;
                    continue;
                }
                // Lone `$` with no digits - copy verbatim.
                out.append(c);
                i++;
                continue;
            }

            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private String buildJdbcUrl(String dbType, String host, int port, String databaseName, boolean sslEnabled) {
        return switch (dbType) {
            case "postgresql" -> {
                String url = "jdbc:postgresql://" + host + ":" + port + "/" + databaseName;
                if (sslEnabled) url += "?ssl=true&sslmode=require";
                yield url;
            }
            case "mysql" -> {
                String url = "jdbc:mysql://" + host + ":" + port + "/" + databaseName;
                if (sslEnabled) url += "?useSSL=true&requireSSL=true";
                yield url;
            }
            case "mssql" -> {
                String url = "jdbc:sqlserver://" + host + ":" + port + ";databaseName=" + databaseName;
                if (sslEnabled) url += ";encrypt=true;trustServerCertificate=false";
                yield url;
            }
            default -> throw new IllegalArgumentException(
                "Unknown dbType: " + dbType + ". Valid: postgresql, mysql, mssql");
        };
    }

    private static String getString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? String.valueOf(val) : null;
    }

    private static int getInt(Map<String, Object> map, String key, int defaultVal) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { return defaultVal; }
        }
        return defaultVal;
    }

    private Map<String, Object> buildErrorResult(String operation, long startTime, Map<String, Object> resolvedParams) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("node_type", "DATABASE");
        result.put("resolved_params", resolvedParams != null ? resolvedParams : Map.of());
        result.put("success", false);
        result.put("operation", operation);
        result.put("duration_ms", System.currentTimeMillis() - startTime);
        return result;
    }

    public Core.DatabaseConfig getConfig() {
        return config;
    }

    public static class Builder {
        private String nodeId;
        private Core.DatabaseConfig config;

        public Builder nodeId(String nodeId) { this.nodeId = nodeId; return this; }
        public Builder databaseConfig(Core.DatabaseConfig config) { this.config = config; return this; }
        public Builder templateAdapter(Object adapter) { return this; }
        public DatabaseNode build() { return new DatabaseNode(nodeId, config); }
    }

    public static Builder builder() { return new Builder(); }
}

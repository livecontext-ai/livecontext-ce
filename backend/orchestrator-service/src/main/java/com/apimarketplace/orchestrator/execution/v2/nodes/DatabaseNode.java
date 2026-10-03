package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.common.web.UrlSafetyValidator;
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

    /** A templated port / timeout / sslEnabled is reported through the workspace-variable rule. */
    private void reportDeferred(Map<String, Object> resolvedParams, String field, Object value) {
        String template = deferredScalar("database", field);
        if (template != null) {
            resolvedParams.put(field,
                com.apimarketplace.orchestrator.services.template.ReportedParams.valueFrom(template, value));
        }
    }

    @Override
    public NodeExecutionResult execute(ExecutionContext context) {
        long startTime = System.currentTimeMillis();

        if (this.config == null) {
            return NodeExecutionResult.failureWithOutput(nodeId,
                "Database configuration is required.",
                Map.of("node_type", "DATABASE", "resolved_params", Map.of()),
                System.currentTimeMillis() - startTime);
        }
        // This execution's config: a {{...}} port, timeout or sslEnabled resolved now, never the
        // default. A local, not the field: the node is shared by concurrent items.
        Core.DatabaseConfig config;
        try {
            config = withDeferredScalars("database", this.config, Core.DatabaseConfig.class, context);
        } catch (IllegalStateException e) {
            return NodeExecutionResult.failureWithOutput(nodeId, e.getMessage(),
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
                String credentialTemplate = deferredScalar("database", "credentialId");
                if (credentialTemplate != null) {
                    // A credential chosen by a {{...}} reference is never swapped for the default:
                    // upstream data picked it, and running on another account would hide that.
                    return NodeExecutionResult.failureWithOutput(nodeId,
                        "database.credentialId '" + credentialTemplate + "' resolved to credential " + credentialId
                            + ", which is not available. No other credential was used.",
                        Map.of("node_type", "DATABASE", "resolved_params", Map.of()),
                        System.currentTimeMillis() - startTime);
                }
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
        reportDeferred(resolvedParams, "port", port);
        reportDeferred(resolvedParams, "timeout", timeout);
        reportDeferred(resolvedParams, "sslEnabled", sslEnabled);

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

        // The database name is concatenated into the JDBC URL, where every driver treats some
        // punctuation as a property separator. For SQL Server the URL is
        // "...;databaseName=" + name, so a name of "x;serverName=10.0.0.5" appends a driver
        // property that redirects the connection to a host the SSRF guard below never saw; the
        // Postgres and MySQL URLs take '?' and '&' the same way (pgjdbc's socketFactory property
        // is reachable that way). Whoever sets the host also sets the database name, so this is
        // inside the guard's threat model, not a separate one.
        String rejectedCharacter = firstJdbcMetacharacter(databaseName);
        if (rejectedCharacter != null) {
            return NodeExecutionResult.failureWithOutput(nodeId,
                "Database: 'databaseName' must not contain " + rejectedCharacter
                    + ". Connection properties cannot be smuggled through the database name;"
                    + " configure them on the Database credential instead.",
                buildErrorResult(operation, startTime, resolvedParams),
                System.currentTimeMillis() - startTime);
        }

        // The HOST reaches the same URL, from the same tenant-controlled sources, and had no
        // equivalent guard (LC-073). See firstJdbcHostMetacharacter for why resolution was not the
        // filter it looked like.
        String rejectedHostCharacter = firstJdbcHostMetacharacter(host);
        if (rejectedHostCharacter != null) {
            return NodeExecutionResult.failureWithOutput(nodeId,
                "Database: 'host' must not contain " + rejectedHostCharacter
                    + ". Connection properties cannot be smuggled through the host either;"
                    + " configure them on the Database credential instead.",
                buildErrorResult(operation, startTime, resolvedParams),
                System.currentTimeMillis() - startTime);
        }

        // SSRF: the host and port come from workflow input or a stored credential and go straight
        // to a socket, so they get the same shared filter a URL-shaped input gets (LC-075), and the
        // driver is then made to dial the address that filter vetted rather than resolve the name
        // itself a second time (LC-073). See buildPinnedJdbcUrl for what each driver supports.
        String jdbcUrl;
        try {
            java.net.InetAddress vetted = UrlSafetyValidator.resolveOutboundHostSafe(host, port);
            jdbcUrl = buildPinnedJdbcUrl(dbType, host, port, databaseName, sslEnabled, vetted);
        } catch (IllegalArgumentException e) {
            return NodeExecutionResult.failureWithOutput(nodeId,
                "Database: " + e.getMessage(),
                buildErrorResult(operation, startTime, resolvedParams),
                System.currentTimeMillis() - startTime);
        }

        try (Connection conn = openConnection(jdbcUrl, username, password)) {
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

    /**
     * The one place a JDBC connection is opened. Package-private and overridable so a test can
     * assert the URL that gets here (which host it names, and that no driver property was smuggled
     * through the database name) without a live database or a socket.
     */
    Connection openConnection(String jdbcUrl, String username, String password) throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    /**
     * The characters that end the database-name component of a JDBC URL and start something else:
     * a driver property ({@code ;} for SQL Server, {@code ?} and {@code &} for Postgres and MySQL),
     * another path segment, or a fragment. Anything a real database name may legitimately contain
     * (spaces, {@code =}, {@code @}, dots, dashes) is deliberately NOT here: over-rejecting turns a
     * hardening fix into an outage for whoever already runs a database with that name.
     */
    private static final String JDBC_URL_METACHARACTERS = ";?&/\\#";

    /**
     * The same characters, plus the three that make a HOST different from a database name.
     *
     * <p>{@code %} because the shared guard treats it as the IPv6 zone separator and judges only
     * what precedes it, so everything after it reaches the URL unvetted; {@code @} because it
     * separates userinfo from the authority, which no real host name contains; and {@code ,}
     * because pgjdbc (and Connector/J) read a comma-separated host list as MULTIPLE targets for
     * failover - {@code host=93.184.216.34,10.0.9.5} would smuggle a second, never-vetted host
     * into the very authority the guard just approved (CASA readiness round 3). {@code [},
     * {@code ]} and {@code :} stay allowed: a bracketed IPv6 literal is a shape the Database node
     * is configured with today, and a host carrying a stray {@code :} fails to resolve rather
     * than injecting anything.
     */
    private static final String JDBC_URL_HOST_METACHARACTERS = JDBC_URL_METACHARACTERS + "%@,";

    /**
     * @return a quoted rendering of the first JDBC-URL metacharacter in {@code databaseName}, or
     *         null when the name is safe to concatenate. Package-private so the rejection is
     *         testable without a live driver.
     */
    static String firstJdbcMetacharacter(String databaseName) {
        return firstCharacterFrom(databaseName, JDBC_URL_METACHARACTERS);
    }

    /**
     * The same rejection for the HOST, which reaches the same delimited URL and had no guard at all
     * (LC-073).
     *
     * <p>The host is as tenant-controlled as the database name - both come from workflow input or
     * the same stored credential - and it is concatenated into the authority of the very URL the
     * database name was being protected from. Resolution filtered most of the abuse by accident:
     * {@code evil.com?socketFactory=x} simply does not resolve. It did NOT filter
     * {@code 93.184.216.34%?socketFactory=evil}, because the shared guard truncates the name at the
     * {@code %} before resolving it and this node then concatenated the ORIGINAL string. The guard
     * refuses that host outright now; this check is the second, local barrier, and it also covers
     * the characters the guard has no reason to care about.
     *
     * @return a quoted rendering of the first offending character, or null when the host is safe to
     *         concatenate
     */
    static String firstJdbcHostMetacharacter(String host) {
        return firstCharacterFrom(host, JDBC_URL_HOST_METACHARACTERS);
    }

    private static String firstCharacterFrom(String value, String forbidden) {
        if (value == null) {
            return null;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (forbidden.indexOf(c) >= 0) {
                return "'" + c + "'";
            }
        }
        return null;
    }

    /**
     * The JDBC URL for a target the shared guard has already vetted, built so the driver dials the
     * vetted ADDRESS while keeping the configured NAME wherever the name carries identity
     * (LC-073 / LC-075).
     *
     * <p><b>PostgreSQL.</b> The name stays in the URL and pgjdbc is handed
     * {@code socketFactory} + {@code socketFactoryArg}, so the socket goes to the vetted address
     * and the TLS upgrade still runs against the name. This is what makes the pin free of cost:
     * SNI (which managed providers route on) and {@code sslmode=verify-full} both keep working.
     * pgjdbc builds its own {@code InetSocketAddress} from the name first, so one extra lookup
     * still happens, but its answer is discarded rather than dialled.
     *
     * <p><b>MySQL and SQL Server.</b> {@code mysql-connector-j} IS a dependency of this service
     * (added for {@code DatabaseNode} itself, an external database a workflow queries - never for
     * this service's own datasource, which is Postgres), so a {@code dbType: "mysql"} connection
     * now really reaches {@code DriverManager} and can succeed; {@code mssql-jdbc} is still absent,
     * so a {@code "mssql"} connection still fails there before any of this matters. Neither driver
     * takes the shared {@link UrlSafetyValidator.PinnedAddressSocketFactory} (Connector/J wants its
     * own {@code com.mysql.cj.protocol.SocketFactory} interface; mssql-jdbc 9.2+ would take the
     * shared factory through {@code socketFactoryClass} + {@code socketFactoryConstructorArg}, but
     * is not on the classpath to try), so neither can be pinned through a socket factory today. The
     * URL is therefore built the older way for both: the vetted literal replaces the host when
     * there is no TLS (which pins it, since nothing then resolves the name again), and a TLS
     * connection - which WOULD leave the rebinding window open by keeping the name - is refused
     * outright below rather than connected unpinned. mysql-connector-j being on the classpath is
     * exactly why that refusal is not hypothetical for MySQL: {@code sslEnabled=true} against
     * {@code dbType: "mysql"} is a real, reachable connection attempt now, not a dead branch.
     */
    static String buildPinnedJdbcUrl(String dbType, String host, int port, String databaseName,
                                     boolean sslEnabled, java.net.InetAddress vetted) {
        if ("postgresql".equals(dbType)) {
            String url = buildJdbcUrl(dbType, host, port, databaseName, sslEnabled);
            return url
                + (url.indexOf('?') < 0 ? '?' : '&')
                + "socketFactory=" + UrlSafetyValidator.PinnedAddressSocketFactory.class.getName()
                + "&socketFactoryArg=" + UrlSafetyValidator.toSocketHost(vetted);
        }
        // MySQL / SQL Server: the only pinning available without a driver-specific socket factory
        // is the vetted literal in the authority. With TLS that literal would break certificate
        // identity, and keeping the NAME would let the driver resolve it again unpinned (the
        // rebinding window LC-073 closes). Refuse that combination rather than connect unpinned.
        if (sslEnabled) {
            throw new IllegalArgumentException("TLS connections to " + dbType + " cannot be pinned to the "
                + "vetted address on this install (only PostgreSQL supports it), so they are refused. "
                + "Connect without TLS over a trusted network, or use PostgreSQL.");
        }
        String url = buildJdbcUrl(dbType, UrlSafetyValidator.toUrlHost(vetted), port, databaseName, false);
        assertAuthorityIsVettedLiteral(url, vetted);
        return url;
    }

    /**
     * Last-line guard: a non-PostgreSQL JDBC URL must name the vetted IP literal as its host, never a
     * name the driver would resolve again. Fails loudly if a future edit reintroduces the name.
     */
    static void assertAuthorityIsVettedLiteral(String jdbcUrl, java.net.InetAddress vetted) {
        String literal = UrlSafetyValidator.toUrlHost(vetted);
        if (!jdbcUrl.contains("//" + literal + ":")) {
            throw new IllegalStateException("Refusing an unpinned JDBC URL: its host is not the vetted address "
                + literal);
        }
    }

    static String buildJdbcUrl(String dbType, String host, int port, String databaseName, boolean sslEnabled) {
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

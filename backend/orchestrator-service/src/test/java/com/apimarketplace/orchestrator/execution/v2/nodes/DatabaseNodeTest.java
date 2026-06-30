package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DatabaseNode.
 * Tests focus on validation paths (null config, missing required fields)
 * and JDBC URL building since real database connections cannot be tested in unit tests.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DatabaseNode")
class DatabaseNodeTest {

    @Mock
    private WorkflowPlan mockPlan;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        context = ExecutionContext.create(
            "run-1",
            "workflow-run-1",
            "tenant-1",
            "item-0",
            0,
            Map.of(),
            mockPlan
        );
    }

    @Nested
    @DisplayName("execute - validation")
    class ExecuteValidation {

        @Test
        @DisplayName("should return failure when config is null")
        void execute_withNullConfig_returnsFailure() {
            DatabaseNode node = new DatabaseNode("core:database", null);

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().isPresent());
            assertTrue(result.errorMessage().get().contains("Database configuration is required"));
            assertEquals("DATABASE", result.output().get("node_type"));
        }

        @Test
        @DisplayName("should return failure when host is missing")
        void execute_withMissingHost_returnsFailure() {
            Core.DatabaseConfig config = new Core.DatabaseConfig(
                "postgresql", null, null, "mydb",
                "user", "pass", null,
                "SELECT 1", null, "select", null, null
            );
            DatabaseNode node = new DatabaseNode("core:database", config);

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().isPresent());
            assertTrue(result.errorMessage().get().contains("'host' is required"));
        }

        @Test
        @DisplayName("should return failure when databaseName is missing")
        void execute_withMissingDatabaseName_returnsFailure() {
            Core.DatabaseConfig config = new Core.DatabaseConfig(
                "postgresql", "dbhost.example.com", null, null,
                "user", "pass", null,
                "SELECT 1", null, "select", null, null
            );
            DatabaseNode node = new DatabaseNode("core:database", config);

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().isPresent());
            assertTrue(result.errorMessage().get().contains("'databaseName' is required"));
        }

        @Test
        @DisplayName("should return failure when query is missing")
        void execute_withMissingQuery_returnsFailure() {
            Core.DatabaseConfig config = new Core.DatabaseConfig(
                "postgresql", "dbhost.example.com", null, "mydb",
                "user", "pass", null,
                null, null, "select", null, null
            );
            DatabaseNode node = new DatabaseNode("core:database", config);

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().isPresent());
            assertTrue(result.errorMessage().get().contains("'query' is required"));
        }

        @Test
        @DisplayName("should return failure when host is blank")
        void execute_withBlankHost_returnsFailure() {
            Core.DatabaseConfig config = new Core.DatabaseConfig(
                "postgresql", "   ", null, "mydb",
                "user", "pass", null,
                "SELECT 1", null, "select", null, null
            );
            DatabaseNode node = new DatabaseNode("core:database", config);

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().get().contains("'host' is required"));
        }
    }

    @Nested
    @DisplayName("buildJdbcUrl")
    class BuildJdbcUrl {

        /**
         * Helper to invoke the private buildJdbcUrl method via reflection.
         */
        private String invokeBuildJdbcUrl(DatabaseNode node, String dbType, String host,
                                          int port, String databaseName, boolean sslEnabled) throws Exception {
            Method method = DatabaseNode.class.getDeclaredMethod(
                "buildJdbcUrl", String.class, String.class, int.class, String.class, boolean.class
            );
            method.setAccessible(true);
            return (String) method.invoke(node, dbType, host, port, databaseName, sslEnabled);
        }

        @Test
        @DisplayName("should build correct PostgreSQL JDBC URL")
        void buildJdbcUrl_postgresql() throws Exception {
            DatabaseNode node = new DatabaseNode("core:database", null);
            String url = invokeBuildJdbcUrl(node, "postgresql", "localhost", 5432, "testdb", false);
            assertEquals("jdbc:postgresql://localhost:5432/testdb", url);
        }

        @Test
        @DisplayName("should build correct PostgreSQL JDBC URL with SSL")
        void buildJdbcUrl_postgresql_ssl() throws Exception {
            DatabaseNode node = new DatabaseNode("core:database", null);
            String url = invokeBuildJdbcUrl(node, "postgresql", "localhost", 5432, "testdb", true);
            assertEquals("jdbc:postgresql://localhost:5432/testdb?ssl=true&sslmode=require", url);
        }

        @Test
        @DisplayName("should build correct MySQL JDBC URL")
        void buildJdbcUrl_mysql() throws Exception {
            DatabaseNode node = new DatabaseNode("core:database", null);
            String url = invokeBuildJdbcUrl(node, "mysql", "mysqlhost", 3306, "appdb", false);
            assertEquals("jdbc:mysql://mysqlhost:3306/appdb", url);
        }

        @Test
        @DisplayName("should build correct MySQL JDBC URL with SSL")
        void buildJdbcUrl_mysql_ssl() throws Exception {
            DatabaseNode node = new DatabaseNode("core:database", null);
            String url = invokeBuildJdbcUrl(node, "mysql", "mysqlhost", 3306, "appdb", true);
            assertEquals("jdbc:mysql://mysqlhost:3306/appdb?useSSL=true&requireSSL=true", url);
        }

        @Test
        @DisplayName("should build correct MSSQL JDBC URL")
        void buildJdbcUrl_mssql() throws Exception {
            DatabaseNode node = new DatabaseNode("core:database", null);
            String url = invokeBuildJdbcUrl(node, "mssql", "sqlserver.example.com", 1433, "warehouse", false);
            assertEquals("jdbc:sqlserver://sqlserver.example.com:1433;databaseName=warehouse", url);
        }

        @Test
        @DisplayName("should build correct MSSQL JDBC URL with SSL")
        void buildJdbcUrl_mssql_ssl() throws Exception {
            DatabaseNode node = new DatabaseNode("core:database", null);
            String url = invokeBuildJdbcUrl(node, "mssql", "sqlserver.example.com", 1433, "warehouse", true);
            assertEquals("jdbc:sqlserver://sqlserver.example.com:1433;databaseName=warehouse;encrypt=true;trustServerCertificate=false", url);
        }

        @Test
        @DisplayName("should throw for unknown dbType")
        void buildJdbcUrl_unknownType() {
            DatabaseNode node = new DatabaseNode("core:database", null);
            assertThrows(Exception.class, () ->
                invokeBuildJdbcUrl(node, "oracle", "host", 1521, "db", false)
            );
        }
    }

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("should build DatabaseNode with builder pattern")
        void builder_pattern_works() {
            Core.DatabaseConfig config = new Core.DatabaseConfig(
                "mysql", "db.example.com", 3307, "production",
                "admin", "secret", true,
                "SELECT * FROM users", List.of("active"), "select", 60000, null
            );

            DatabaseNode node = DatabaseNode.builder()
                .nodeId("core:my_database")
                .databaseConfig(config)
                .build();

            assertEquals("core:my_database", node.getNodeId());
            assertEquals(NodeType.DATABASE, node.getType());
            assertNotNull(node.getConfig());
            assertEquals("mysql", node.getConfig().dbType());
            assertEquals("db.example.com", node.getConfig().host());
            assertEquals(3307, node.getConfig().port());
            assertEquals("production", node.getConfig().databaseName());
            assertEquals("select", node.getConfig().operation());
        }
    }

    // The docs advertise `$1, $2` (PostgreSQL style) but the node ultimately hands
    // the query to a JDBC PreparedStatement, which only understands `?`. We normalize
    // at the node boundary so both syntaxes work without changing the LLM-facing docs
    // (#DB1).
    @Nested
    @DisplayName("normalizePlaceholders() - rewrites $N to ? (#DB1)")
    class NormalizePlaceholdersTests {

        @Test
        void shouldRewriteSingleDollarPlaceholder() {
            assertEquals(
                "SELECT * FROM t WHERE id = ?",
                DatabaseNode.normalizePlaceholders("SELECT * FROM t WHERE id = $1"));
        }

        @Test
        void shouldRewriteMultipleDollarPlaceholders() {
            assertEquals(
                "SELECT * FROM t WHERE a = ? AND b = ? AND c = ?",
                DatabaseNode.normalizePlaceholders("SELECT * FROM t WHERE a = $1 AND b = $2 AND c = $3"));
        }

        @Test
        void shouldLeaveQuestionMarkQueriesUnchanged() {
            String q = "SELECT * FROM t WHERE id = ? AND name = ?";
            assertEquals(q, DatabaseNode.normalizePlaceholders(q));
        }

        @Test
        void shouldPreserveDollarSignInsideStringLiteral() {
            assertEquals(
                "SELECT '$1 is literal' FROM t WHERE id = ?",
                DatabaseNode.normalizePlaceholders("SELECT '$1 is literal' FROM t WHERE id = $1"));
        }

        @Test
        void shouldHandleEscapedQuoteInsideStringLiteral() {
            // 'it''s' is a single SQL literal containing it's; the $1 inside must not be rewritten.
            assertEquals(
                "SELECT 'it''s $1' FROM t WHERE id = ?",
                DatabaseNode.normalizePlaceholders("SELECT 'it''s $1' FROM t WHERE id = $1"));
        }

        @Test
        void shouldPreserveDollarQuotedBody() {
            // Postgres dollar-quoted string: $$ ... $$ - the $1 inside must not be rewritten.
            assertEquals(
                "SELECT $$ $1 literal $$ FROM t WHERE id = ?",
                DatabaseNode.normalizePlaceholders("SELECT $$ $1 literal $$ FROM t WHERE id = $1"));
        }

        @Test
        void shouldPreserveTaggedDollarQuotedBody() {
            assertEquals(
                "SELECT $tag$ $2 keep $tag$ FROM t WHERE id = ?",
                DatabaseNode.normalizePlaceholders("SELECT $tag$ $2 keep $tag$ FROM t WHERE id = $1"));
        }

        @Test
        void shouldHandleNullAndEmpty() {
            assertNull(DatabaseNode.normalizePlaceholders(null));
            assertEquals("", DatabaseNode.normalizePlaceholders(""));
        }

        @Test
        void shouldNotRewriteLoneDollar() {
            assertEquals("SELECT '$' FROM t", DatabaseNode.normalizePlaceholders("SELECT '$' FROM t"));
        }

        @Test
        void shouldHandleMultiDigitPlaceholder() {
            assertEquals(
                "SELECT ? FROM t WHERE id = ?",
                DatabaseNode.normalizePlaceholders("SELECT $10 FROM t WHERE id = $1"));
        }

        // #DB1: double-quoted identifiers must preserve `$N` literally (they're
        // part of the column/table name, not a placeholder).
        @Test
        void shouldPreserveDollarInDoubleQuotedIdentifier() {
            assertEquals(
                "SELECT \"col$1\" FROM t WHERE id = ?",
                DatabaseNode.normalizePlaceholders("SELECT \"col$1\" FROM t WHERE id = $1"));
        }

        @Test
        void shouldPreserveEscapedQuoteInDoubleQuotedIdentifier() {
            // "weird""name$2" is a single identifier containing weird"name$2; $2 must not be rewritten.
            assertEquals(
                "UPDATE t SET \"weird\"\"name$2\" = ? WHERE id = ?",
                DatabaseNode.normalizePlaceholders("UPDATE t SET \"weird\"\"name$2\" = $1 WHERE id = $2"));
        }

        // #DB1: PostgreSQL E-strings use backslash escapes. `E'\'$1'` is a single
        // literal containing `'$1`, not a quote-$1-quote sequence.
        @Test
        void shouldPreserveDollarInEString() {
            assertEquals(
                "SELECT E'$1 is literal' FROM t WHERE id = ?",
                DatabaseNode.normalizePlaceholders("SELECT E'$1 is literal' FROM t WHERE id = $1"));
        }

        @Test
        void shouldPreserveEStringWithBackslashEscape() {
            // E'a\'b $1 c' is one literal: a'b $1 c - the escaped quote must not terminate it.
            assertEquals(
                "SELECT E'a\\'b $1 c' FROM t WHERE id = ?",
                DatabaseNode.normalizePlaceholders("SELECT E'a\\'b $1 c' FROM t WHERE id = $1"));
        }

        @Test
        void shouldPreserveLowercaseEString() {
            assertEquals(
                "SELECT e'$1 literal' FROM t WHERE id = ?",
                DatabaseNode.normalizePlaceholders("SELECT e'$1 literal' FROM t WHERE id = $1"));
        }

        @Test
        void shouldHandleEmptyEString() {
            // E'' is a valid empty E-string; nothing to escape, trailing $1 still rewrites.
            assertEquals(
                "SELECT E'' FROM t WHERE id = ?",
                DatabaseNode.normalizePlaceholders("SELECT E'' FROM t WHERE id = $1"));
        }

        @Test
        void shouldTreatEInsideIdentifierAsNormal() {
            // `someE` is an identifier; the `'x'` after it is a regular quoted literal,
            // not an E-string. `''` is still the escape.
            // Query: SELECT someE'it''s $1' FROM t WHERE id = $1
            // → someE stays (identifier), 'it''s $1' is a plain literal (preserve $1), trailing $1 → ?
            assertEquals(
                "SELECT someE'it''s $1' FROM t WHERE id = ?",
                DatabaseNode.normalizePlaceholders("SELECT someE'it''s $1' FROM t WHERE id = $1"));
        }
    }
}

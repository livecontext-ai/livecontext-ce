package com.apimarketplace.datasource.tools.datasource;

import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.datasource.config.DataSourceAgentDefaultsConfig;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSource;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSourceStatus;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSourceType;
import com.apimarketplace.datasource.services.DataSourceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DataSourceTableModule")
class DataSourceTableModuleTest {

    @Mock private DataSourceService dataSourceService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private DataSourceAgentDefaultsConfig agentDefaults;
    private DataSourceTableModule module;

    private static final String TENANT = "test-tenant";

    @BeforeEach
    void setUp() {
        agentDefaults = new DataSourceAgentDefaultsConfig();
        module = new DataSourceTableModule(dataSourceService, objectMapper, agentDefaults, ceVectorGate());
    }

    private ToolExecutionContext ctx() {
        return ToolExecutionContext.of(TENANT);
    }

    private DataSource fakeDs(Long id, String name) {
        return new DataSource(id, TENANT, name, "desc", DataSourceType.INLINE, Map.of(),
                DataSourceStatus.ACTIVE, null, null, TENANT, null, null, null, null, null, null);
    }

    // ==================== allow-list enforcement (credentials channel) ====================

    @Nested
    @DisplayName("allow-list enforcement (credentials channel - grant round-trip)")
    class AllowListTests {

        private ToolExecutionContext ctxCreds(Map<String, Object> credentials) {
            return new ToolExecutionContext(TENANT, credentials, Map.of(), Set.of(), null, null, null, null);
        }

        @Test
        @DisplayName("get on a table_id NOT in allowedTableIds (read from CREDENTIALS, not variables) is denied")
        void getOutOfCredentialsAllowListDenied() {
            Optional<ToolExecutionResult> res = module.execute("get", Map.of("table_id", 99), TENANT,
                    ctxCreds(Map.of("allowedTableIds", List.of("5"))));

            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
            verifyNoInteractions(dataSourceService);
        }

        @Test
        @DisplayName("create appends the new id to the SAME credentials allow-list the gate reads (grant round-trips, not a no-op)")
        void createGrantRoundTripsViaCredentials() {
            when(dataSourceService.createDataSource(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(fakeDs(7L, "Users"));

            // A restricted agent's allow-list lives in credentials (the channel grantCreatedResource writes).
            Map<String, Object> creds = new HashMap<>();
            creds.put("allowedTableIds", new ArrayList<>(List.of("5")));

            Map<String, Object> params = new HashMap<>();
            params.put("name", "Users");
            params.put("data", List.of(Map.of("name", "Alice")));

            Optional<ToolExecutionResult> res = module.execute("create", params, TENANT, ctxCreds(creds));
            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();

            // The freshly-created id 7 was appended to the agent's own allow-list - pre-fix the
            // grant wrote credentials while the gate read variables, so this stayed ["5"] (no-op)
            // and the agent was locked out of the table it just created.
            @SuppressWarnings("unchecked")
            List<String> allowed = (List<String>) creds.get("allowedTableIds");
            assertThat(allowed).contains("7");
        }
    }

    /** Table owned by an arbitrary tenant/org, used to exercise the strict-scope gate. */
    private DataSource fakeDsScoped(Long id, String name, String tenant, String org) {
        return new DataSource(id, tenant, name, "desc", DataSourceType.INLINE, Map.of(),
                DataSourceStatus.ACTIVE, null, null, tenant, null, null, null, null, null, org);
    }

    /** Caller context pinned to an org workspace (orgId at record position 7). */
    private ToolExecutionContext ctxWithOrg(String orgId) {
        return new ToolExecutionContext(TENANT, Map.of(), Map.of(), Set.of(), null, null, orgId, null);
    }

    // ==================== canHandle ====================

    @Test
    @DisplayName("canHandle should accept table-level operations")
    void canHandle() {
        assertThat(module.canHandle("create")).isTrue();
        assertThat(module.canHandle("get")).isTrue();
        assertThat(module.canHandle("list")).isTrue();
        assertThat(module.canHandle("update")).isTrue();
        assertThat(module.canHandle("delete")).isTrue();
        assertThat(module.canHandle("help")).isTrue();
        assertThat(module.canHandle("query_rows")).isFalse();
    }

    // ==================== create ====================

    @Nested
    @DisplayName("create")
    class CreateTests {

        @Test
        @DisplayName("Should create table with data")
        void shouldCreateWithData() {
            when(dataSourceService.createDataSource(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(fakeDs(1L, "Users"));

            Map<String, Object> params = new HashMap<>();
            params.put("name", "Users");
            params.put("data", List.of(Map.of("name", "Alice", "age", 30)));

            Optional<ToolExecutionResult> res = module.execute("create", params, TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) res.get().data();
            assertThat(data.get("id")).isEqualTo(1L);
            assertThat(data.get("status")).isEqualTo("CREATED");
        }

        @Test
        @DisplayName("Should create schema-only table with columns")
        void shouldCreateSchemaOnlyWithColumns() {
            when(dataSourceService.createDataSource(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(fakeDs(2L, "Tasks"));

            Map<String, Object> params = new HashMap<>();
            params.put("name", "Tasks");
            params.put("columns", List.of(
                    Map.of("name", "title", "type", "text"),
                    Map.of("name", "done", "type", "checkbox"),
                    Map.of("name", "rank", "type", "number")
            ));

            Optional<ToolExecutionResult> res = module.execute("create", params, TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();

            // Verify createDataSource was called with a non-null mappingSpec
            verify(dataSourceService).createDataSource(
                    eq(TENANT), eq("Tasks"), any(), eq(DataSourceType.INLINE), any(), anyList(), eq(TENANT), argThat(spec -> {
                        if (spec == null) return false;
                        @SuppressWarnings("unchecked")
                        var mappingSpec = (Map<String, ?>) spec;
                        return mappingSpec.containsKey("title") && mappingSpec.containsKey("done") && mappingSpec.containsKey("rank");
                    }), isNull());
        }

        @Test
        @DisplayName("Should create with double-serialized columns")
        void shouldCreateWithDoubleSerializedColumns() {
            when(dataSourceService.createDataSource(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(fakeDs(3L, "Contacts"));

            Map<String, Object> params = new HashMap<>();
            params.put("name", "Contacts");
            params.put("columns", List.of(
                    "{\"name\": \"email\", \"type\": \"email\"}",
                    "{\"name\": \"phone\", \"type\": \"phone\"}",
                    "{\"name\": \"website\", \"type\": \"url\"}"
            ));

            Optional<ToolExecutionResult> res = module.execute("create", params, TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();

            // Verify mappingSpec has 3 entries
            verify(dataSourceService).createDataSource(
                    any(), any(), any(), any(), any(), any(), any(), argThat(spec -> {
                        if (spec == null) return false;
                        @SuppressWarnings("unchecked")
                        var mappingSpec = (Map<String, ?>) spec;
                        return mappingSpec.size() == 3;
                    }), isNull());
        }

        @Test
        @DisplayName("Should create with all 14 column types")
        void shouldCreateWithAll14Types() {
            when(dataSourceService.createDataSource(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(fakeDs(4L, "AllTypes"));

            // select/multi_select require display.options - see ToolParameterUtils#validateSelectOptions.
            List<Map<String, Object>> sampleOptions = List.of(
                    Map.of("label", "A", "value", "a"),
                    Map.of("label", "B", "value", "b")
            );
            Map<String, Object> params = new HashMap<>();
            params.put("name", "AllTypes");
            params.put("columns", List.of(
                    Map.of("name", "c1", "type", "text"),
                    Map.of("name", "c2", "type", "number"),
                    Map.of("name", "c3", "type", "date"),
                    Map.of("name", "c4", "type", "checkbox"),
                    Map.of("name", "c5", "type", "select", "display", Map.of("options", sampleOptions)),
                    Map.of("name", "c6", "type", "multi_select", "display", Map.of("options", sampleOptions)),
                    Map.of("name", "c7", "type", "rating"),
                    Map.of("name", "c8", "type", "sentiment"),
                    Map.of("name", "c9", "type", "progress"),
                    Map.of("name", "c10", "type", "file"),
                    Map.of("name", "c11", "type", "image"),
                    Map.of("name", "c12", "type", "email"),
                    Map.of("name", "c13", "type", "phone"),
                    Map.of("name", "c14", "type", "url")
            ));

            Optional<ToolExecutionResult> res = module.execute("create", params, TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();

            verify(dataSourceService).createDataSource(
                    any(), any(), any(), any(), any(), any(), any(), argThat(spec -> {
                        if (spec == null) return false;
                        @SuppressWarnings("unchecked")
                        var mappingSpec = (Map<String, ?>) spec;
                        return mappingSpec.size() == 14;
                    }), isNull());
        }

        @Test
        @DisplayName("Should preserve display config in mappingSpec")
        void shouldPreserveDisplayConfig() {
            when(dataSourceService.createDataSource(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(fakeDs(5L, "WithDisplay"));

            Map<String, Object> selectDisplay = Map.of("options", List.of(
                    Map.of("label", "Active", "value", "A", "color", "#22c55e")
            ));

            Map<String, Object> params = new HashMap<>();
            params.put("name", "WithDisplay");
            params.put("columns", List.of(
                    Map.of("name", "status", "type", "select", "display", selectDisplay)
            ));

            Optional<ToolExecutionResult> res = module.execute("create", params, TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();

            verify(dataSourceService).createDataSource(
                    any(), any(), any(), any(), any(), any(), any(), argThat(spec -> {
                        if (spec == null) return false;
                        @SuppressWarnings("unchecked")
                        var mappingSpec = (Map<String, ?>) spec;
                        return mappingSpec.containsKey("status");
                    }), isNull());
        }

        @Test
        @DisplayName("Should fail without name")
        void shouldFailWithoutName() {
            Map<String, Object> params = Map.of("data", List.of(Map.of("x", 1)));
            Optional<ToolExecutionResult> res = module.execute("create", params, TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
        }

        @Test
        @DisplayName("Should fail without data or columns")
        void shouldFailWithoutDataOrColumns() {
            Map<String, Object> params = Map.of("name", "Test");
            Optional<ToolExecutionResult> res = module.execute("create", params, TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
        }

        @Test
        @DisplayName("Should propagate createDataSource exception")
        void shouldPropagateException() {
            when(dataSourceService.createDataSource(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("Create failed: sourceConfig null"));

            Map<String, Object> params = new HashMap<>();
            params.put("name", "Test");
            params.put("data", List.of(Map.of("a", 1)));

            Optional<ToolExecutionResult> res = module.execute("create", params, TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().error()).contains("sourceConfig null");
        }

        @Test
        @DisplayName("Should reject reserved column name 'priority' at create time (B1)")
        void shouldRejectReservedColumnName() {
            Map<String, Object> params = new HashMap<>();
            params.put("name", "T");
            params.put("columns", List.of(
                    Map.of("name", "title", "type", "text"),
                    Map.of("name", "priority", "type", "select")
            ));

            Optional<ToolExecutionResult> res = module.execute("create", params, TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().errorCode()).isEqualTo(ToolErrorCode.VALIDATION_ERROR);
            assertThat(res.get().error()).contains("reserved");
            // Confirm we never hit the datasource service - fail-fast
            verify(dataSourceService, never()).createDataSource(
                    any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should reject invalid column type at create time (B2)")
        void shouldRejectInvalidColumnType() {
            Map<String, Object> params = new HashMap<>();
            params.put("name", "T");
            params.put("columns", List.of(Map.of("name", "c", "type", "totally_invalid_xyz")));

            Optional<ToolExecutionResult> res = module.execute("create", params, TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().errorCode()).isEqualTo(ToolErrorCode.VALIDATION_ERROR);
            assertThat(res.get().error()).contains("Invalid column type");
            verify(dataSourceService, never()).createDataSource(
                    any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should reject reserved column name when data[] also present (B1)")
        void shouldRejectReservedNameWithData() {
            Map<String, Object> params = new HashMap<>();
            params.put("name", "T");
            params.put("data", List.of(Map.of("title", "hi")));
            params.put("columns", List.of(
                    Map.of("name", "title", "type", "text"),
                    Map.of("name", "row_index", "type", "number")
            ));

            Optional<ToolExecutionResult> res = module.execute("create", params, TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().errorCode()).isEqualTo(ToolErrorCode.VALIDATION_ERROR);
            verify(dataSourceService, never()).createDataSource(
                    any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should reject reserved name in data-only create (B1 data-only path)")
        void shouldRejectReservedNameInDataOnlyCreate() {
            // No columns[] - column names are inferred from row keys, so reserved
            // names would otherwise slip past validation entirely.
            Map<String, Object> params = new HashMap<>();
            params.put("name", "T");
            params.put("data", List.of(Map.of("id", 999, "title", "hi")));

            Optional<ToolExecutionResult> res = module.execute("create", params, TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().errorCode()).isEqualTo(ToolErrorCode.VALIDATION_ERROR);
            assertThat(res.get().error()).contains("reserved");
            verify(dataSourceService, never()).createDataSource(
                    any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Data-only create scans all rows (not just the first) for reserved keys")
        void dataOnlyCreateScansAllRows() {
            Map<String, Object> params = new HashMap<>();
            params.put("name", "T");
            params.put("data", List.of(
                    Map.of("title", "row1"),
                    Map.of("title", "row2", "priority", "hi") // reserved key on second row
            ));

            Optional<ToolExecutionResult> res = module.execute("create", params, TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().errorCode()).isEqualTo(ToolErrorCode.VALIDATION_ERROR);
            assertThat(res.get().error()).contains("reserved");
            verify(dataSourceService, never()).createDataSource(
                    any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Data-only create strips data. prefix before reserved check")
        void dataOnlyCreateStripsDataPrefix() {
            Map<String, Object> params = new HashMap<>();
            params.put("name", "T");
            // data.tenant_id → tenant_id (reserved) after sanitization
            params.put("data", List.of(Map.of("data.tenant_id", "abc")));

            Optional<ToolExecutionResult> res = module.execute("create", params, TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().errorCode()).isEqualTo(ToolErrorCode.VALIDATION_ERROR);
            assertThat(res.get().error()).contains("reserved");
            verify(dataSourceService, never()).createDataSource(
                    any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("columns[] + data[] with reserved-key in data still rejected (B1 sibling)")
        void createWithColumnsAndDataRejectsReservedKeyInData() {
            // Declared columns are clean, but data[] sneaks in a reserved key.
            Map<String, Object> params = new HashMap<>();
            params.put("name", "T");
            params.put("columns", List.of(Map.of("name", "title", "type", "text")));
            params.put("data", List.of(Map.of("title", "ok", "priority", "hi")));

            Optional<ToolExecutionResult> res = module.execute("create", params, TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().errorCode()).isEqualTo(ToolErrorCode.VALIDATION_ERROR);
            assertThat(res.get().error()).contains("reserved");
            verify(dataSourceService, never()).createDataSource(
                    any(), any(), any(), any(), any(), any(), any(), any(), any());
        }
    }

    // ==================== get ====================

    @Nested
    @DisplayName("get")
    class GetTests {

        @Test
        @DisplayName("Should get table by table_id")
        void shouldGetByTableId() {
            when(dataSourceService.getDataSource(5L)).thenReturn(Optional.of(fakeDs(5L, "MyTable")));

            Optional<ToolExecutionResult> res = module.execute("get", Map.of("table_id", 5), TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) res.get().data();
            assertThat(data.get("id")).isEqualTo(5L);
            assertThat(data.get("name")).isEqualTo("MyTable");
        }

        @Test
        @DisplayName("Should fail without id")
        void shouldFailWithoutId() {
            Optional<ToolExecutionResult> res = module.execute("get", Map.of(), TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
        }

        @Test
        @DisplayName("Should return not found for unknown id")
        void shouldReturnNotFound() {
            when(dataSourceService.getDataSource(999L)).thenReturn(Optional.empty());

            Optional<ToolExecutionResult> res = module.execute("get", Map.of("table_id", 999), TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().errorCode()).isEqualTo(ToolErrorCode.DATASOURCE_NOT_FOUND);
        }

        @Test
        @DisplayName("Numeric allowedTableIds permits get of a matching table (MCP stores Integer ids), regression")
        void numericAllowlistPermitsMatchingGet() {
            // Regression: an agent created via MCP stores `tables:[209]` as a List<Integer>.
            // Pre-fix, getAllowedTableIds returned the raw List<Integer> and
            // `.contains(String.valueOf(209))` was always false, so PERMISSION_DENIED
            // "This table is not in your approved table list." getAllowedTableIds now
            // stringifies the list so a matching numeric id is allowed. The allowlist
            // travels on the CREDENTIALS channel (position 2), matching TableToolAccess.
            when(dataSourceService.getDataSource(209L)).thenReturn(Optional.of(fakeDs(209L, "Tickets")));
            ToolExecutionContext context = new ToolExecutionContext(
                TENANT, Map.of("allowedTableIds", List.of(209, 42)), Map.of(),
                Set.of(), null, null, null, null);

            Optional<ToolExecutionResult> res = module.execute("get", Map.of("table_id", 209), TENANT, context);

            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();
        }

        @Test
        @DisplayName("Strict scope: get of another tenant's table (mode=all, no allowlist) returns NOT_FOUND, cross-tenant gap #4")
        void getOfCrossTenantTableIsDeniedAsNotFound() {
            // Exploit shape: agent in mode=all (allowlist null) guesses a numeric id owned by
            // another personal-workspace user. getDataSource is an unscoped findById, so without
            // the ScopeGuard floor this leaked the other tenant's table. Denied as NOT_FOUND
            // (not PERMISSION_DENIED) so the row's existence is not leaked across workspaces.
            when(dataSourceService.getDataSource(777L))
                    .thenReturn(Optional.of(fakeDsScoped(777L, "OtherUsersTable", "other-tenant", null)));

            Optional<ToolExecutionResult> res = module.execute("get", Map.of("table_id", 777), TENANT, ctx());

            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().errorCode()).isEqualTo(ToolErrorCode.DATASOURCE_NOT_FOUND);
        }

        @Test
        @DisplayName("Strict scope: get of a table in a DIFFERENT org bucket returns NOT_FOUND (shared personal-org bleed)")
        void getOfCrossOrgTableIsDeniedAsNotFound() {
            // Caller is active in org "orgA"; the table is tagged "orgB". Same user id could
            // own both, but strict scope keys on the active workspace org, not ownership.
            when(dataSourceService.getDataSource(778L))
                    .thenReturn(Optional.of(fakeDsScoped(778L, "OrgBTable", TENANT, "orgB")));

            Optional<ToolExecutionResult> res = module.execute("get", Map.of("table_id", 778), TENANT, ctxWithOrg("orgA"));

            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().errorCode()).isEqualTo(ToolErrorCode.DATASOURCE_NOT_FOUND);
        }

        @Test
        @DisplayName("Numeric allowedTableIds still BLOCKS a non-listed table (allowlist not weakened)")
        void numericAllowlistStillBlocksNonListedTable() {
            // The stringify fix must not over-permit: a table outside the (numeric) allow-list
            // is still denied, proving the allowlist gate stays enforced. Allowlist on the
            // CREDENTIALS channel (position 2), matching TableToolAccess.allowedTableIds.
            ToolExecutionContext context = new ToolExecutionContext(
                TENANT, Map.of("allowedTableIds", List.of(42)), Map.of(),
                Set.of(), null, null, null, null);

            Optional<ToolExecutionResult> res = module.execute("get", Map.of("table_id", 209), TENANT, context);

            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
        }
    }

    // ==================== list ====================

    @Nested
    @DisplayName("list")
    class ListTests {

        /** Context carrying a conversationId so the loop-detection guard is active. */
        private ToolExecutionContext ctxWithConversation(String conversationId) {
            return new ToolExecutionContext(
                TENANT, Map.of(), Map.of("conversationId", conversationId),
                Set.of(), null, null, null, null);
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> dataOf(Optional<ToolExecutionResult> res) {
            assertThat(res).isPresent();
            return (Map<String, Object>) res.get().data();
        }

        private List<DataSource> nTables(int n) {
            List<DataSource> all = new ArrayList<>();
            for (long i = 1; i <= n; i++) all.add(fakeDs(i, "T" + i));
            return all;
        }

        @Test
        @DisplayName("Should list tables")
        void shouldListTables() {
            when(dataSourceService.getDataSources(TENANT, null, null))
                    .thenReturn(List.of(fakeDs(1L, "T1"), fakeDs(2L, "T2")));

            Optional<ToolExecutionResult> res = module.execute("list", Map.of(), TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) res.get().data();
            assertThat(data.get("count")).isEqualTo(2);
            assertThat(data.get("total")).isEqualTo(2);
        }

        private DataSource dsWithDesc(long id, String name, String description) {
            return new DataSource(id, TENANT, name, description, DataSourceType.INLINE, Map.of(),
                    DataSourceStatus.ACTIVE, null, null, TENANT, null, null, null, null, null, null);
        }

        @Test
        @DisplayName("query filters tables by name OR description (case-insensitive) before pagination")
        @SuppressWarnings("unchecked")
        void queryFiltersByNameAndDescription() {
            when(dataSourceService.getDataSources(TENANT, null, null)).thenReturn(List.of(
                    dsWithDesc(1L, "Invoices", "totals"),
                    dsWithDesc(2L, "Orders", "handles invoices too"),
                    dsWithDesc(3L, "Weather", "forecasts")));

            Map<String, Object> data = dataOf(module.execute("list", Map.of("query", "invoice"), TENANT, ctx()));
            assertThat(data.get("count")).isEqualTo(2);
            assertThat(data.get("total")).isEqualTo(2);
            List<Map<String, Object>> tables = (List<Map<String, Object>>) data.get("dataSources");
            assertThat(tables).extracting(m -> m.get("id")).containsExactlyInAnyOrder(1L, 2L);
        }

        @Test
        @DisplayName("Loop guard: a filtered re-list at the same offset is NOT mistaken for a repeat of the unfiltered page")
        @SuppressWarnings("unchecked")
        void queryIsPartOfPageKey() {
            when(dataSourceService.getDataSources(TENANT, null, null))
                    .thenReturn(List.of(fakeDs(1L, "Invoices"), fakeDs(2L, "Orders")));
            ToolExecutionContext context = ctxWithConversation("conv-query-key");

            Map<String, Object> unfiltered = dataOf(
                    module.execute("list", Map.of("offset", 0, "limit", 25), TENANT, context));
            assertThat(unfiltered.get("status")).isEqualTo("OK");

            // Same offset+limit but WITH a query -> different page key -> not ALREADY_LISTED.
            Map<String, Object> filtered = dataOf(
                    module.execute("list", Map.of("offset", 0, "limit", 25, "query", "invoice"), TENANT, context));
            assertThat(filtered.get("status")).isEqualTo("OK");
            assertThat(filtered.get("count")).isEqualTo(1);
        }

        @Test
        @DisplayName("Pagination: advancing offset within the cooldown is NOT blocked (prod bug 2026-06-05)")
        void paginationWithNewOffsetIsNotBlocked() {
            // 28 tables: page one (limit=25) shows 25, the agent must be able to reach
            // the remaining 3 at offset=25 - exactly what ALREADY_LISTED was killing.
            when(dataSourceService.getDataSources(TENANT, null, null)).thenReturn(nTables(28));

            ToolExecutionContext context = ctxWithConversation("conv-paginate");

            // Page 1
            Map<String, Object> page1 = dataOf(
                module.execute("list", Map.of("offset", 0, "limit", 25), TENANT, context));
            assertThat(page1.get("status")).isEqualTo("OK");
            assertThat(page1.get("count")).isEqualTo(25);
            assertThat(page1.get("hasMore")).isEqualTo(true);

            // Page 2 - same conversation, immediately after (within the 30s cooldown),
            // new offset. Must return the remaining rows, NOT ALREADY_LISTED.
            Map<String, Object> page2 = dataOf(
                module.execute("list", Map.of("offset", 25, "limit", 25), TENANT, context));
            assertThat(page2.get("status")).isEqualTo("OK");
            assertThat(page2.get("count")).isEqualTo(3);
            assertThat(page2.get("hasMore")).isEqualTo(false);

            // The service was actually queried for both pages (no short-circuit).
            verify(dataSourceService, times(2)).getDataSources(TENANT, null, null);
        }

        @Test
        @DisplayName("Loop guard: repeating the SAME page within the cooldown returns ALREADY_LISTED")
        void repeatingSamePageReturnsAlreadyListed() {
            when(dataSourceService.getDataSources(TENANT, null, null))
                    .thenReturn(List.of(fakeDs(1L, "T1"), fakeDs(2L, "T2")));

            ToolExecutionContext context = ctxWithConversation("conv-loop");

            Map<String, Object> first = dataOf(
                module.execute("list", Map.of("offset", 0, "limit", 25), TENANT, context));
            assertThat(first.get("status")).isEqualTo("OK");

            Map<String, Object> second = dataOf(
                module.execute("list", Map.of("offset", 0, "limit", 25), TENANT, context));
            assertThat(second.get("status")).isEqualTo("ALREADY_LISTED");

            // Guard short-circuits before hitting the service on the repeat.
            verify(dataSourceService, times(1)).getDataSources(TENANT, null, null);
        }

        @Test
        @DisplayName("Loop guard is per-conversation: a different conversation lists the same page freely")
        void differentConversationIsNotBlocked() {
            when(dataSourceService.getDataSources(TENANT, null, null))
                    .thenReturn(List.of(fakeDs(1L, "T1"), fakeDs(2L, "T2")));

            Map<String, Object> convA = dataOf(
                module.execute("list", Map.of("offset", 0, "limit", 25), TENANT, ctxWithConversation("conv-a")));
            assertThat(convA.get("status")).isEqualTo("OK");

            Map<String, Object> convB = dataOf(
                module.execute("list", Map.of("offset", 0, "limit", 25), TENANT, ctxWithConversation("conv-b")));
            assertThat(convB.get("status")).isEqualTo("OK");
        }

        @Test
        @DisplayName("No conversationId: repeated lists are never loop-guarded")
        void withoutConversationIdNeverBlocked() {
            when(dataSourceService.getDataSources(TENANT, null, null))
                    .thenReturn(List.of(fakeDs(1L, "T1"), fakeDs(2L, "T2")));

            Map<String, Object> first = dataOf(module.execute("list", Map.of(), TENANT, ctx()));
            Map<String, Object> second = dataOf(module.execute("list", Map.of(), TENANT, ctx()));
            assertThat(first.get("status")).isEqualTo("OK");
            assertThat(second.get("status")).isEqualTo("OK");
        }

        @Test
        @DisplayName("Pagination: changing only the page size (limit) is a new page, not a re-list")
        void changingOnlyLimitIsANewPage() {
            when(dataSourceService.getDataSources(TENANT, null, null)).thenReturn(nTables(40));
            ToolExecutionContext context = ctxWithConversation("conv-limit");

            Map<String, Object> firstPageSize = dataOf(
                module.execute("list", Map.of("offset", 0, "limit", 25), TENANT, context));
            assertThat(firstPageSize.get("status")).isEqualTo("OK");

            // Same offset, wider page → genuinely different result set → must not be blocked.
            Map<String, Object> widerPage = dataOf(
                module.execute("list", Map.of("offset", 0, "limit", 40), TENANT, context));
            assertThat(widerPage.get("status")).isEqualTo("OK");
            assertThat(widerPage.get("count")).isEqualTo(40);
        }

        @Test
        @DisplayName("Loop guard normalizes param types: string offset/limit collide with their integer form")
        void stringAndIntegerParamsShareTheSameKey() {
            when(dataSourceService.getDataSources(TENANT, null, null))
                    .thenReturn(List.of(fakeDs(1L, "T1")));
            ToolExecutionContext context = ctxWithConversation("conv-coerce");

            Map<String, Object> withInts = dataOf(
                module.execute("list", Map.of("offset", 0, "limit", 25), TENANT, context));
            assertThat(withInts.get("status")).isEqualTo("OK");

            // Same page expressed as strings - getIntParam coerces before the key is built,
            // so this is the SAME page and must be caught by the loop guard.
            Map<String, Object> withStrings = dataOf(
                module.execute("list", Map.of("offset", "0", "limit", "25"), TENANT, context));
            assertThat(withStrings.get("status")).isEqualTo("ALREADY_LISTED");
        }

        @Test
        @DisplayName("Loop guard: re-fetching an earlier page within the cooldown is still blocked")
        void refetchingEarlierPageIsStillBlocked() {
            when(dataSourceService.getDataSources(TENANT, null, null)).thenReturn(nTables(28));
            ToolExecutionContext context = ctxWithConversation("conv-back");

            assertThat(dataOf(module.execute("list", Map.of("offset", 0, "limit", 25), TENANT, context)).get("status"))
                    .isEqualTo("OK");
            assertThat(dataOf(module.execute("list", Map.of("offset", 25, "limit", 25), TENANT, context)).get("status"))
                    .isEqualTo("OK");
            // Going back to page 1 within the cooldown is a redundant re-list → guarded.
            assertThat(dataOf(module.execute("list", Map.of("offset", 0, "limit", 25), TENANT, context)).get("status"))
                    .isEqualTo("ALREADY_LISTED");
        }

        @Test
        @DisplayName("Cooldown expiry: the same page is listable again once the cooldown elapses")
        void samePageIsListableAgainAfterCooldown() {
            when(dataSourceService.getDataSources(TENANT, null, null))
                    .thenReturn(List.of(fakeDs(1L, "T1")));
            AtomicLong nowMs = new AtomicLong(1_000_000L);
            module.setClock(nowMs::get);
            ToolExecutionContext context = ctxWithConversation("conv-expiry");

            assertThat(dataOf(module.execute("list", Map.of("offset", 0, "limit", 25), TENANT, context)).get("status"))
                    .isEqualTo("OK");
            // Immediately again → blocked.
            assertThat(dataOf(module.execute("list", Map.of("offset", 0, "limit", 25), TENANT, context)).get("status"))
                    .isEqualTo("ALREADY_LISTED");
            // Advance past the 30s cooldown → listable again.
            nowMs.addAndGet(31_000L);
            assertThat(dataOf(module.execute("list", Map.of("offset", 0, "limit", 25), TENANT, context)).get("status"))
                    .isEqualTo("OK");
        }

        @Test
        @DisplayName("Loop-detection cache stays bounded: entries past the cooldown are evicted")
        void cacheIsBoundedByCooldownEviction() {
            when(dataSourceService.getDataSources(TENANT, null, null))
                    .thenReturn(List.of(fakeDs(1L, "T1")));
            AtomicLong nowMs = new AtomicLong(1_000_000L);
            module.setClock(nowMs::get);
            ToolExecutionContext context = ctxWithConversation("conv-evict");

            // Page through 5 distinct offsets → 5 cache entries accumulate.
            for (int off = 0; off < 5; off++) {
                module.execute("list", Map.of("offset", off * 25, "limit", 25), TENANT, context);
            }
            assertThat(module.recentListCacheSize()).isEqualTo(5);

            // After the cooldown, the next list evicts all 5 stale entries; only it remains.
            nowMs.addAndGet(31_000L);
            module.execute("list", Map.of("offset", 999, "limit", 25), TENANT, context);
            assertThat(module.recentListCacheSize()).isEqualTo(1);
        }

        @Test
        @DisplayName("Cooldown boundary: an entry aged exactly the cooldown is evicted and listable, not blocked")
        void entryExactlyAtCooldownIsEvictedAndListable() {
            when(dataSourceService.getDataSources(TENANT, null, null))
                    .thenReturn(List.of(fakeDs(1L, "T1")));
            AtomicLong nowMs = new AtomicLong(1_000_000L);
            module.setClock(nowMs::get);
            ToolExecutionContext context = ctxWithConversation("conv-boundary");

            assertThat(dataOf(module.execute("list", Map.of("offset", 0, "limit", 25), TENANT, context)).get("status"))
                    .isEqualTo("OK");
            assertThat(module.recentListCacheSize()).isEqualTo(1);

            // Advance by EXACTLY LIST_COOLDOWN_MS (30_000): age == cooldown → block check
            // (age < cooldown) is false and eviction (age >= cooldown) is true, so the stale
            // entry is dropped and the page lists again, replaced by a single fresh entry.
            nowMs.addAndGet(30_000L);
            assertThat(dataOf(module.execute("list", Map.of("offset", 0, "limit", 25), TENANT, context)).get("status"))
                    .isEqualTo("OK");
            assertThat(module.recentListCacheSize()).isEqualTo(1);
        }
    }

    // ==================== update ====================

    @Nested
    @DisplayName("update")
    class UpdateTests {

        @Test
        @DisplayName("Should update with table_id")
        void shouldUpdateWithTableId() {
            when(dataSourceService.getDataSource(3L)).thenReturn(Optional.of(fakeDs(3L, "Old")));
            when(dataSourceService.updateDataSource(eq(3L), eq("New"), any(), any()))
                    .thenReturn(fakeDs(3L, "New"));

            Map<String, Object> params = Map.of("table_id", 3, "name", "New");
            Optional<ToolExecutionResult> res = module.execute("update", params, TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) res.get().data();
            assertThat(data.get("name")).isEqualTo("New");
        }

        @Test
        @DisplayName("Should fail without id")
        void shouldFailWithoutId() {
            Optional<ToolExecutionResult> res = module.execute("update", Map.of("name", "x"), TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
        }

        @Test
        @DisplayName("Strict scope: update of another tenant's table returns NOT_FOUND and never mutates, cross-tenant gap #4")
        void updateOfCrossTenantTableIsDeniedBeforeMutation() {
            when(dataSourceService.getDataSource(776L))
                    .thenReturn(Optional.of(fakeDsScoped(776L, "OtherUsersTable", "other-tenant", null)));

            Optional<ToolExecutionResult> res = module.execute(
                    "update", Map.of("table_id", 776, "name", "Hacked"), TENANT, ctx());

            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().errorCode()).isEqualTo(ToolErrorCode.DATASOURCE_NOT_FOUND);
            // The scope gate fires before any write, the other tenant's row is untouched.
            verify(dataSourceService, never()).updateDataSource(anyLong(), any(), any(), any());
        }

        @Test
        @DisplayName("Strict scope: update of a table in a DIFFERENT org bucket returns NOT_FOUND and never mutates, cross-org gap #4")
        void updateOfCrossOrgTableIsDeniedBeforeMutation() {
            // Caller active in "orgA"; table tagged "orgB" but owned by the same user id,
            // strict scope keys on the active workspace org, not ownership, so the write is denied.
            when(dataSourceService.getDataSource(774L))
                    .thenReturn(Optional.of(fakeDsScoped(774L, "OrgBTable", TENANT, "orgB")));

            Optional<ToolExecutionResult> res = module.execute(
                    "update", Map.of("table_id", 774, "name", "Hacked"), TENANT, ctxWithOrg("orgA"));

            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().errorCode()).isEqualTo(ToolErrorCode.DATASOURCE_NOT_FOUND);
            verify(dataSourceService, never()).updateDataSource(anyLong(), any(), any(), any());
        }
    }

    // ==================== delete ====================

    @Nested
    @DisplayName("delete")
    class DeleteTests {

        @Test
        @DisplayName("Should delete with table_id")
        void shouldDeleteWithTableId() {
            when(dataSourceService.getDataSource(4L)).thenReturn(Optional.of(fakeDs(4L, "ToDelete")));

            Optional<ToolExecutionResult> res = module.execute("delete", Map.of("table_id", 4), TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();
            verify(dataSourceService).deleteDataSource(4L);
        }

        @Test
        @DisplayName("Should fail for non-existent table")
        void shouldFailForNonExistent() {
            when(dataSourceService.getDataSource(999L)).thenReturn(Optional.empty());

            Optional<ToolExecutionResult> res = module.execute("delete", Map.of("table_id", 999), TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
        }

        @Test
        @DisplayName("Strict scope: delete of another tenant's table returns NOT_FOUND and never deletes, cross-tenant gap #4")
        void deleteOfCrossTenantTableIsDeniedBeforeDeletion() {
            when(dataSourceService.getDataSource(775L))
                    .thenReturn(Optional.of(fakeDsScoped(775L, "OtherUsersTable", "other-tenant", null)));

            Optional<ToolExecutionResult> res = module.execute("delete", Map.of("table_id", 775), TENANT, ctx());

            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().errorCode()).isEqualTo(ToolErrorCode.DATASOURCE_NOT_FOUND);
            // The scope gate fires before the delete, the other tenant's row survives.
            verify(dataSourceService, never()).deleteDataSource(anyLong());
        }

        @Test
        @DisplayName("Strict scope: delete of a table in a DIFFERENT org bucket returns NOT_FOUND and never deletes, cross-org gap #4")
        void deleteOfCrossOrgTableIsDeniedBeforeDeletion() {
            // Caller active in "orgA"; table tagged "orgB" though owned by the same user id.
            when(dataSourceService.getDataSource(773L))
                    .thenReturn(Optional.of(fakeDsScoped(773L, "OrgBTable", TENANT, "orgB")));

            Optional<ToolExecutionResult> res = module.execute("delete", Map.of("table_id", 773), TENANT, ctxWithOrg("orgA"));

            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().errorCode()).isEqualTo(ToolErrorCode.DATASOURCE_NOT_FOUND);
            verify(dataSourceService, never()).deleteDataSource(anyLong());
        }
    }

    // ==================== help ====================

    @Test
    @DisplayName("help should list all 14 column types")
    void helpShouldList14Types() {
        Optional<ToolExecutionResult> res = module.execute("help", Map.of(), TENANT, ctx());
        assertThat(res).isPresent();
        assertThat(res.get().success()).isTrue();

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) res.get().data();
        @SuppressWarnings("unchecked")
        Map<String, String> types = (Map<String, String>) data.get("columnTypes");
        assertThat(types).hasSize(15);
        assertThat(types).containsKeys("text", "number", "date", "checkbox", "select",
                "multi_select", "rating", "sentiment", "progress", "file", "image",
                "email", "phone", "url", "vector");
    }

    @Test
    @DisplayName("help.columnNaming.reserved must list every RESERVED_COLUMN_NAMES entry verbatim")
    void helpReservedListMatchesConstant() {
        Optional<ToolExecutionResult> res = module.execute("help", Map.of(), TENANT, ctx());
        assertThat(res).isPresent();

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) res.get().data();
        @SuppressWarnings("unchecked")
        Map<String, String> naming = (Map<String, String>) data.get("columnNaming");
        String reserved = naming.get("reserved");

        assertThat(reserved).isNotNull();
        for (String name : ToolParameterUtils.RESERVED_COLUMN_NAMES) {
            assertThat(reserved).contains(name);
        }
    }

    // ==========================================================================
    // V100: unified per-resource per-turn cap resolution for table creation
    // ==========================================================================
    @Nested
    @DisplayName("resolveMaxPerResourcePerTurn (V100 unified per-resource cap)")
    class ResolveMaxPerResourcePerTurn {

        @Test
        @DisplayName("Returns YAML default (5) when no override credential is present")
        void fallsBackToYamlDefault() {
            assertThat(module.resolveMaxPerResourcePerTurn(ctx())).isEqualTo(5);
        }

        @Test
        @DisplayName("Uses __chatMaxPerResourcePerTurn__ credential override when positive")
        void usesCredentialOverride() {
            ToolExecutionContext context = new ToolExecutionContext(
                TENANT,
                Map.of("__chatMaxPerResourcePerTurn__", 12, "turnId", "turn-x"),
                Map.of(), Set.of(), null, null, null, null);
            assertThat(module.resolveMaxPerResourcePerTurn(context)).isEqualTo(12);
        }

        @Test
        @DisplayName("Falls back to YAML default when credential override is zero or negative")
        void fallsBackWhenCredentialNonPositive() {
            ToolExecutionContext context = new ToolExecutionContext(
                TENANT,
                Map.of("__chatMaxPerResourcePerTurn__", 0, "turnId", "turn-x"),
                Map.of(), Set.of(), null, null, null, null);
            assertThat(module.resolveMaxPerResourcePerTurn(context)).isEqualTo(5);
        }

        @Test
        @DisplayName("Returns YAML default when context is null")
        void handlesNullContext() {
            assertThat(module.resolveMaxPerResourcePerTurn(null)).isEqualTo(5);
        }

        @Test
        @DisplayName("Picks up YAML override from datasource agent defaults config")
        void honorsYamlOverride() {
            agentDefaults.setMaxPerResourcePerTurn(3);
            assertThat(module.resolveMaxPerResourcePerTurn(ctx())).isEqualTo(3);
        }
    }

    private static com.apimarketplace.datasource.services.VectorFeatureGate ceVectorGate() {
        org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment();
        env.setProperty("app.edition", "ce");
        return new com.apimarketplace.datasource.services.VectorFeatureGate(new com.apimarketplace.common.web.AppEditionProvider(env));
    }
}

package com.apimarketplace.datasource.tools.datasource;

import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.datasource.config.DataSourceAgentDefaultsConfig;
import com.apimarketplace.datasource.crud.service.CrudExecutorService;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSource;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSourceStatus;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSourceType;
import com.apimarketplace.datasource.services.DataSourceService;
import com.apimarketplace.datasource.services.VectorFeatureGate;
import com.apimarketplace.publication.client.PublicationClient;
import com.apimarketplace.common.web.AppEditionProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * The workspace's per-member rules on a table apply to EVERY table-tool action that names one,
 * exactly as they do on the REST CRUD surface: a member DENIED the table sees it as absent, a
 * member with READ-only access reads and never writes.
 *
 * <p>Regression: before, only list (and, since the present refactor, get/present) applied them.
 * query_rows, insert/update/delete_rows and add_columns went straight to the CRUD executor, and
 * update/delete handed the service no caller, so its own gate judged the table's OWNER, who is
 * never restricted. Driven through the whole {@code table} tool, the path an agent takes.
 */
@DisplayName("table tool - workspace member rules on every single-table action")
class TableToolMemberRulesTest {

    private static final String TENANT = "member-7";
    private static final String ORG = "org-1";
    private static final long TABLE = 42L;

    private final DataSourceService dataSourceService = mock(DataSourceService.class);
    private final CrudExecutorService crud = mock(CrudExecutorService.class);
    private final PublicationClient publication = mock(PublicationClient.class);
    private DataSourceToolsProvider tool;

    private static final List<String> READS = List.of("get", "present", "query_rows");
    private static final List<String> WRITES = List.of("insert_rows", "update_rows", "delete_rows", "add_columns", "update", "delete",
        "publish", "unpublish");

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        MockEnvironment env = new MockEnvironment();
        env.setProperty("app.edition", "ce");
        VectorFeatureGate vectorGate = new VectorFeatureGate(new AppEditionProvider(env), null);
        tool = new DataSourceToolsProvider(
            new DataSourceTableModule(dataSourceService, objectMapper, new DataSourceAgentDefaultsConfig(), vectorGate),
            new DataSourceRowModule(crud, dataSourceService, objectMapper),
            new DataSourceSchemaModule(crud, dataSourceService, objectMapper, vectorGate),
            new TablePublishModule(publication, dataSourceService));
        when(dataSourceService.getDataSource(TABLE)).thenReturn(Optional.of(table(TENANT, ORG)));
    }

    private static DataSource table(String tenant, String org) {
        return new DataSource(TABLE, tenant, "Payroll", "desc", DataSourceType.INLINE, Map.of(),
            DataSourceStatus.ACTIVE, null, null, tenant, null, null, null, null, null, org);
    }

    private static ToolExecutionContext member(String orgId) {
        return as(orgId, "MEMBER");
    }

    private static ToolExecutionContext as(String orgId, String role) {
        return new ToolExecutionContext(TENANT, Map.of(), Map.of(), Set.of(), null, null, orgId, role);
    }

    private static Map<String, Object> call(String action) {
        Map<String, Object> p = new HashMap<>();
        p.put("action", action);
        p.put("table_id", TABLE);
        switch (action) {
            case "insert_rows" -> p.put("rows", List.of(Map.of("name", "A")));
            case "update_rows" -> {
                p.put("where", Map.of("column", "id", "operator", "=", "value", 1));
                p.put("set", Map.of("name", "B"));
            }
            case "delete_rows" -> p.put("where", Map.of("column", "id", "operator", "=", "value", 1));
            case "add_columns" -> p.put("columns", List.of(Map.of("name", "note", "type", "text")));
            case "update" -> p.put("name", "Renamed");
            case "publish" -> {
                p.put("title", "Payroll listing");
                p.put("interface_id", "00000000-0000-0000-0000-000000000001");
            }
            default -> { }
        }
        return p;
    }

    private ToolExecutionResult run(String action, ToolExecutionContext ctx) {
        return tool.execute("table", call(action), ctx);
    }

    private void verifyNothingReachedTheTable() {
        verifyNoInteractions(crud, publication);
        verify(dataSourceService, never()).updateDataSource(any(), any(), any(), any(), any(), any());
        verify(dataSourceService, never()).deleteDataSource(any(), any(), any());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"get", "present", "query_rows", "insert_rows", "update_rows", "delete_rows", "add_columns", "update", "delete",
        "publish", "unpublish"})
    @DisplayName("a member DENIED the table is told it does not exist, and nothing reaches it")
    void deniedMemberSeesNothing(String action) {
        when(dataSourceService.canAccessViaOrg(ORG, TENANT, "42", "MEMBER")).thenReturn(false);

        ToolExecutionResult result = run(action, member(ORG));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.DATASOURCE_NOT_FOUND);
        assertThat(result.metadata() == null || !result.metadata().containsKey("visualization")).isTrue();
        verifyNothingReachedTheTable();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"insert_rows", "update_rows", "delete_rows", "add_columns", "update", "delete", "publish", "unpublish"})
    @DisplayName("a READ-only member is refused every write, with a reason it can act on, and nothing reaches the table")
    void readOnlyMemberCannotWrite(String action) {
        when(dataSourceService.canAccessViaOrg(ORG, TENANT, "42", "MEMBER")).thenReturn(true);
        when(dataSourceService.canWriteViaOrg(ORG, TENANT, "42", "MEMBER")).thenReturn(false);

        ToolExecutionResult result = run(action, member(ORG));

        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
        assertThat(result.error()).contains("read-only");
        verifyNothingReachedTheTable();
    }

    @Test
    @DisplayName("a READ-only member still reads: get and present answer, query_rows reaches the rows")
    void readOnlyMemberStillReads() {
        when(dataSourceService.canAccessViaOrg(ORG, TENANT, "42", "MEMBER")).thenReturn(true);
        when(dataSourceService.canWriteViaOrg(ORG, TENANT, "42", "MEMBER")).thenReturn(false);

        assertThat(run("get", member(ORG)).success()).isTrue();
        assertThat(run("present", member(ORG)).success()).isTrue();
        run("query_rows", member(ORG));

        verify(crud).execute(any(), eq(TENANT), eq(ORG));
        verify(dataSourceService, never()).canWriteViaOrg(anyString(), anyString(), anyString(), any());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"insert_rows", "update_rows", "delete_rows", "add_columns"})
    @DisplayName("an unrestricted member's row and schema writes reach the table")
    void unrestrictedMemberWrites(String action) {
        when(dataSourceService.canAccessViaOrg(ORG, TENANT, "42", "MEMBER")).thenReturn(true);
        when(dataSourceService.canWriteViaOrg(ORG, TENANT, "42", "MEMBER")).thenReturn(true);

        ToolExecutionResult result = run(action, member(ORG));

        verify(crud, atLeastOnce()).execute(any(), eq(TENANT), eq(ORG));
        assertThat(result.errorCode()).isNotIn(ToolErrorCode.PERMISSION_DENIED, ToolErrorCode.DATASOURCE_NOT_FOUND);
    }

    @Test
    @DisplayName("update and delete hand the service the CALLER, so its own gate judges them and not the owner")
    void updateAndDeletePassTheCaller() {
        when(dataSourceService.canAccessViaOrg(ORG, TENANT, "42", "MEMBER")).thenReturn(true);
        when(dataSourceService.canWriteViaOrg(ORG, TENANT, "42", "MEMBER")).thenReturn(true);
        when(dataSourceService.updateDataSource(eq(TABLE), eq("Renamed"), any(), any(), eq(TENANT), eq("MEMBER")))
            .thenReturn(table(TENANT, ORG));

        assertThat(run("update", member(ORG)).success()).isTrue();
        assertThat(run("delete", member(ORG)).success()).isTrue();

        verify(dataSourceService).updateDataSource(eq(TABLE), eq("Renamed"), any(), any(), eq(TENANT), eq("MEMBER"));
        verify(dataSourceService).deleteDataSource(TABLE, TENANT, "MEMBER");
        verify(dataSourceService, never()).deleteDataSource(TABLE);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"get", "present", "query_rows", "update", "delete", "insert_rows", "add_columns"})
    @DisplayName("a table of ANOTHER workspace is never judged by member rules, so no answer reveals it exists")
    void otherWorkspaceIsNotJudged(String action) {
        when(dataSourceService.getDataSource(TABLE)).thenReturn(Optional.of(table("someone-else", "org-2")));

        ToolExecutionResult result = run(action, member(ORG));

        // Exactly the answer any out-of-workspace table gets: never a "read-only" that proves it exists.
        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isIn(ToolErrorCode.DATASOURCE_NOT_FOUND, ToolErrorCode.EXECUTION_FAILED);
        assertThat(result.error()).doesNotContain("read-only");
        verify(dataSourceService, never()).canAccessViaOrg(any(), any(), any(), any());
        verify(dataSourceService, never()).canWriteViaOrg(any(), any(), any(), any());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"publish", "unpublish"})
    @DisplayName("publishing a table of ANOTHER workspace is never answered read-only (the publication service refuses it)")
    void otherWorkspacePublishIsNotJudged(String action) {
        when(dataSourceService.getDataSource(TABLE)).thenReturn(Optional.of(table("someone-else", "org-2")));

        ToolExecutionResult result = run(action, member(ORG));

        assertThat(result.error() == null || !result.error().contains("read-only")).isTrue();
        verify(dataSourceService, never()).canAccessViaOrg(any(), any(), any(), any());
        verify(dataSourceService, never()).canWriteViaOrg(any(), any(), any(), any());
    }

    @Test
    @DisplayName("an unknown table is left to the downstream not-found, never judged")
    void unknownTableIsNotJudged() {
        when(dataSourceService.getDataSource(TABLE)).thenReturn(Optional.empty());

        for (String action : READS) {
            assertThat(run(action, member(ORG)).success()).as(action).isFalse();
        }
        for (String action : List.of("update", "delete")) {
            ToolExecutionResult result = run(action, member(ORG));
            assertThat(result.errorCode()).as(action).isEqualTo(ToolErrorCode.DATASOURCE_NOT_FOUND);
        }
        verify(dataSourceService, never()).canAccessViaOrg(any(), any(), any(), any());
        verify(dataSourceService, never()).canWriteViaOrg(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a caller with no workspace context is not judged by member rules (the scope check decides)")
    void callerWithoutWorkspaceIsNotJudged() {
        run("query_rows", as(null, null));
        run("insert_rows", as(null, null));

        // Passed straight through to the CRUD executor, whose own scope check decides.
        verify(crud, times(2)).execute(any(), eq(TENANT), isNull());
        verify(dataSourceService, never()).canAccessViaOrg(any(), any(), any(), any());
        verify(dataSourceService, never()).canWriteViaOrg(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a table with no workspace of its own is not judged by member rules")
    void tableWithoutWorkspaceIsNotJudged() {
        when(dataSourceService.getDataSource(TABLE)).thenReturn(Optional.of(table(TENANT, null)));

        run("insert_rows", as(null, null));

        verify(dataSourceService, never()).canAccessViaOrg(any(), any(), any(), any());
        verify(crud).execute(any(), eq(TENANT), isNull());
    }

    @Test
    @DisplayName("a caller with no role (an agent run by a workflow) is judged with that null role, as the service does")
    void nullRoleIsPassedThrough() {
        when(dataSourceService.canAccessViaOrg(ORG, TENANT, "42", null)).thenReturn(false);

        ToolExecutionResult result = run("query_rows", as(ORG, null));

        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.DATASOURCE_NOT_FOUND);
        verifyNoInteractions(crud);
    }

    @Test
    @DisplayName("a VIEWER cannot create a table; a member can")
    void viewerCannotCreate() {
        Map<String, Object> create = new HashMap<>(Map.of("action", "create", "name", "New", "data", List.of(Map.of("a", 1))));

        ToolExecutionResult viewer = tool.execute("table", create, as(ORG, "VIEWER"));
        ToolExecutionResult member = tool.execute("table", new HashMap<>(create), as(ORG, "MEMBER"));

        assertThat(viewer.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
        assertThat(viewer.error()).contains("viewer");
        assertThat(member.error() == null || !member.error().contains("viewer")).isTrue();
        verify(dataSourceService, times(1)).createDataSource(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }
}

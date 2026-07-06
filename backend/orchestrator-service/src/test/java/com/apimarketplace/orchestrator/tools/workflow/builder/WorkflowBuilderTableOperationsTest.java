package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.datasource.client.DataSourceClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #TC3: verify extractTableId accepts all documented aliases for the table-routing
 * param across CRUD operations. Help docs recommend `dataSourceId` for find_rows and
 * `table_id` for insert/read/update/delete - users following either must not be rejected.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowBuilderTableOperations - #TC3 table_id alias extraction")
class WorkflowBuilderTableOperationsTest {

    @Mock private DataSourceClient dataSourceClient;
    @Mock private WorkflowBuilderCreator creator;

    private WorkflowBuilderTableOperations ops;

    @BeforeEach
    void setUp() {
        ops = new WorkflowBuilderTableOperations(dataSourceClient, creator);
    }

    @Test
    @DisplayName("returns value from canonical 'table_id'")
    void canonicalTableId() {
        Map<String, Object> p = new HashMap<>();
        p.put("table_id", 42);
        assertThat(ops.extractTableId(p)).isEqualTo(42);
    }

    @Test
    @DisplayName("returns value from 'dataSourceId' (camelCase, find_rows docs)")
    void dataSourceIdCamel() {
        Map<String, Object> p = new HashMap<>();
        p.put("dataSourceId", 42);
        assertThat(ops.extractTableId(p)).isEqualTo(42);
    }

    @Test
    @DisplayName("returns value from 'datasource_id' (snake_case)")
    void datasourceIdSnake() {
        Map<String, Object> p = new HashMap<>();
        p.put("datasource_id", 42);
        assertThat(ops.extractTableId(p)).isEqualTo(42);
    }

    @Test
    @DisplayName("returns value from 'tableId' (camelCase)")
    void tableIdCamel() {
        Map<String, Object> p = new HashMap<>();
        p.put("tableId", 42);
        assertThat(ops.extractTableId(p)).isEqualTo(42);
    }

    @Test
    @DisplayName("prefers canonical 'table_id' over aliases when both present")
    void canonicalWinsOverAlias() {
        Map<String, Object> p = new HashMap<>();
        p.put("table_id", 100);
        p.put("dataSourceId", 200);
        assertThat(ops.extractTableId(p)).isEqualTo(100);
    }

    @Test
    @DisplayName("falls through alias precedence: dataSourceId > datasource_id > tableId")
    void aliasPrecedence() {
        Map<String, Object> p = new HashMap<>();
        p.put("datasource_id", 2);
        p.put("tableId", 3);
        // dataSourceId absent, datasource_id wins over tableId
        assertThat(ops.extractTableId(p)).isEqualTo(2);
    }

    @Test
    @DisplayName("returns null when no alias present")
    void missingAllAliases() {
        Map<String, Object> p = new HashMap<>();
        p.put("where", Map.of("c", "x"));
        assertThat(ops.extractTableId(p)).isNull();
    }

    @Test
    @DisplayName("null value treated as absent, falls through to next alias")
    void nullValueFallsThrough() {
        Map<String, Object> p = new HashMap<>();
        p.put("table_id", null);
        p.put("dataSourceId", 42);
        assertThat(ops.extractTableId(p)).isEqualTo(42);
    }

    // #TC3: the alias keys must NOT leak into the final step params. execute()
    // normalizes to canonical `table_id`, and transformToStep() must strip both
    // `datasource_id` and `tableId` (dataSourceId is kept because it's the
    // canonical downstream key, set explicitly from the resolved tableId).
    @Test
    @DisplayName("transformToStep strips LLM alias keys (datasource_id, tableId)")
    void transformToStepStripsAliases() {
        Map<String, Object> params = new HashMap<>();
        params.put("table_id", 42L);
        params.put("datasource_id", 42L);  // alias the LLM may have passed
        params.put("tableId", 42L);        // another alias
        params.put("label", "Find Active");
        params.put("where", Map.of("column", "status", "operator", "=", "value", "active"));

        Map<String, Object> stepParams = ops.transformToStep(params, "find_rows", 42L);

        assertThat(stepParams)
            .doesNotContainKey("table_id")
            .doesNotContainKey("datasource_id")
            .doesNotContainKey("tableId")
            .containsEntry("dataSourceId", 42L)  // canonical, set by transform
            .containsEntry("id", "crud/find-rows")
            .containsEntry("label", "Find Active");
    }

    @Test
    @DisplayName("transformToStep keeps canonical dataSourceId even when LLM passed a different alias")
    void transformToStepPreservesCanonicalDataSourceId() {
        Map<String, Object> params = new HashMap<>();
        params.put("table_id", 42L);       // canonical (set by execute())
        params.put("tableId", 999L);       // LLM junk - must not leak
        params.put("label", "Delete One");
        params.put("where", Map.of("column", "id", "operator", "=", "value", "7"));

        Map<String, Object> stepParams = ops.transformToStep(params, "delete_row", 42L);

        // Downstream expects dataSourceId = the authoritative tableId, not the LLM's tableId alias.
        assertThat(stepParams.get("dataSourceId")).isEqualTo(42L);
        assertThat(stepParams).doesNotContainKey("tableId");
    }

    // Documented contract (help: table_crud_types.where): the where column is a BARE
    // name. An LLM that wrongly prefixes it with "data." must still work - the prefix is
    // stripped at build time so the stored plan stays canonical and the downstream
    // SqlSanitizer (which rejects a dotted column) never sees "data.". Guards the exact
    // confusion that caused wasted cycles on the Gmail Auto-Labeler dedup work.
    @Test
    @DisplayName("transformToStep strips 'data.' prefix from where.column (bare-name canonical form)")
    @SuppressWarnings("unchecked")
    void transformToStepStripsDataPrefixFromWhereColumn() {
        Map<String, Object> params = new HashMap<>();
        params.put("table_id", 38L);
        params.put("label", "Check Existing");
        params.put("where", Map.of("column", "data.message_id", "operator", "=", "value", "{{current_item.id}}"));

        Map<String, Object> stepParams = ops.transformToStep(params, "find_rows", 38L);

        Map<String, Object> crud = (Map<String, Object>) stepParams.get("crud");
        Map<String, Object> where = (Map<String, Object>) crud.get("where");
        assertThat(where.get("column")).isEqualTo("message_id");
        assertThat(where.get("operator")).isEqualTo("=");
        assertThat(where.get("value")).isEqualTo("{{current_item.id}}");
    }

    @Test
    @DisplayName("transformToStep leaves an already-bare where.column unchanged")
    @SuppressWarnings("unchecked")
    void transformToStepKeepsBareWhereColumn() {
        Map<String, Object> params = new HashMap<>();
        params.put("table_id", 38L);
        params.put("label", "Check Existing");
        params.put("where", Map.of("column", "message_id", "operator", "=", "value", "abc"));

        Map<String, Object> stepParams = ops.transformToStep(params, "find_rows", 38L);

        Map<String, Object> crud = (Map<String, Object>) stepParams.get("crud");
        Map<String, Object> where = (Map<String, Object>) crud.get("where");
        assertThat(where.get("column")).isEqualTo("message_id");
    }

    // The `offset` pagination param is documented on find_rows/get_rows and fully supported
    // downstream (CrudToolExecutor, FindNode, GetRowsNodeSpec, LimitNode), but the simplified
    // builder used to drop it before it reached the crud block - so a documented param silently
    // did nothing. transformToStep must forward it into crud (and not leak it to top-level).
    @Test
    @DisplayName("transformToStep forwards 'offset' into crud for find_rows")
    @SuppressWarnings("unchecked")
    void transformToStepForwardsOffsetForFindRows() {
        Map<String, Object> params = new HashMap<>();
        params.put("table_id", 42L);
        params.put("label", "Page 2");
        params.put("limit", 50);
        params.put("offset", 100);

        Map<String, Object> stepParams = ops.transformToStep(params, "find_rows", 42L);

        Map<String, Object> crud = (Map<String, Object>) stepParams.get("crud");
        assertThat(crud).containsEntry("offset", 100).containsEntry("limit", 50);
        assertThat(stepParams).doesNotContainKey("offset"); // stripped from top-level
    }

    @Test
    @DisplayName("transformToStep forwards 'offset' into crud for read_rows")
    @SuppressWarnings("unchecked")
    void transformToStepForwardsOffsetForReadRows() {
        Map<String, Object> params = new HashMap<>();
        params.put("table_id", 7L);
        params.put("label", "Fetch Page");
        params.put("offset", 25);

        Map<String, Object> stepParams = ops.transformToStep(params, "read_rows", 7L);

        Map<String, Object> crud = (Map<String, Object>) stepParams.get("crud");
        assertThat(crud).containsEntry("offset", 25);
        assertThat(stepParams).doesNotContainKey("offset");
    }

    @Test
    @DisplayName("transformToStep omits 'offset' from crud when not provided (no spurious default)")
    @SuppressWarnings("unchecked")
    void transformToStepOmitsOffsetWhenAbsent() {
        Map<String, Object> params = new HashMap<>();
        params.put("table_id", 7L);
        params.put("label", "No Offset");

        Map<String, Object> stepParams = ops.transformToStep(params, "find_rows", 7L);

        Map<String, Object> crud = (Map<String, Object>) stepParams.get("crud");
        assertThat(crud).doesNotContainKey("offset");
    }

    @Test
    @DisplayName("transformToStep overwrites LLM-provided dataSourceId with authoritative canonical value")
    void transformToStepOverwritesConflictingDataSourceId() {
        // Simulates: LLM passes BOTH dataSourceId=999 (junk) and table_id=42 (correct).
        // execute() picks table_id=42 via extractTableId precedence; transformToStep
        // is called with tableId=42L. The LLM's bogus dataSourceId must NOT survive.
        Map<String, Object> params = new HashMap<>();
        params.put("table_id", 42L);
        params.put("dataSourceId", 999L);  // LLM conflict - must be overwritten
        params.put("label", "Update One");
        params.put("where", Map.of("column", "id", "operator", "=", "value", "5"));
        params.put("set", Map.of("status", "done"));

        Map<String, Object> stepParams = ops.transformToStep(params, "update_row", 42L);

        // Canonical write wins - the LLM's 999 is overwritten, not leaked.
        assertThat(stepParams.get("dataSourceId")).isEqualTo(42L);
        assertThat(stepParams).doesNotContainKey("table_id");
        assertThat(stepParams).doesNotContainKey("datasource_id");
        assertThat(stepParams).doesNotContainKey("tableId");
    }
}

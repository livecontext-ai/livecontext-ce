package com.apimarketplace.datasource.persistence;

import com.apimarketplace.datasource.domain.DataSourceEnhancedModels.ColumnManagementRequest;
import com.apimarketplace.datasource.domain.DataSourceEnhancedModels.ColumnOperation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mockingDetails;

@DisplayName("DataSourceColumnRepository column management")
class DataSourceColumnRepositoryTest {

    @Test
    @DisplayName("RENAME uses text-array JSONB paths and refreshes the mapping path")
    void renameUsesTextArrayJsonbPathsAndRefreshesMappingPath() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        DataSourceColumnRepository repository = new DataSourceColumnRepository(jdbcTemplate);
        ColumnManagementRequest request = new ColumnManagementRequest(
            ColumnOperation.RENAME,
            "assignee",
            "owner",
            null,
            null
        );

        var result = repository.manageColumn(42L, "tenant-1", request);

        assertThat(result.success()).isTrue();

        // Three calls: the item rewrite, the mapping rewrite, and the column_order
        // read that keeps the renamed column in its place.
        List<Invocation> invocations = new ArrayList<>(mockingDetails(jdbcTemplate).getInvocations());
        assertThat(invocations).hasSize(3);

        Object[] itemUpdate = flattenedArguments(invocations.get(0));
        assertThat((String) itemUpdate[0]).contains("ARRAY[?]::text[]");
        assertThat(Arrays.copyOfRange(itemUpdate, 1, itemUpdate.length))
            .containsExactly("assignee", "owner", "assignee", 42L, "tenant-1");

        Object[] mappingUpdate = flattenedArguments(invocations.get(1));
        assertThat((String) mappingUpdate[0])
            .contains("ARRAY[?]::text[]")
            .contains("'{path}'")
            .contains("THEN 'data.' || ?");
        assertThat(Arrays.copyOfRange(mappingUpdate, 1, mappingUpdate.length))
            .containsExactly("assignee", "owner", "assignee", "assignee", "owner", "owner", 42L, "tenant-1");
    }

    @Test
    @DisplayName("SET_DEFAULT writes JSONB defaults only to missing or null values")
    void setDefaultWritesJsonbDefaultsOnlyToMissingOrNullValues() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        DataSourceColumnRepository repository = new DataSourceColumnRepository(jdbcTemplate);
        ColumnManagementRequest request = new ColumnManagementRequest(
            ColumnOperation.SET_DEFAULT,
            "backfill",
            null,
            "ready",
            null
        );

        var result = repository.manageColumn(42L, "tenant-1", request);

        assertThat(result.success()).isTrue();

        List<Invocation> invocations = new ArrayList<>(mockingDetails(jdbcTemplate).getInvocations());
        assertThat(invocations).hasSize(1);

        Object[] update = flattenedArguments(invocations.get(0));
        assertThat((String) update[0])
            .contains("jsonb_set(data, ?::text[], ?::jsonb, true)")
            .contains("jsonb_typeof(data->?) IS NULL")
            .contains("data->? = 'null'::jsonb");
        assertThat(Arrays.copyOfRange(update, 1, update.length))
            .containsExactly("{backfill}", "\"ready\"", 42L, "tenant-1", "backfill", "backfill");
    }

    @Test
    @DisplayName("RENAME rewrites the column's entry in column_order so it keeps its position")
    void renameRewritesColumnOrderEntryInPlace() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        // A table that has seen BOTH writers: the grid saved the rendered fields
        // with their `data.` prefix, and `assignee` was appended bare when the
        // column was created.
        when(jdbcTemplate.queryForObject(contains("SELECT column_order"), eq(String.class), any(), any()))
            .thenReturn("[{\"field\":\"checkbox\",\"order\":0},"
                + "{\"field\":\"data.alpha\",\"order\":1},"
                + "{\"field\":\"assignee\",\"order\":2},"
                + "{\"field\":\"data.beta\",\"order\":3}]");
        DataSourceColumnRepository repository = new DataSourceColumnRepository(jdbcTemplate);

        var result = repository.manageColumn(42L, "tenant-1",
            new ColumnManagementRequest(ColumnOperation.RENAME, "assignee", "owner", null, null));

        assertThat(result.success()).isTrue();

        Object[] orderUpdate = flattenedArguments(lastColumnOrderWrite(jdbcTemplate));
        // Position 2 is preserved, and nothing else in the array is touched.
        assertThat((String) orderUpdate[1]).isEqualTo(
            "[{\"field\":\"checkbox\",\"order\":0},"
                + "{\"field\":\"data.alpha\",\"order\":1},"
                + "{\"field\":\"owner\",\"order\":2},"
                + "{\"field\":\"data.beta\",\"order\":3}]");
    }

    @Test
    @DisplayName("RENAME rewrites the prefixed spelling the grid saves, not only the bare one")
    void renameRewritesThePrefixedSpellingToo() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(contains("SELECT column_order"), eq(String.class), any(), any()))
            .thenReturn("[{\"field\":\"data.assignee\",\"order\":0},{\"field\":\"data.other\",\"order\":1}]");
        DataSourceColumnRepository repository = new DataSourceColumnRepository(jdbcTemplate);

        repository.manageColumn(42L, "tenant-1",
            new ColumnManagementRequest(ColumnOperation.RENAME, "assignee", "owner", null, null));

        Object[] orderUpdate = flattenedArguments(lastColumnOrderWrite(jdbcTemplate));
        assertThat((String) orderUpdate[1])
            .isEqualTo("[{\"field\":\"data.owner\",\"order\":0},{\"field\":\"data.other\",\"order\":1}]");
    }

    @Test
    @DisplayName("RENAME leaves column_order untouched when it does not mention the column")
    void renameLeavesColumnOrderUntouchedWhenTheColumnIsAbsent() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(contains("SELECT column_order"), eq(String.class), any(), any()))
            .thenReturn("[{\"field\":\"checkbox\",\"order\":0}]");
        DataSourceColumnRepository repository = new DataSourceColumnRepository(jdbcTemplate);

        repository.manageColumn(42L, "tenant-1",
            new ColumnManagementRequest(ColumnOperation.RENAME, "assignee", "owner", null, null));

        assertThat(columnOrderWrites(jdbcTemplate)).isEmpty();
    }

    @Test
    @DisplayName("RENAME still succeeds when column_order cannot be read")
    void renameStillSucceedsWhenColumnOrderCannotBeRead() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        // Where a column sits must never decide whether renaming it worked.
        when(jdbcTemplate.queryForObject(contains("SELECT column_order"), eq(String.class), any(), any()))
            .thenThrow(new RuntimeException("column_order unreadable"));
        DataSourceColumnRepository repository = new DataSourceColumnRepository(jdbcTemplate);

        var result = repository.manageColumn(42L, "tenant-1",
            new ColumnManagementRequest(ColumnOperation.RENAME, "assignee", "owner", null, null));

        assertThat(result.success()).isTrue();
        assertThat(columnOrderWrites(jdbcTemplate)).isEmpty();
    }

    @Test
    @DisplayName("RENAME onto an existing column drops the entry instead of duplicating it")
    void renameOntoAnExistingColumnDoesNotDuplicateTheEntry() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        // Nothing upstream refuses renaming onto an existing column, and a
        // duplicate entry would be copied verbatim into every snapshot and clone.
        when(jdbcTemplate.queryForObject(contains("SELECT column_order"), eq(String.class), any(), any()))
            .thenReturn("[{\"field\":\"assignee\",\"order\":0},{\"field\":\"owner\",\"order\":1}]");
        DataSourceColumnRepository repository = new DataSourceColumnRepository(jdbcTemplate);

        repository.manageColumn(42L, "tenant-1",
            new ColumnManagementRequest(ColumnOperation.RENAME, "assignee", "owner", null, null));

        Object[] orderUpdate = flattenedArguments(lastColumnOrderWrite(jdbcTemplate));
        // One `owner`, keeping the position the surviving column already had.
        assertThat((String) orderUpdate[1]).isEqualTo("[{\"field\":\"owner\",\"order\":1}]");
    }

    @Test
    @DisplayName("RENAME rewrites a legacy name-keyed entry without giving it two names")
    void renameRewritesLegacyNameKeyedEntry() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        // The reader on the other side of this contract accepts `name`, so this
        // side has to as well, or the fix skips exactly those entries.
        when(jdbcTemplate.queryForObject(contains("SELECT column_order"), eq(String.class), any(), any()))
            .thenReturn("[{\"name\":\"assignee\",\"order\":0},{\"field\":\"other\",\"order\":1}]");
        DataSourceColumnRepository repository = new DataSourceColumnRepository(jdbcTemplate);

        repository.manageColumn(42L, "tenant-1",
            new ColumnManagementRequest(ColumnOperation.RENAME, "assignee", "owner", null, null));

        Object[] orderUpdate = flattenedArguments(lastColumnOrderWrite(jdbcTemplate));
        assertThat((String) orderUpdate[1])
            .isEqualTo("[{\"name\":\"owner\",\"order\":0},{\"field\":\"other\",\"order\":1}]");
    }

    @Test
    @DisplayName("RENAME survives one malformed entry instead of skipping the whole fix")
    void renameSurvivesOneMalformedEntry() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        // A JSONB array can hold anything. Discarding the position fix because of
        // one junk element is the loud half of the same silent bug.
        when(jdbcTemplate.queryForObject(contains("SELECT column_order"), eq(String.class), any(), any()))
            .thenReturn("[\"junk\",null,{\"field\":\"assignee\",\"order\":0}]");
        DataSourceColumnRepository repository = new DataSourceColumnRepository(jdbcTemplate);

        repository.manageColumn(42L, "tenant-1",
            new ColumnManagementRequest(ColumnOperation.RENAME, "assignee", "owner", null, null));

        Object[] orderUpdate = flattenedArguments(lastColumnOrderWrite(jdbcTemplate));
        assertThat((String) orderUpdate[1]).isEqualTo("[\"junk\",null,{\"field\":\"owner\",\"order\":0}]");
    }

    @Test
    @DisplayName("RENAME onto an existing column drops the entry whichever spellings the two use")
    void renameOntoAnExistingColumnDoesNotDuplicateAcrossSpellings() {
        // The likely shape, not the exotic one: a table that has seen a drag AND an
        // added column carries `data.x` for one and a bare name for the other, so a
        // guard that compared raw spellings would never fire on it.
        assertThat(renameProducingOrder("[{\"field\":\"data.assignee\"},{\"field\":\"owner\"}]"))
            .isEqualTo("[{\"field\":\"owner\"}]");
        assertThat(renameProducingOrder("[{\"field\":\"assignee\"},{\"field\":\"data.owner\"}]"))
            .isEqualTo("[{\"field\":\"data.owner\"}]");
    }

    @Test
    @DisplayName("RENAME collapses an array holding both spellings of the renamed column")
    void renameCollapsesBothSpellingsOfTheRenamedColumn() {
        assertThat(renameProducingOrder("[{\"field\":\"assignee\"},{\"field\":\"data.assignee\"}]"))
            .isEqualTo("[{\"field\":\"owner\"}]");
    }

    @Test
    @DisplayName("RENAME rewrites every name key an entry carries, never only one of two")
    void renameRewritesEveryNameKeyAnEntryCarries() {
        assertThat(renameProducingOrder("[{\"field\":\"assignee\",\"name\":\"assignee\"}]"))
            .isEqualTo("[{\"field\":\"owner\",\"name\":\"owner\"}]");
    }

    @Test
    @DisplayName("RENAME to the same name writes no column_order at all")
    void renameToTheSameNameWritesNothing() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(contains("SELECT column_order"), eq(String.class), any(), any()))
            .thenReturn("[{\"field\":\"assignee\",\"order\":0}]");
        DataSourceColumnRepository repository = new DataSourceColumnRepository(jdbcTemplate);

        repository.manageColumn(42L, "tenant-1",
            new ColumnManagementRequest(ColumnOperation.RENAME, "assignee", "assignee", null, null));

        assertThat(columnOrderWrites(jdbcTemplate)).isEmpty();
    }

    /** Rename `assignee` to `owner` against a stored order, and return what was written. */
    private static String renameProducingOrder(String storedColumnOrder) {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(contains("SELECT column_order"), eq(String.class), any(), any()))
            .thenReturn(storedColumnOrder);
        new DataSourceColumnRepository(jdbcTemplate).manageColumn(42L, "tenant-1",
            new ColumnManagementRequest(ColumnOperation.RENAME, "assignee", "owner", null, null));
        return (String) flattenedArguments(lastColumnOrderWrite(jdbcTemplate))[1];
    }

    /** Every UPDATE that writes the column_order column. */
    private static List<Invocation> columnOrderWrites(JdbcTemplate jdbcTemplate) {
        return mockingDetails(jdbcTemplate).getInvocations().stream()
            .filter(invocation -> {
                Object[] arguments = flattenedArguments(invocation);
                return arguments.length > 0
                    && arguments[0] instanceof String sql
                    && sql.contains("SET column_order");
            })
            .toList();
    }

    private static Invocation lastColumnOrderWrite(JdbcTemplate jdbcTemplate) {
        List<Invocation> writes = columnOrderWrites(jdbcTemplate);
        assertThat(writes).as("the rename must write column_order back").isNotEmpty();
        return writes.get(writes.size() - 1);
    }

    private static Object[] flattenedArguments(Invocation invocation) {
        Object[] arguments = invocation.getArguments();
        if (arguments.length == 2 && arguments[1] instanceof Object[] varargs) {
            Object[] flattened = new Object[varargs.length + 1];
            flattened[0] = arguments[0];
            System.arraycopy(varargs, 0, flattened, 1, varargs.length);
            return flattened;
        }
        return arguments;
    }
}

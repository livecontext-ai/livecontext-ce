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
import static org.mockito.Mockito.mock;
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

        List<Invocation> invocations = new ArrayList<>(mockingDetails(jdbcTemplate).getInvocations());
        assertThat(invocations).hasSize(2);

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

package com.apimarketplace.orchestrator.persistence;

import com.apimarketplace.orchestrator.domain.WorkflowStepDataEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A native query that Hibernate maps onto {@link WorkflowStepDataEntity} must return EVERY mapped
 * column: the "lightweight" reads list their columns by hand (to NULL out the heavy JSONB ones),
 * and a column added to the entity but not to such a list fails at runtime with "column not found"
 * on the run-query and resume paths, which no mock-based test executes. Found while adding
 * {@code is_mocked} (V526): three such lists, none of them exercised by any test.
 */
@DisplayName("WorkflowStepDataRepository native SELECTs return every entity column")
class WorkflowStepDataNativeSelectColumnsTest {

    private static final Pattern SELECT_LIST = Pattern.compile(
            "(?is)^\\s*SELECT\\s+(?:DISTINCT\\s+ON\\s*\\([^)]*\\)\\s*)?(.*?)\\s+FROM\\s");
    private static final Pattern ALIAS = Pattern.compile("(?i)\\bAS\\s+([a-z_][a-z0-9_]*)\\s*$");
    private static final Pattern LAST_IDENTIFIER = Pattern.compile("(?i)([a-z_][a-z0-9_]*)\\s*$");

    @Test
    @DisplayName("every native query mapped onto the entity names all of its columns")
    void everyEntityMappedNativeSelectNamesEveryColumn() {
        Set<String> entityColumns = entityColumns();
        assertThat(entityColumns).contains("is_mocked", "id", "tool_id");

        List<String> checked = new ArrayList<>();
        for (Method method : WorkflowStepDataRepository.class.getDeclaredMethods()) {
            Query query = method.getAnnotation(Query.class);
            if (query == null || !query.nativeQuery() || !returnsEntity(method)) {
                continue;
            }
            Matcher m = SELECT_LIST.matcher(query.value());
            assertThat(m.find()).describedAs("%s: no SELECT ... FROM", method.getName()).isTrue();
            Set<String> selected = selectedColumns(m.group(1));
            if (selected.contains("*")) {
                continue; // SELECT * / s.* already returns every column
            }
            assertThat(new TreeSet<>(selected))
                    .describedAs("%s must select every WorkflowStepDataEntity column", method.getName())
                    .containsAll(entityColumns);
            checked.add(method.getName());
        }
        assertThat(checked)
                .describedAs("the explicit-column reads this test exists for")
                .contains("findByWorkflowRunIdLightweightAll", "findLatestPerAliasLightweight",
                        "findByWorkflowRunIdAndEpochLatestPerAliasLightweight");
    }

    private static Set<String> entityColumns() {
        Set<String> columns = new TreeSet<>();
        for (Field field : WorkflowStepDataEntity.class.getDeclaredFields()) {
            Column column = field.getAnnotation(Column.class);
            if (column != null) {
                columns.add(column.name().toLowerCase(Locale.ROOT));
            } else if (field.isAnnotationPresent(Id.class)) {
                columns.add(field.getName().toLowerCase(Locale.ROOT));
            }
        }
        return columns;
    }

    private static boolean returnsEntity(Method method) {
        if (method.getReturnType() == WorkflowStepDataEntity.class) {
            return true;
        }
        Type type = method.getGenericReturnType();
        if (type instanceof ParameterizedType p) {
            for (Type arg : p.getActualTypeArguments()) {
                if (arg == WorkflowStepDataEntity.class) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Set<String> selectedColumns(String list) {
        Set<String> names = new LinkedHashSet<>();
        for (String item : list.split(",")) {
            String trimmed = item.trim();
            if (trimmed.endsWith("*")) {
                names.add("*");
                continue;
            }
            Matcher alias = ALIAS.matcher(trimmed);
            Matcher last = LAST_IDENTIFIER.matcher(trimmed);
            if (alias.find()) {
                names.add(alias.group(1).toLowerCase(Locale.ROOT));
            } else if (last.find()) {
                names.add(last.group(1).toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }
}

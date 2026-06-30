package com.apimarketplace.datasource.persistence.nested;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NestedArraySqlBuilderTest {

    private final NestedArraySqlBuilder builder = new NestedArraySqlBuilder(new JsonPathBuilder());

    @Test
    void buildSelectSqlBindsJsonPathAndObjectArraySortField() {
        List<Object> params = new ArrayList<>(List.of("tenant-1", 42L));
        String maliciousSort = "name');DROP_TABLE_users;--";

        String sql = builder.buildSelectSql(
                "payload.items",
                maliciousSort,
                "asc",
                3,
                25,
                params);

        assertThat(sql).contains("WITH filtered_items AS");
        assertThat(sql).contains("jsonb_array_elements(jsonb_extract_path(dsi.data, ?, ?))");
        assertThat(sql).contains("jsonb_extract_path_text(elem.value, ?)");
        assertThat(sql).doesNotContain("payload.items");
        assertThat(sql).doesNotContain(maliciousSort);
        assertThat(sql).doesNotContain("elem.value->>'");
        assertThat(params).containsExactly(
                "tenant-1", 42L,
                "payload", "items",
                "payload", "items",
                "payload", "items",
                maliciousSort,
                25, 50);
    }
}

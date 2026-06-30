package com.apimarketplace.datasource.persistence.nested;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NestedObjectSqlBuilderTest {

    private final NestedObjectSqlBuilder builder = new NestedObjectSqlBuilder(new JsonPathBuilder());

    @Test
    void buildSelectSqlBindsJsonPathAndSortField() {
        List<Object> params = new ArrayList<>(List.of("tenant-1", 42L));
        String maliciousSort = "name');DROP_TABLE_users;--";

        String sql = builder.buildSelectSql(
                "payload.profile",
                maliciousSort,
                "desc",
                2,
                50,
                params);

        assertThat(sql).contains("WITH filtered_items AS");
        assertThat(sql).contains("jsonb_extract_path(data, ?, ?)");
        assertThat(sql).contains("jsonb_extract_path_text(jsonb_extract_path(data, ?, ?), ?)");
        assertThat(sql).doesNotContain("payload.profile");
        assertThat(sql).doesNotContain(maliciousSort);
        assertThat(sql).doesNotContain("->'");
        assertThat(params).containsExactly(
                "tenant-1", 42L,
                "payload", "profile",
                "payload", "profile",
                "payload", "profile",
                "payload", "profile", maliciousSort,
                50, 50);
    }
}

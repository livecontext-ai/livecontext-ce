package com.apimarketplace.datasource.persistence.nested;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonPathBuilderTest {

    private final JsonPathBuilder builder = new JsonPathBuilder();

    @Test
    void buildJsonbPathBindsPathSegments() {
        List<Object> params = new ArrayList<>();

        String sql = builder.buildJsonbPath("payload.profile", params);

        assertThat(sql).isEqualTo("jsonb_extract_path(data, ?, ?)");
        assertThat(params).containsExactly("payload", "profile");
    }

    @Test
    void buildJsonbPathDoesNotInlineMaliciousSegment() {
        List<Object> params = new ArrayList<>();
        String malicious = "payload');DROP_TABLE_data_source_items;--";

        String sql = builder.buildJsonbPath(malicious, params);

        assertThat(sql).isEqualTo("jsonb_extract_path(data, ?)");
        assertThat(sql).doesNotContain(malicious);
        assertThat(params).containsExactly(malicious);
    }

    @Test
    void parsePathSegmentsRejectsAmbiguousPathSyntax() {
        assertThatThrownBy(() -> builder.parsePathSegments("payload..profile"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid JSON path format");
    }
}

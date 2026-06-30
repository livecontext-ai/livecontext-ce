package com.apimarketplace.datasource.client.dto;

import java.util.List;
import java.util.Map;

/**
 * A project's datasources together with the same preview payload the paged list endpoint returns:
 * {@code rowCounts} (id → number of rows) and {@code sampleRows} (id → first few row {@code data}
 * payloads). Lets a project's Tables tab render the identical mini-table card as {@code /app/tables}.
 *
 * <p>{@code rowCounts} and {@code sampleRows} are keyed by the datasource id as a <b>string</b>
 * (JSON object keys are always strings), matching how the frontend looks them up by
 * {@code String(ds.id)}.
 */
public record ProjectDataSourcesPreviewDto(
        List<DataSourceDto> items,
        Map<String, Long> rowCounts,
        Map<String, List<Map<String, Object>>> sampleRows
) {
    public static ProjectDataSourcesPreviewDto empty() {
        return new ProjectDataSourcesPreviewDto(List.of(), Map.of(), Map.of());
    }
}

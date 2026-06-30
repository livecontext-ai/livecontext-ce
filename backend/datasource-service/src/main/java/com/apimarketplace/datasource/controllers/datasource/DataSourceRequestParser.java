package com.apimarketplace.datasource.controllers.datasource;

import com.apimarketplace.datasource.domain.DataSourceEnhancedModels.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses request parameters for DataSource endpoints.
 * Handles sort and filter parameter parsing.
 *
 * Extracted from DataSourceController for Single Responsibility Principle.
 *
 * @see DataSourceController
 */
@Component
public class DataSourceRequestParser {

    /**
     * Parse sort parameter from string format.
     * Format: "priority:desc,created_at:asc"
     *
     * @param sort The sort string
     * @return List of SortRequest objects
     */
    public List<SortRequest> parseSortParameter(String sort) {
        if (sort == null || sort.trim().isEmpty()) {
            return List.of();
        }

        List<SortRequest> sortRequests = new ArrayList<>();
        String[] sortParts = sort.split(",");

        for (String part : sortParts) {
            String[] colSort = part.trim().split(":");
            if (colSort.length == 2) {
                String colId = colSort[0].trim();
                String direction = colSort[1].trim();

                try {
                    SortDirection sortDirection = SortDirection.fromValue(direction);
                    sortRequests.add(new SortRequest(colId, sortDirection));
                } catch (IllegalArgumentException e) {
                    // Invalid direction, use ASC by default
                    sortRequests.add(new SortRequest(colId, SortDirection.ASC));
                }
            } else if (colSort.length == 1) {
                // No direction specified, use ASC by default
                sortRequests.add(new SortRequest(colSort[0].trim(), SortDirection.ASC));
            }
        }

        return sortRequests;
    }

    /**
     * Parse sort parameters from separate sortBy and sortOrder strings.
     *
     * @param sortBy The column to sort by
     * @param sortOrder The sort direction (asc/desc)
     * @return List of SortRequest objects
     */
    public List<SortRequest> parseSortParameters(String sortBy, String sortOrder) {
        if (sortBy == null) {
            return List.of();
        }

        try {
            SortDirection direction = SortDirection.fromValue(sortOrder);
            return List.of(new SortRequest(sortBy, direction));
        } catch (IllegalArgumentException e) {
            return List.of(new SortRequest(sortBy, SortDirection.ASC));
        }
    }

    /**
     * Parse filter parameter from JSON or text format.
     * Format: {"status":"active","amount":{">":100}} or simple text for search
     *
     * @param filter The filter string
     * @return Map of filter criteria
     */
    public Map<String, Object> parseFilterParameter(String filter) {
        if (filter == null || filter.trim().isEmpty()) {
            return Map.of();
        }

        try {
            if (filter.startsWith("{") && filter.endsWith("}")) {
                // JSON filter - for now return empty, proper parsing could be added
                return Map.of();
            } else {
                // Simple text search
                return Map.of("search", filter);
            }
        } catch (Exception e) {
            // On parsing error, use as text search
            return Map.of("search", filter);
        }
    }

    /**
     * Convert a map to a JsonPatchOperation.
     *
     * @param patchMap The map containing op, path, value
     * @return The JsonPatchOperation
     */
    public JsonPatchOperation mapToJsonPatchOperation(Map<String, Object> patchMap) {
        String op = (String) patchMap.get("op");
        String path = (String) patchMap.get("path");
        Object value = patchMap.get("value");

        return new JsonPatchOperation(
            PatchOperation.valueOf(op.toUpperCase()),
            path,
            value,
            null
        );
    }
}

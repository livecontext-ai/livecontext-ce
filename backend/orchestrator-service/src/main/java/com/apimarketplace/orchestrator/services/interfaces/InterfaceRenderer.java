package com.apimarketplace.orchestrator.services.interfaces;

import com.apimarketplace.orchestrator.services.InterfaceRenderService.InterfaceRenderResult;
import com.apimarketplace.orchestrator.services.InterfaceRenderService.SingleItemResult;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Interface for rendering workflow interfaces.
 * Defines the contract for rendering interface templates with data.
 *
 * @see com.apimarketplace.orchestrator.services.InterfaceRenderService
 */
public interface InterfaceRenderer {

    /**
     * Render an interface with workflow run data.
     *
     * @param interfaceId The interface UUID
     * @param runId The workflow run ID
     * @param tenantId The tenant ID
     * @param page Page number (0-based)
     * @param size Page size
     * @param epoch Optional epoch filter (null = all epochs)
     * @return Render result with HTML and pagination info
     */
    InterfaceRenderResult render(UUID interfaceId, String runId, String tenantId, int page, int size, Integer epoch);

    /**
     * Render an interface with datasource data (preview mode).
     *
     * @param interfaceId The interface UUID
     * @param tenantId The tenant ID
     * @param page Page number (0-based)
     * @param size Page size
     * @return Render result with HTML and pagination info
     */
    InterfaceRenderResult renderWithDatasource(UUID interfaceId, String tenantId, int page, int size);

    /**
     * Resolve expressions in an HTML template.
     *
     * @param htmlTemplate The HTML template with expressions
     * @param resolvedData The data to resolve expressions with
     * @return Resolved HTML string
     */
    String resolveHtmlWithExpressions(String htmlTemplate, Map<String, Object> resolvedData);

    /**
     * Count items available for rendering from a workflow run.
     *
     * @param runId The workflow run ID
     * @return Number of items
     */
    long countItems(String runId);

    /**
     * Count items available from a datasource.
     *
     * @param interfaceId The interface UUID
     * @param tenantId The tenant ID
     * @return Number of items
     */
    long countDatasourceItems(UUID interfaceId, String tenantId);

    /**
     * Render a single item with optimized resolution from DB storage.
     *
     * @param interfaceId The interface UUID
     * @param runId The workflow run ID
     * @param tenantId The tenant ID
     * @param epoch The epoch
     * @param itemIndex The item index
     * @return Optional single item result with resolved data
     */
    Optional<SingleItemResult> renderItem(UUID interfaceId, String runId, String tenantId, int epoch, int itemIndex);

    /**
     * Get run-info metadata for an interface (template, config, resolved indices).
     *
     * @param interfaceId The interface UUID
     * @param runId The workflow run ID
     * @param tenantId The tenant ID
     * @return Optional map with htmlTemplate, cssTemplate, jsTemplate, totalItems, resolvedItemIndices
     */
    Optional<Map<String, Object>> getRunInfo(UUID interfaceId, String runId, String tenantId);

    /**
     * Get available SpEL functions for expressions.
     *
     * @return Map of function name to description
     */
    Map<String, String> getAvailableFunctions();
}

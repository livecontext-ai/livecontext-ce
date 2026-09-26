package com.apimarketplace.catalog.service.exception;

import java.util.UUID;

/**
 * Exception thrown when an API tool cannot be found.
 */
public class ToolNotFoundException extends CatalogServiceException {

    /**
     * The code every surface answers a missing tool with (the 404 body's {@code error}),
     * and the one an agent-facing caller keys its "search again" answer on.
     */
    public static final String ERROR_CODE = "TOOL_NOT_FOUND";

    public ToolNotFoundException(UUID toolId) {
        super("Tool not found: " + toolId, ERROR_CODE);
    }

    public ToolNotFoundException(String toolIdOrSlug) {
        super("Tool not found: " + toolIdOrSlug, ERROR_CODE);
    }

    public static ToolNotFoundException byId(UUID toolId) {
        return new ToolNotFoundException(toolId);
    }

    public static ToolNotFoundException bySlug(String slug) {
        return new ToolNotFoundException(slug);
    }
}

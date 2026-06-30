package com.apimarketplace.catalog.service.exception;

import java.util.UUID;

/**
 * Exception thrown when an API tool cannot be found.
 */
public class ToolNotFoundException extends CatalogServiceException {

    public ToolNotFoundException(UUID toolId) {
        super("Tool not found: " + toolId, "TOOL_NOT_FOUND");
    }

    public ToolNotFoundException(String toolIdOrSlug) {
        super("Tool not found: " + toolIdOrSlug, "TOOL_NOT_FOUND");
    }

    public static ToolNotFoundException byId(UUID toolId) {
        return new ToolNotFoundException(toolId);
    }

    public static ToolNotFoundException bySlug(String slug) {
        return new ToolNotFoundException(slug);
    }
}

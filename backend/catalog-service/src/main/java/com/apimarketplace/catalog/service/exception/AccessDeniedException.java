package com.apimarketplace.catalog.service.exception;

/**
 * Exception thrown when a user attempts to access or modify a resource they don't own.
 */
public class AccessDeniedException extends CatalogServiceException {

    private final String userId;
    private final String resourceType;
    private final String resourceId;

    public AccessDeniedException(String message, String userId, String resourceType, String resourceId) {
        super(message, "ACCESS_DENIED");
        this.userId = userId;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
    }

    public String getUserId() {
        return userId;
    }

    public String getResourceType() {
        return resourceType;
    }

    public String getResourceId() {
        return resourceId;
    }

    /**
     * Factory method for API access denied.
     */
    public static AccessDeniedException forApi(String userId, String apiId) {
        return new AccessDeniedException(
            String.format("User '%s' does not have permission to access API '%s'", userId, apiId),
            userId,
            "API",
            apiId
        );
    }

    /**
     * Factory method for Tool access denied.
     */
    public static AccessDeniedException forTool(String userId, String toolId) {
        return new AccessDeniedException(
            String.format("User '%s' does not have permission to access Tool '%s'", userId, toolId),
            userId,
            "Tool",
            toolId
        );
    }

    /**
     * Factory method for generic resource access denied.
     */
    public static AccessDeniedException forResource(String userId, String resourceType, String resourceId) {
        return new AccessDeniedException(
            String.format("User '%s' does not have permission to access %s '%s'", userId, resourceType, resourceId),
            userId,
            resourceType,
            resourceId
        );
    }
}

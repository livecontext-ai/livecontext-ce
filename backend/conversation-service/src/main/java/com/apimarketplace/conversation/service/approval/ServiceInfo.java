package com.apimarketplace.conversation.service.approval;

/**
 * Information about a service that requires approval.
 * Used for displaying service details to the user in the approval prompt.
 *
 * @param serviceType Unique identifier for the service (e.g., "gmail", "slack")
 * @param serviceName Human-readable name (e.g., "Gmail", "Slack")
 * @param description Description of what access is being requested
 * @param iconSlug Icon identifier for UI display
 */
public record ServiceInfo(
    String serviceType,
    String serviceName,
    String description,
    String iconSlug
) {
    /**
     * Create ServiceInfo with minimal required fields.
     */
    public ServiceInfo(String serviceType, String serviceName) {
        this(serviceType, serviceName, null, null);
    }

    /**
     * Create ServiceInfo from just the service type.
     * Name is derived from type by capitalizing first letter.
     */
    public static ServiceInfo fromType(String serviceType) {
        String name = serviceType.substring(0, 1).toUpperCase() + serviceType.substring(1);
        return new ServiceInfo(serviceType, name, null, null);
    }

    /**
     * Create ServiceInfo with name and description.
     */
    public static ServiceInfo of(String serviceType, String serviceName, String description) {
        return new ServiceInfo(serviceType, serviceName, description, null);
    }
}

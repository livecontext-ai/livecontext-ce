package com.apimarketplace.agent.widget;

import java.util.Map;

/**
 * Standard response for widget public endpoints.
 */
public record WidgetResponse(
    String status,
    String message,
    Map<String, Object> data
) {
    public static WidgetResponse success(Map<String, Object> data) {
        return new WidgetResponse("success", null, data);
    }

    public static WidgetResponse error(String message) {
        return new WidgetResponse("error", message, null);
    }

    public static WidgetResponse notFound() {
        return new WidgetResponse("not_found", "Widget not found", null);
    }

    public static WidgetResponse inactive() {
        return new WidgetResponse("inactive", "Widget is not active", null);
    }

    public static WidgetResponse originDenied() {
        return new WidgetResponse("origin_denied", "Origin not allowed", null);
    }
}

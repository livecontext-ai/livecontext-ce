package com.apimarketplace.orchestrator.services.expression;

/**
 * Thrown by {@link ExpressionFunctions#json(Object)} when a non-blank string cannot be parsed as JSON.
 *
 * <p>Carries a truncated preview of the offending value so the inspector can show the user
 * exactly what failed without exposing arbitrarily large payloads in error messages.
 *
 * <p>{@link com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter} catches
 * this exception when resolving step input fields and re-throws it with the field name
 * prepended, so downstream nodes see "json() in field 'generationConfig' failed: ...".
 */
public class JsonParseException extends RuntimeException {

    private static final int PREVIEW_MAX_LENGTH = 80;

    private final String valuePreview;

    public JsonParseException(String message, String valuePreview, Throwable cause) {
        super(message, cause);
        this.valuePreview = valuePreview;
    }

    public String getValuePreview() {
        return valuePreview;
    }

    public static String preview(String value) {
        if (value == null) return "null";
        if (value.length() <= PREVIEW_MAX_LENGTH) return value;
        return value.substring(0, PREVIEW_MAX_LENGTH) + "…";
    }
}

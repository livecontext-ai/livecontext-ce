package com.apimarketplace.auth.variables.domain;

import java.time.Instant;

/**
 * Domain models for workflow variables - the org/personal key/value store
 * referenced in workflow expressions as {@code {{$vars.name}}} (canonical)
 * or {@code {{vars:name}}} (alias).
 *
 * <p>Scope semantics mirror the V362 workspace model: a row with a non-null
 * {@code organizationId} is workspace-shared (all members read, non-VIEWER
 * write); a row with a null {@code organizationId} belongs to the personal
 * scope of {@code tenantId}.
 */
public final class WorkflowVariableModels {

    private WorkflowVariableModels() {
    }

    /** Variable name grammar - must stay valid inside the {{$vars.name}} template form. */
    public static final String NAME_PATTERN = "^[a-zA-Z_][a-zA-Z0-9_]{0,63}$";

    /** Guard against pathological payloads; generous for JSON config blobs. */
    public static final int MAX_VALUE_LENGTH = 100_000;

    public static final int MAX_DESCRIPTION_LENGTH = 500;

    /**
     * How the stored text value is typed when resolved into a workflow
     * expression. The value column is always text (encrypted); the type drives
     * the conversion applied by the internal bundle endpoint so SpEL sees a
     * native number/boolean/map instead of a string.
     */
    public enum ValueType {
        STRING,
        NUMBER,
        BOOLEAN,
        JSON;

        public static ValueType fromValue(String value) {
            if (value == null || value.isBlank()) {
                return STRING;
            }
            for (ValueType type : values()) {
                if (type.name().equalsIgnoreCase(value)) {
                    return type;
                }
            }
            return null;
        }
    }

    /**
     * Main domain entity. {@code value} is plaintext in memory (the repository
     * encrypts/decrypts at the SQL boundary). {@code secret} makes the value
     * write-only: masked to null in every listing (see VariableResponse.from);
     * the runtime bundle still resolves the real value into workflow runs, so
     * resolved values can appear in run outputs like any other parameter.
     */
    public record WorkflowVariable(
            Long id,
            String tenantId,
            String organizationId,
            String name,
            String value,
            ValueType valueType,
            boolean secret,
            String description,
            String createdBy,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    /**
     * Create/update payload. {@code type} is one of {@link ValueType}
     * (case-insensitive), defaults to STRING. {@code secret} (default false)
     * makes the value WRITE-ONLY: it is masked in every listing (UI and
     * agent-facing) and must be re-entered on edit. Runtime resolution
     * ({{$vars.name}} in workflow runs) still uses the real value.
     */
    public record UpsertVariableRequest(
            String name,
            String value,
            String type,
            String description,
            Boolean secret
    ) {
    }

    /**
     * API response shape. {@code scope} is "workspace" or "personal".
     * For {@code secret} variables the value is MASKED to null - the real
     * value never leaves the server through a listing.
     */
    public record VariableResponse(
            Long id,
            String name,
            String value,
            String type,
            String description,
            String scope,
            boolean secret,
            Instant createdAt,
            Instant updatedAt
    ) {
        public static VariableResponse from(WorkflowVariable variable) {
            return new VariableResponse(
                    variable.id(),
                    variable.name(),
                    variable.secret() ? null : variable.value(),
                    variable.valueType().name(),
                    variable.description(),
                    variable.organizationId() != null ? "workspace" : "personal",
                    variable.secret(),
                    variable.createdAt(),
                    variable.updatedAt()
            );
        }
    }
}

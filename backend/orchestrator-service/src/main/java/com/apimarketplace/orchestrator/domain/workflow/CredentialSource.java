package com.apimarketplace.orchestrator.domain.workflow;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Whether an MCP step authenticates with the end-user's own credential or a
 * platform-provisioned credential (which carries a per-call markup).
 * <p>
 * Absent / unknown values decode to {@link #USER} so old plans keep working.
 */
public enum CredentialSource {
    USER("user"),
    PLATFORM("platform");

    private final String wire;

    CredentialSource(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static CredentialSource fromWire(String value) {
        if (value == null || value.isBlank()) return USER;
        String norm = value.trim().toLowerCase(Locale.ROOT);
        return switch (norm) {
            case "platform" -> PLATFORM;
            case "user" -> USER;
            default -> throw new IllegalArgumentException(
                    "Unknown credentialSource: " + value + " (expected 'user' or 'platform')");
        };
    }

    public boolean isPlatform() {
        return this == PLATFORM;
    }
}

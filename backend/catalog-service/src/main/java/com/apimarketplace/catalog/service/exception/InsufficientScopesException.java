package com.apimarketplace.catalog.service.exception;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * V166: thrown by {@code HttpExecutionService.preflightScopeCheck} when a tool declares
 * {@code requiredScopes} that the user's bound OAuth2 credential has not been granted.
 *
 * <p>This exception is intentionally caught at the {@code ApiService.executeApiTool}
 * boundary and converted into a structured error map ({@code success: false,
 * errorCode: "insufficient_scopes", missingScopes: [...]}) rather than propagating to
 * the orchestrator's generic exception handler. The structured shape lets the frontend
 * render a targeted "reconnect to enable" banner instead of a generic 500 page.
 */
public final class InsufficientScopesException extends RuntimeException {

    private final String toolName;
    private final UUID apiId;
    private final String credentialName;
    private final String integration;
    private final Set<String> missingScopes;

    public InsufficientScopesException(String toolName,
                                       UUID apiId,
                                       String credentialName,
                                       String integration,
                                       Set<String> missingScopes) {
        super("Tool '" + toolName + "' requires scopes not granted by credential '"
                + credentialName + "': " + String.join(", ", missingScopes));
        this.toolName = toolName;
        this.apiId = apiId;
        this.credentialName = credentialName;
        this.integration = integration;
        this.missingScopes = missingScopes == null ? Set.of() : Set.copyOf(missingScopes);
    }

    public String getToolName() { return toolName; }
    public UUID getApiId() { return apiId; }
    public String getCredentialName() { return credentialName; }
    public String getIntegration() { return integration; }
    public Set<String> getMissingScopes() { return new HashSet<>(missingScopes); }
}

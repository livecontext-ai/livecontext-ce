package com.apimarketplace.orchestrator.tools.credential;

import com.apimarketplace.agent.domain.ToolParameter;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.registry.ToolCategory;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider;
import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.credential.client.dto.CredentialSummaryDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.apimarketplace.agent.registry.ToolSchemaGenerator.*;

/**
 * Provider for credential discovery tools.
 * Enables agents to discover which external services (Gmail, Slack, etc.)
 * the user has connected, allowing intelligent tool selection.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CredentialToolsProvider implements ToolsProvider {

    private final CredentialClient credentialClient;

    @Override
    public ToolCategory getCategory() {
        return ToolCategory.CATALOG;
    }

    @Override
    public List<AgentToolDefinition> getTools() {
        return List.of(buildGetConnectedServices());
    }

    @Override
    public ToolExecutionResult execute(String toolName, Map<String, Object> parameters, ToolExecutionContext context) {
        if (!"get_connected_services".equals(toolName)) {
            return ToolExecutionResult.failure(ToolErrorCode.TOOL_NOT_FOUND, "Unknown tool: " + toolName);
        }

        String tenantId = context != null ? context.tenantId() : null;
        if (tenantId == null || tenantId.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "User context required");
        }

        try {
            return executeGetUserConnected(tenantId);
        } catch (Exception e) {
            log.error("Error executing get_user_connected for tenant {}: {}", tenantId, e.getMessage(), e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Error: " + e.getMessage());
        }
    }

    // ==================== Tool Definition ====================

    private AgentToolDefinition buildGetConnectedServices() {
        return AgentToolDefinition.builder()
            .name("get_connected_services")
            .description("""
                Discover which external services the user has connected (Gmail, Slack, Calendar, etc.).
                Takes NO parameters - just call get_connected_services().
                Returns: {connected: [{name, integration, status, isDefault, account}], count, hint}.
                Status: 'active' (ready), 'expiring' (still works, token expiring soon), 'needs_reauth' (token revoked or expired; only the user can Reconnect, you cannot use or fix it), 'error' (misconfigured; an admin must fix it, reconnecting alone will not help).
                Only 'isDefault=true' credentials are used when executing tools.

                WHEN TO USE: When uncertain which service the user has (e.g. "check my emails" without specifying Gmail/Outlook).
                SKIP if user explicitly mentions a service (e.g., "send with Gmail").
                """)
            .category(ToolCategory.CATALOG)
            .parameters(List.of())
            .requiredParameters(List.of())
            .inputSchema(Map.of("type", "object", "properties", Map.of()))
            .helpText("""
                Discovers user's connected external services.

                Returns:
                {
                  "connected": [
                    {"name": "Gmail", "integration": "gmail", "status": "active"},
                    {"name": "Slack", "integration": "slack", "status": "active"}
                  ],
                  "hint": "User has Gmail and Slack connected. Use catalog(action='search') to find tools."
                }

                Status values:
                - active: Ready to use
                - expiring: Token expiring soon, still works
                - needs_reauth: Token was revoked or expired. Only the user can re-authorize (Reconnect); you cannot use this credential or fix it yourself.
                - error: Configuration problem (bad client secret, missing scope). An admin must fix it; reconnecting alone will not resolve it.
                """)
            .requiresAuth(true)
            .tags(List.of("credential", "discovery", "integration", "oauth"))
            .build();
    }

    // ==================== Tool Execution ====================

    private ToolExecutionResult executeGetUserConnected(String tenantId) {
        List<CredentialSummaryDto> credentials = credentialClient.getAllCredentials(tenantId);

        List<Map<String, Object>> connected = credentials.stream()
            .map(c -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", c.getName());
                entry.put("integration", c.getIntegration());
                entry.put("status", c.getStatus() != null ? c.getStatus().toLowerCase() : "unknown");
                entry.put("isDefault", c.isDefault());

                // Extract account identifier if available (e.g., email address)
                String account = extractAccountIdentifier(c);
                if (account != null) {
                    entry.put("account", account);
                }

                return entry;
            })
            .toList();

        // Count default credentials (only defaults are used for execution)
        long defaultCount = connected.stream()
            .filter(c -> Boolean.TRUE.equals(c.get("isDefault")))
            .count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("connected", connected);
        result.put("count", connected.size());
        result.put("defaultCount", defaultCount);

        // Build intelligent hint
        if (connected.isEmpty()) {
            result.put("hint", "No services connected yet. You can still use catalog(action='search') - some APIs are public or will prompt for setup if needed.");
        } else {
            List<String> defaultServiceNames = connected.stream()
                .filter(c -> Boolean.TRUE.equals(c.get("isDefault")))
                .map(c -> (String) c.get("name"))
                .toList();

            String servicesList = String.join(", ", defaultServiceNames);
            result.put("hint", String.format(
                "User has %d default credential(s) ready for execution: %s. Only default credentials are used when executing tools.",
                defaultCount,
                servicesList.isEmpty() ? "(none)" : servicesList
            ));

            // Add specific hints for common use cases
            boolean hasEmail = connected.stream()
                .anyMatch(c -> "gmail".equals(c.get("integration")) || "outlook".equals(c.get("integration")));
            boolean hasMessaging = connected.stream()
                .anyMatch(c -> "slack".equals(c.get("integration")) || "discord".equals(c.get("integration")));
            boolean hasCalendar = connected.stream()
                .anyMatch(c -> "google-calendar".equals(c.get("integration")) || "outlook-calendar".equals(c.get("integration")));

            Map<String, String> categoryHints = new LinkedHashMap<>();
            if (hasEmail) {
                String emailProvider = connected.stream()
                    .filter(c -> "gmail".equals(c.get("integration")) || "outlook".equals(c.get("integration")))
                    .map(c -> (String) c.get("integration"))
                    .findFirst().orElse("email");
                categoryHints.put("email", String.format("For emails: catalog(action='search')(\"%s list messages\")", emailProvider));
            }
            if (hasMessaging) {
                String msgProvider = connected.stream()
                    .filter(c -> "slack".equals(c.get("integration")) || "discord".equals(c.get("integration")))
                    .map(c -> (String) c.get("integration"))
                    .findFirst().orElse("messaging");
                categoryHints.put("messaging", String.format("For messages: catalog(action='search')(\"%s send message\")", msgProvider));
            }
            if (hasCalendar) {
                categoryHints.put("calendar", "For calendar: catalog(action='search')(\"calendar list events\")");
            }

            if (!categoryHints.isEmpty()) {
                result.put("examples", categoryHints);
            }
        }

        return ToolExecutionResult.success(result);
    }

    /**
     * Extract a human-readable account identifier from credential data.
     * For OAuth services, this is typically the email or username.
     */
    private String extractAccountIdentifier(CredentialSummaryDto credential) {
        Map<String, Object> data = credential.getCredentialData();
        if (data == null) {
            return null;
        }

        // Try common OAuth fields
        if (data.containsKey("email")) {
            return (String) data.get("email");
        }
        if (data.containsKey("user_email")) {
            return (String) data.get("user_email");
        }
        if (data.containsKey("username")) {
            return (String) data.get("username");
        }
        if (data.containsKey("workspace")) {
            return (String) data.get("workspace");
        }
        if (data.containsKey("team_name")) {
            return (String) data.get("team_name");
        }

        return null;
    }
}

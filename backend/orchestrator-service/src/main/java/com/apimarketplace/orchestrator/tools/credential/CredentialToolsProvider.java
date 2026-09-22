package com.apimarketplace.orchestrator.tools.credential;

import com.apimarketplace.agent.domain.ToolParameter;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.registry.ToolCategory;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider;
import com.apimarketplace.agent.tools.credential.SelectableAccounts;
import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.credential.client.dto.CredentialSummaryDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
                Returns: {connected: [{name, integration, status, isDefault, account, scopes}], count, defaultCount, hint}.
                Status: 'active' (ready), 'expiring' (still works, token expiring soon), 'needs_reauth' (token revoked or expired; only the user can Reconnect, you cannot use or fix it), 'error' (misconfigured; an admin must fix it, reconnecting alone will not help).
                scopes lists what an OAuth account was actually granted, and is absent for a credential that has no scope concept. Two accounts of one integration routinely differ here, and that difference is what decides which endpoints each one can run. You do not have to compare them yourself: catalog(action='response_schema', tool_id='<uuid>') names the accounts that can run that endpoint.
                isDefault=true marks the one that runs when nothing names another. The others are NOT dead: name one and it runs instead, either on a direct call - catalog(action='execute', credential_name='<name>') - or on a workflow mcp step, by setting credential_selector to an expression that resolves to that entry's name, which is how one workflow serves several accounts of the same integration instead of being duplicated per account. Selectable either way are the entries whose status is exactly 'active' (an expiring one is refused too, not only needs_reauth and error), whose name is not shared with another active entry of the same integration, and whose name is not a positive whole number (that is read as a credential id). Capitalisation and surrounding spaces are ignored when matching; nothing else about the name is. An unmatched name FAILS the call rather than falling back to the default.

                WHEN TO USE: when uncertain which service the user has (e.g. "check my emails" without specifying Gmail/Outlook), and whenever you need the exact NAME of an account, which is the only way to discover the values credential_selector accepts.
                SKIP if the user names the service AND you do not need an account name (e.g. "send with Gmail" when they have one Gmail).
                """)
            .category(ToolCategory.CATALOG)
            .parameters(List.of())
            .requiredParameters(List.of())
            .inputSchema(Map.of("type", "object", "properties", Map.of()))
            .helpText("""
                Discovers user's connected external services.

                Returns:
                {
                  "count": 3,
                  "defaultCount": 2,
                  "connected": [
                    {"name": "Gmail", "integration": "gmail", "status": "active", "isDefault": true, "account": "me@example.com", "scopes": ["https://www.googleapis.com/auth/gmail.send"]},
                    {"name": "Client A", "integration": "instagram", "status": "active", "isDefault": true},
                    {"name": "Client B", "integration": "instagram", "status": "active", "isDefault": false}
                  ],
                  "hint": "User has 2 default credential(s) ready for execution: Gmail, Client A. Executing a tool directly uses the default one unless the call names another with credential_name. This is what YOUR workspace holds; a workflow runs under its owner's, so a name taken from here resolves only if the two are the same. Also held and selectable BY NAME, either on a direct catalog execute call through its credential_name argument, or by a workflow step that names one in its credential_selector (active only): \\"Client B\\" (instagram)."
                }

                Two entries share an integration when the user holds several accounts of it,
                as with Client A and Client B above. Executing a tool directly uses the default
                one unless the call names another: pass credential_name on a catalog execute
                call, or, for a workflow step, pass the name through the step's
                credential_selector. Copy the name from here rather than retyping it:
                only capitalisation and surrounding spaces are ignored when matching, so any
                other reformatting (a hyphen for a space, a shortened form) selects nothing and
                the call fails rather than falling back.

                The scopes field is what decides which of two accounts of one integration can
                run a given endpoint. Ask for the endpoint contract with
                catalog(action='response_schema', tool_id='<uuid>') and its credential block
                names the accounts that can run it, and what to do when none can.

                This lists what the CALLING workspace holds. A workflow runs under its owner's
                workspace, so a name taken from here is only guaranteed to resolve if you are
                building for the same workspace you are calling from.

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
                entry.put("status", c.getStatus() != null ? c.getStatus().toLowerCase(Locale.ROOT) : "unknown");
                entry.put("isDefault", c.isDefault());

                // Extract account identifier if available (e.g., email address)
                String account = extractAccountIdentifier(c);
                if (account != null) {
                    entry.put("account", account);
                }
                // What this account was actually granted. Two accounts of one integration
                // routinely differ, and that difference is the whole reason one of them
                // cannot run an endpoint the other can. Omitted when empty rather than sent
                // as [], so a key with no scope concept does not read as a revoked one.
                if (c.getScopes() != null && !c.getScopes().isEmpty()) {
                    entry.put("scopes", c.getScopes());
                }

                return entry;
            })
            .toList();

        // Count default credentials (the direct-execution path resolves is_default first)
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
                // A null name is storable, and String.join renders one as the literal
                // "null", so the summary would read "ready for execution: Gmail, null".
                // The listing itself still shows the entry; only this summary skips it.
                .filter(name -> name != null && !name.isBlank())
                .toList();

            String servicesList = String.join(", ", defaultServiceNames);
            // The hint is the agent's window into state, so it outranks any static
            // description: it once carried the retracted claim too, and correcting the
            // description alone left that claim being served on every call. It also
            // listed default names only, hiding exactly the entries a workflow step
            // selects by name. (Phrased carefully: the discoverability guard scans this
            // file for the claim, comments included, and a paraphrase here would fail
            // the build. Rewrite around it rather than loosening the pattern.)
            //
            // Which entries qualify is SelectableAccounts' problem, not this method's,
            // because credential(action='list') shapes the same rows for chat agents and
            // the two answers must not diverge again.
            String extra = SelectableAccounts.offer(connected);
            result.put("hint", String.format(
                "User has %d default credential(s) ready for execution: %s. Executing a tool "
                + "directly uses the default one unless the call names another with "
                + "credential_name. This is what YOUR workspace holds; a "
                + "workflow runs under its owner's, so a name taken from here resolves only if "
                + "the two are the same.%s",
                defaultCount,
                servicesList.isEmpty() ? "(none)" : servicesList,
                extra
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

package com.apimarketplace.orchestrator.tools.credential;

import com.apimarketplace.agent.registry.ToolCategory;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.credential.client.dto.CredentialSummaryDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CredentialToolsProvider} - the agent-facing
 * {@code get_connected_services} discovery tool. This surface had ZERO coverage
 * before the 2026-06-23 test-strategy audit.
 * <p>
 * The provider is a thin shaper over {@link CredentialClient#getAllCredentials}:
 * the tests pin the dispatch guard, the tenant guard, the empty-vs-populated hint
 * shaping, status lowercasing, the account-identifier precedence chain, the
 * default-count gating, the per-category catalog hints, and the exception wrap.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CredentialToolsProvider (get_connected_services)")
class CredentialToolsProviderTest {

    private static final String TENANT = "tenant-1";

    @Mock private CredentialClient credentialClient;
    @InjectMocks private CredentialToolsProvider provider;

    private static CredentialSummaryDto cred(String name, String integration, String status,
                                             boolean isDefault, Map<String, Object> data) {
        CredentialSummaryDto c = new CredentialSummaryDto();
        c.setName(name);
        c.setIntegration(integration);
        c.setStatus(status);
        c.setDefault(isDefault);
        c.setCredentialData(data);
        return c;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ToolExecutionResult r) {
        return (Map<String, Object>) r.data();
    }

    private ToolExecutionResult call() {
        return provider.execute("get_connected_services", Map.of(), ToolExecutionContext.of(TENANT));
    }

    // ── metadata ────────────────────────────────────────────────────────

    @Test
    @DisplayName("category is CATALOG and the single tool requires auth")
    void metadata() {
        assertThat(provider.getCategory()).isEqualTo(ToolCategory.CATALOG);
        assertThat(provider.getTools()).hasSize(1);
        assertThat(provider.getTools().get(0).name()).isEqualTo("get_connected_services");
        assertThat(provider.getTools().get(0).requiresAuth()).isTrue();
    }

    // ── dispatch guard ──────────────────────────────────────────────────

    @Test
    @DisplayName("an unknown tool name -> TOOL_NOT_FOUND, client untouched")
    void unknownTool() {
        ToolExecutionResult r = provider.execute("nope", Map.of(), ToolExecutionContext.of(TENANT));
        assertThat(r.success()).isFalse();
        assertThat(r.errorCode()).isEqualTo(ToolErrorCode.TOOL_NOT_FOUND);
        verifyNoInteractions(credentialClient);
    }

    @Test
    @DisplayName("the tool name is case-sensitive - a wrong-case name -> TOOL_NOT_FOUND")
    void wrongCaseTool() {
        ToolExecutionResult r = provider.execute("GET_CONNECTED_SERVICES", Map.of(), ToolExecutionContext.of(TENANT));
        assertThat(r.errorCode()).isEqualTo(ToolErrorCode.TOOL_NOT_FOUND);
        verifyNoInteractions(credentialClient);
    }

    // ── tenant guard ────────────────────────────────────────────────────

    @Test
    @DisplayName("a null context -> MISSING_PARAMETER, client untouched")
    void nullContext() {
        ToolExecutionResult r = provider.execute("get_connected_services", Map.of(), null);
        assertThat(r.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
        verifyNoInteractions(credentialClient);
    }

    @Test
    @DisplayName("a null or blank tenantId -> MISSING_PARAMETER, client untouched")
    void blankTenant() {
        assertThat(provider.execute("get_connected_services", Map.of(), ToolExecutionContext.empty())
                .errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
        assertThat(provider.execute("get_connected_services", Map.of(), ToolExecutionContext.of("  "))
                .errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
        verifyNoInteractions(credentialClient);
    }

    // ── empty state ─────────────────────────────────────────────────────

    @Test
    @DisplayName("no credentials -> success with a no-services hint, no UI setupUrl, no examples")
    void emptyState() {
        when(credentialClient.getAllCredentials(TENANT)).thenReturn(List.of());

        ToolExecutionResult r = call();

        assertThat(r.success()).isTrue();
        Map<String, Object> d = data(r);
        assertThat(d).containsEntry("count", 0).containsEntry("defaultCount", 0L)
                .containsKey("hint")
                .doesNotContainKey("setupUrl")
                .doesNotContainKey("examples");
        assertThat((String) d.get("hint")).contains("No services connected");
    }

    // ── populated state ─────────────────────────────────────────────────

    @Test
    @DisplayName("lowercases status, surfaces isDefault, counts only defaults, and lists default names in the hint")
    void populatedShaping() {
        when(credentialClient.getAllCredentials(TENANT)).thenReturn(List.of(
                cred("Gmail", "gmail", "ACTIVE", true, Map.of("email", "me@x.com")),
                cred("Slack", "slack", "Active", false, Map.of("team_name", "acme"))));

        ToolExecutionResult r = call();

        Map<String, Object> d = data(r);
        assertThat(d).containsEntry("count", 2).containsEntry("defaultCount", 1L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> connected = (List<Map<String, Object>>) d.get("connected");
        assertThat(connected.get(0))
                .containsEntry("name", "Gmail").containsEntry("status", "active")
                .containsEntry("isDefault", true).containsEntry("account", "me@x.com");
        assertThat(connected.get(1)).containsEntry("status", "active").containsEntry("isDefault", false);
        // Only the default (Gmail) is named in the execution hint.
        String hint = (String) d.get("hint");
        assertThat(hint).contains("1 default").contains("Gmail").doesNotContain("Slack");
    }

    @Test
    @DisplayName("a null status defaults to 'unknown'")
    void nullStatusUnknown() {
        when(credentialClient.getAllCredentials(TENANT)).thenReturn(List.of(
                cred("X", "x", null, false, null)));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> connected = (List<Map<String, Object>>) data(call()).get("connected");
        assertThat(connected.get(0)).containsEntry("status", "unknown");
    }

    @Test
    @DisplayName("account identifier follows the precedence email > user_email > username > workspace > team_name")
    void accountIdentifierPrecedence() {
        when(credentialClient.getAllCredentials(TENANT)).thenReturn(List.of(
                cred("A", "a", "active", false, mapOf("email", "e", "user_email", "ue", "username", "u")),
                cred("B", "b", "active", false, mapOf("user_email", "ue", "username", "u")),
                cred("C", "c", "active", false, mapOf("username", "u")),
                cred("D", "d", "active", false, mapOf("workspace", "ws", "team_name", "tn")),
                cred("E", "e", "active", false, mapOf("team_name", "tn")),
                cred("F", "f", "active", false, null)));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> connected = (List<Map<String, Object>>) data(call()).get("connected");
        assertThat(connected.get(0)).containsEntry("account", "e");
        assertThat(connected.get(1)).containsEntry("account", "ue");
        assertThat(connected.get(2)).containsEntry("account", "u");
        assertThat(connected.get(3)).containsEntry("account", "ws");
        assertThat(connected.get(4)).containsEntry("account", "tn");
        assertThat(connected.get(5)).doesNotContainKey("account"); // null data -> no account key
    }

    // ── category hints ──────────────────────────────────────────────────

    @Test
    @DisplayName("surfaces email + messaging + calendar catalog hints when those integrations are present")
    void categoryHints() {
        when(credentialClient.getAllCredentials(TENANT)).thenReturn(List.of(
                cred("Gmail", "gmail", "active", true, null),
                cred("Slack", "slack", "active", true, null),
                cred("Cal", "google-calendar", "active", true, null)));

        @SuppressWarnings("unchecked")
        Map<String, String> examples = (Map<String, String>) data(call()).get("examples");
        assertThat(examples).containsKeys("email", "messaging", "calendar");
        assertThat(examples.get("email")).contains("gmail");
    }

    @Test
    @DisplayName("outlook / discord / outlook-calendar also trigger their category hints")
    void categoryHintsAlternateProviders() {
        when(credentialClient.getAllCredentials(TENANT)).thenReturn(List.of(
                cred("Outlook", "outlook", "active", true, null),
                cred("Discord", "discord", "active", true, null),
                cred("OutCal", "outlook-calendar", "active", true, null)));

        @SuppressWarnings("unchecked")
        Map<String, String> examples = (Map<String, String>) data(call()).get("examples");
        assertThat(examples).containsKeys("email", "messaging", "calendar");
        assertThat(examples.get("email")).contains("outlook");
    }

    @Test
    @DisplayName("no recognized category -> no examples key")
    void noCategoryMatch() {
        when(credentialClient.getAllCredentials(TENANT)).thenReturn(List.of(
                cred("Stripe", "stripe", "active", true, null)));

        assertThat(data(call())).doesNotContainKey("examples");
    }

    // ── exception wrap ──────────────────────────────────────────────────

    @Test
    @DisplayName("a client exception is wrapped as EXECUTION_FAILED, not propagated")
    void clientExceptionWrapped() {
        when(credentialClient.getAllCredentials(TENANT)).thenThrow(new RuntimeException("auth-service down"));

        ToolExecutionResult r = call();

        assertThat(r.success()).isFalse();
        assertThat(r.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
        assertThat(r.error()).contains("auth-service down");
    }

    // ── hint + status edge branches ─────────────────────────────────────

    @Test
    @DisplayName("a populated list with zero defaults -> defaultCount 0 and a '(none)' execution hint")
    void allNonDefaultNoneHint() {
        when(credentialClient.getAllCredentials(TENANT)).thenReturn(List.of(
                cred("Gmail", "gmail", "active", false, null)));

        Map<String, Object> d = data(call());
        assertThat(d).containsEntry("count", 1).containsEntry("defaultCount", 0L);
        assertThat((String) d.get("hint")).contains("0 default").contains("(none)");
    }

    @Test
    @DisplayName("the documented status values EXPIRING/ERROR are lowercased to the contract form")
    void documentedStatusValuesLowercased() {
        when(credentialClient.getAllCredentials(TENANT)).thenReturn(List.of(
                cred("A", "a", "EXPIRING", false, null),
                cred("B", "b", "ERROR", false, null)));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> connected = (List<Map<String, Object>>) data(call()).get("connected");
        assertThat(connected.get(0)).containsEntry("status", "expiring");
        assertThat(connected.get(1)).containsEntry("status", "error");
    }

    @Test
    @DisplayName("a NEEDS_REAUTH credential (revoked OAuth token) is lowercased to the emitted 'needs_reauth' status the agent must handle")
    void needsReauthStatusLowercased() {
        // A revoked/expired OAuth credential (e.g. a Google Cloud integration whose
        // refresh_token Google rejected with invalid_grant) surfaces as needs_reauth.
        // The tool emits the raw lowercased status, so the agent must be able to see it.
        when(credentialClient.getAllCredentials(TENANT)).thenReturn(List.of(
                cred("Google BigQuery", "googlebigquery", "NEEDS_REAUTH", false, null)));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> connected = (List<Map<String, Object>>) data(call()).get("connected");
        assertThat(connected.get(0)).containsEntry("status", "needs_reauth");
    }

    @Test
    @DisplayName("doc/impl parity: description + helpText document ALL emittable statuses incl. needs_reauth, and convey that needs_reauth is user-only (agent cannot fix)")
    void needsReauthStatusIsDocumented() {
        // Regression for the doc drift: executeGetUserConnected emits needs_reauth (the
        // raw lowercased status) but the tool metadata previously listed only
        // active/expiring/error, so an agent branching on the documented set would
        // silently mishandle a revoked credential. Assert not just token-presence but
        // that the meaning is right (user re-authorizes; agent cannot use/fix it) and
        // that adding needs_reauth did NOT drop any previously-documented status.
        var tool = provider.getTools().get(0);
        assertThat(tool.description())
                .as("description must document every emittable status")
                .contains("active").contains("expiring").contains("needs_reauth").contains("error");
        assertThat(tool.helpText())
                .as("helpText must document every emittable status")
                .contains("active").contains("expiring").contains("needs_reauth").contains("error");
        // Semantic guard: a bare contains(\"needs_reauth\") would still pass if the
        // surrounding sentence were misleading. Pin the actionable meaning.
        assertThat(tool.helpText())
                .as("helpText must tell the agent needs_reauth is resolved by the USER, not the agent")
                .containsPattern("(?s)needs_reauth.*(user|re-authorize|Reconnect)");
    }

    /** Mutable, null-tolerant map builder (Map.of rejects nulls and is immutable). */
    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}

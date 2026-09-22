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
    @DisplayName("lowercases status, surfaces isDefault, counts defaults, and names both the default and the selectable ones in the hint")
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
        // The hint is what the agent actually reads, so it has to name BOTH: the default
        // that a direct tool call uses, and the non-default that a workflow step can pick
        // by name. Naming only the default is what taught agents the second one was dead.
        String hint = (String) d.get("hint");
        assertThat(hint).contains("1 default").contains("Gmail")
                .contains("Slack").contains("credential_selector");
    }

    @Test
    @DisplayName("the hint offers only ACTIVE non-defaults, never a revoked or expiring one")
    void hintOffersOnlyActiveNonDefaults() {
        // The hint says these are usable by credential_selector, and the run-time
        // matcher accepts 'active' only, fail-closed. Every non-active entry offered
        // here therefore buys the agent a FAILED step, not a wrong account. Note that
        // 'expiring' is the trap: the same tool describes it as "still works".
        when(credentialClient.getAllCredentials(TENANT)).thenReturn(List.of(
                cred("Main", "instagram", "active", true, null),
                cred("Usable", "instagram", "active", false, null),
                cred("Revoked", "instagram", "needs_reauth", false, null),
                cred("Broken", "instagram", "error", false, null),
                cred("Expiring", "instagram", "expiring", false, null)));

        String hint = (String) data(call()).get("hint");

        assertThat(hint).contains("Usable");
        assertThat(hint).doesNotContain("Revoked").doesNotContain("Broken").doesNotContain("Expiring");
    }

    @Test
    @DisplayName("the hint drops a name two active accounts of one integration share")
    void hintDropsAmbiguousNames() {
        // Naming a duplicate selects NEITHER account, so offering it is offering a
        // guaranteed failure. Same spelling under a DIFFERENT integration is not
        // ambiguous and must survive.
        when(credentialClient.getAllCredentials(TENANT)).thenReturn(List.of(
                cred("Main", "instagram", "active", true, null),
                cred("Client", "instagram", "active", false, null),
                cred(" client ", "instagram", "active", false, null),
                cred("Client", "slack", "active", false, null)));

        String hint = (String) data(call()).get("hint");

        // The matcher trims and ignores case, so " client " collides with "Client".
        assertThat(hint).doesNotContain("(instagram)");
        assertThat(hint).contains("\"Client\" (slack)");
    }

    @Test
    @DisplayName("a DEFAULT credential with no name does not print as the literal null")
    void hintSkipsANamelessDefault() {
        // The summary lists default names with String.join, which renders a null as the
        // four characters "null", so the hint read "ready for execution: Gmail, null".
        // The entry still appears in `connected`; only this one sentence skips it.
        when(credentialClient.getAllCredentials(TENANT)).thenReturn(List.of(
                cred("Gmail", "gmail", "active", true, null),
                cred(null, "slack", "active", true, null),
                cred("   ", "notion", "active", true, null)));

        String hint = (String) data(call()).get("hint");

        assertThat(hint).contains("Gmail").doesNotContain("null");
    }

    @Test
    @DisplayName("the hint drops a numeric name, which resolves as an id and never as a name")
    void hintDropsNumericNames() {
        // A positive whole number short-circuits to the id path before the name path is
        // consulted, so naming "42" looks up credential 42. The description says so; the
        // hint said otherwise, and the hint is what the agent reads as state.
        when(credentialClient.getAllCredentials(TENANT)).thenReturn(List.of(
                cred("Main", "instagram", "active", true, null),
                cred("42", "instagram", "active", false, null),
                cred("0", "instagram", "active", false, null)));

        String hint = (String) data(call()).get("hint");

        assertThat(hint).doesNotContain("\"42\" (instagram)");
        // "0" is NOT an id: positiveId refuses anything <= 0, so it stays reachable by
        // name. This is why the rule is written as POSITIVE whole number everywhere.
        assertThat(hint).contains("\"0\" (instagram)");
    }

    @Test
    @DisplayName("the hint drops an entry with no name or no integration, which can match nothing")
    void hintDropsUnattributableEntries() {
        when(credentialClient.getAllCredentials(TENANT)).thenReturn(List.of(
                cred("Main", "instagram", "active", true, null),
                cred(null, "instagram", "active", false, null),
                cred("   ", "instagram", "active", false, null),
                cred("Orphan", null, "active", false, null)));

        String hint = (String) data(call()).get("hint");

        // Asserted on the OFFER form: a bare doesNotContain("null") passes for free,
        // since the default-names half of the hint could never have produced it. Also an
        // absence guard, green on pre-change code too; it pins that the filters do not
        // regress, not that they exist.
        assertThat(hint).doesNotContain("(instagram)").doesNotContain("Orphan");
        assertThat(hint).doesNotContain("credential_selector");
    }

    @Test
    @DisplayName("an integration spelled with different case is still one namespace for collisions")
    void hintTreatsIntegrationCaseInsensitively() {
        // The integration column is not guaranteed lower-cased. Splitting "Instagram" and
        // "instagram" into two buckets would make an ambiguous pair look unambiguous and
        // offer both, which is the exact failure the collision filter exists to prevent.
        // Another absence guard, so also green pre-change; it discriminates against the
        // case-splitting mutation, not against the feature being missing.
        when(credentialClient.getAllCredentials(TENANT)).thenReturn(List.of(
                cred("Main", "instagram", "active", true, null),
                cred("Shared", "Instagram", "active", false, null),
                cred("Shared", "instagram", "active", false, null)));

        assertThat((String) data(call()).get("hint")).doesNotContain("Shared");
    }

    @Test
    @DisplayName("each offered name carries its integration, so the agent knows which step it fits")
    void hintQualifiesNamesWithTheirIntegration() {
        when(credentialClient.getAllCredentials(TENANT)).thenReturn(List.of(
                cred("Gmail", "gmail", "active", true, null),
                cred("Client B", "instagram", "active", false, null)));

        assertThat((String) data(call()).get("hint")).contains("\"Client B\" (instagram)");
    }

    @Test
    @DisplayName("a long list of accounts is capped and says so, instead of growing without bound")
    void hintCapsTheOfferedList() {
        // Without a cap this sentence grows with the account's credential count and is
        // served on EVERY call. A silent truncation would read as "these are all".
        List<CredentialSummaryDto> many = new java.util.ArrayList<>();
        many.add(cred("Main", "instagram", "active", true, null));
        for (int i = 1; i <= 20; i++) {
            many.add(cred("Acct " + i, "instagram", "active", false, null));
        }
        when(credentialClient.getAllCredentials(TENANT)).thenReturn(many);

        String hint = (String) data(call()).get("hint");

        assertThat(hint).contains("\"Acct 1\" (instagram)").contains("\"Acct 12\" (instagram)");
        assertThat(hint).doesNotContain("\"Acct 13\" (instagram)");
        assertThat(hint).contains("and 8 more in the connected list");
    }

    @Test
    @DisplayName("with no non-default credential the hint omits the selector clause entirely")
    void hintOmitsSelectorClauseWhenEveryCredentialIsDefault() {
        when(credentialClient.getAllCredentials(TENANT)).thenReturn(List.of(
                cred("Gmail", "gmail", "active", true, null)));

        String hint = (String) data(call()).get("hint");

        // Nothing is selectable by name here, so mentioning the field would send the
        // agent looking for a second account that does not exist. NOTE this is an
        // absence guard: it also passed before the clause existed at all, so it proves
        // the silence is correct, not that the feature is present. The presence tests
        // above carry that weight.
        assertThat(hint).contains("Gmail").doesNotContain("credential_selector").doesNotContain("Also held");
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

    // -- granted scopes -------------------------------------------------

    @Test
    @DisplayName("an account granted scopes carries them, because that is what decides which endpoints it can run")
    void surfacesGrantedScopes() {
        // Two accounts of one integration routinely differ here, and until this was
        // listed the difference was invisible: an agent could see two Gmail accounts and
        // nothing to tell them apart, then be refused on the one the default resolves to.
        CredentialSummaryDto perso = cred("Perso", "gmail", "active", true, null);
        perso.setScopes(List.of("https://www.googleapis.com/auth/gmail.send"));
        when(credentialClient.getAllCredentials(TENANT)).thenReturn(List.of(perso));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> connected = (List<Map<String, Object>>) data(call()).get("connected");
        assertThat(connected.get(0).get("scopes"))
                .isEqualTo(List.of("https://www.googleapis.com/auth/gmail.send"));
    }

    @Test
    @DisplayName("a credential with no scope concept omits the field rather than reporting an empty grant")
    void omitsScopesWhenThereAreNone() {
        // An API key has no scopes. Emitting [] would read as "granted nothing", which is
        // what a revoked OAuth account looks like.
        CredentialSummaryDto apiKey = cred("Stripe", "stripe", "active", true, null);
        when(credentialClient.getAllCredentials(TENANT)).thenReturn(List.of(apiKey));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> connected = (List<Map<String, Object>>) data(call()).get("connected");
        assertThat(connected.get(0)).doesNotContainKey("scopes");

        apiKey.setScopes(List.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> again = (List<Map<String, Object>>) data(call()).get("connected");
        assertThat(again.get(0)).doesNotContainKey("scopes");
    }

    // -- the documented sample must be the answer the runtime gives ------

    @Test
    @DisplayName("the hint in helpText repeats the sentences the runtime hint actually produces")
    void documentedHintMatchesTheRuntimeHint() {
        // The helpText carries a worked example of the response, hint included. Nothing
        // compared the two, so changing the runtime sentence left the documented one
        // stating the opposite - and the documented one is what an agent reads before it
        // has ever called. Caught exactly that way while adding credential_name.
        CredentialSummaryDto gmail = cred("Gmail", "gmail", "active", true, null);
        CredentialSummaryDto clientA = cred("Client A", "instagram", "active", true, null);
        CredentialSummaryDto clientB = cred("Client B", "instagram", "active", false, null);
        when(credentialClient.getAllCredentials(TENANT))
                .thenReturn(List.of(gmail, clientA, clientB));

        String runtimeHint = (String) data(call()).get("hint");
        String helpText = provider.getTools().get(0).helpText();

        // Sentence by sentence rather than whole: the sample is JSON-escaped and names
        // example accounts, so only the invariant prose can be compared.
        for (String sentence : List.of(
                "Executing a tool directly uses the default one unless the call names another"
                        + " with credential_name.",
                "This is what YOUR workspace holds",
                "Also held and selectable BY NAME, either on a direct catalog execute call"
                        + " through its credential_name argument, or by a workflow step that names"
                        + " one in its credential_selector (active only)")) {
            assertThat(runtimeHint).as("runtime hint lost: %s", sentence).contains(sentence);
            assertThat(helpText).as("documented sample drifted from the runtime hint: %s", sentence)
                    .contains(sentence);
        }
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

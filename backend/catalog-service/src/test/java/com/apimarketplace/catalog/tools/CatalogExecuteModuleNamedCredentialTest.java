package com.apimarketplace.catalog.tools;

import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Running one call on a NAMED account instead of the default one.
 *
 * <p>An id is not something a chat agent can learn - ids reach the module through the
 * execution context, which tool arguments never touch. A name is: it is in every
 * credential listing the agent reads. Until this existed, an agent could SEE that the
 * default account lacked a scope and that another account held it, and still had no way
 * to run on the other one.
 *
 * <p>Two halves, both load-bearing. WHAT is forwarded (the selection the catalog already
 * implements, strict, so an unmatched name refuses instead of quietly running on the
 * default), and what is REFUSED before anything runs.
 */
@DisplayName("CatalogExecuteModule - running on a named account")
class CatalogExecuteModuleNamedCredentialTest {

    private static Map<String, Object> request(String name, String source) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        if (name != null) parameters.put("credential_name", name);
        if (source != null) parameters.put("credential_source", source);
        return parameters;
    }

    private static ToolExecutionContext context() {
        return new ToolExecutionContext("tenant-1", Map.of(), Map.of(), Set.of(),
                null, null, null, null);
    }

    private static Map<String, Object> forwarded(Map<String, Object> parameters) {
        Map<String, Object> body = new LinkedHashMap<>();
        CatalogExecuteModule.applyCredentialChoice(body, parameters, context());
        return body;
    }

    @Nested
    @DisplayName("what travels upstream")
    class WhatTravels {

        @Test
        @DisplayName("a name is sent as the run-time selection, STRICT, on the caller's own pool")
        void theWholeTriple() {
            // All three or none. The catalog refuses a name without the strict flag, and a
            // selection that does not state the user pool, because the branches that would
            // serve it never read the selection at all.
            assertThat(forwarded(request("Boulot", null)))
                    .containsEntry("selectedCredentialName", "Boulot")
                    .containsEntry("credentialSelectionStrict", true)
                    .containsEntry("credentialSource", "user");
        }

        @Test
        @DisplayName("naming an account implies the caller's own pool even when the caller said user")
        void explicitUserSourceIsCompatible() {
            assertThat(forwarded(request("Boulot", "user")))
                    .containsEntry("credentialSource", "user")
                    .containsEntry("selectedCredentialName", "Boulot");
        }

        @Test
        @DisplayName("naming nothing leaves the pool exactly as the caller stated it")
        void noNameChangesNothing() {
            assertThat(forwarded(request(null, "platform")))
                    .containsEntry("credentialSource", "platform")
                    .doesNotContainKey("selectedCredentialName")
                    .doesNotContainKey("credentialSelectionStrict");
            assertThat(forwarded(request(null, null))).isEmpty();
        }

        @Test
        @DisplayName("surrounding spaces are trimmed, and nothing else about the name is touched")
        void trimmedOnly() {
            // The matcher downstream ignores capitalisation and surrounding spaces and
            // NOTHING else, and every text that offers these names says so. Cleaning the
            // value further here would accept names the run then refuses.
            assertThat(CatalogExecuteModule.chosenCredentialName(request("  Client A  ", null)))
                    .isEqualTo("Client A");
            assertThat(CatalogExecuteModule.chosenCredentialName(request("Client-A", null)))
                    .isEqualTo("Client-A");
        }

        @Test
        @DisplayName("a positive whole number is an ID, exactly as it is on a workflow step")
        void aNumberIsAnIdOnBothPaths() {
            // Both credential listings tell an agent that a name which is a positive whole
            // number is read as a credential id. That was true only on a workflow step, so
            // an account named "2024" meant one thing there and another here - a rule stated
            // to be identical and wasn't. Read the same way in both, so the sentence is true
            // wherever it is read.
            assertThat(forwarded(request("2024", null)))
                    .containsEntry("selectedCredentialId", 2024L)
                    .containsEntry("credentialSelectionStrict", true)
                    .containsEntry("credentialSource", "user")
                    .doesNotContainKey("selectedCredentialName");
        }

        @Test
        @DisplayName("a number that is not a positive id stays a NAME, so an account really called 0 is still reachable")
        void nonPositiveNumbersAreStillNames() {
            assertThat(forwarded(request("0", null)))
                    .containsEntry("selectedCredentialName", "0")
                    .doesNotContainKey("selectedCredentialId");
            assertThat(forwarded(request("-1", null)))
                    .containsEntry("selectedCredentialName", "-1")
                    .doesNotContainKey("selectedCredentialId");
        }

        @Test
        @DisplayName("a blank name is no choice at all, not a choice of the empty account")
        void blankIsNoChoice() {
            assertThat(CatalogExecuteModule.chosenCredentialName(request("   ", null))).isNull();
            assertThat(CatalogExecuteModule.chosenCredentialName(request(null, null))).isNull();
            assertThat(forwarded(request("   ", null))).doesNotContainKey("selectedCredentialName");
        }
    }

    @Nested
    @DisplayName("beside a credential pinned by the surface")
    class BesideAPinnedId {

        @Test
        @DisplayName("the pinned id is dropped rather than sent and then silently overridden")
        void aNameReplacesThePin() {
            // Downstream the NAME is consulted before the pinned id, so sending both states
            // one choice and honours the other - the exact defect the pin was added to
            // close, reappearing from the other side.
            Map<String, Object> body = new LinkedHashMap<>();
            Map<String, Object> credentials = new LinkedHashMap<>();
            credentials.put("__credentialId__", 42L);
            CatalogExecuteModule.applyCredentialChoice(body, request("Boulot", "user"),
                    new ToolExecutionContext("tenant-1", credentials, Map.of(), Set.of(),
                            null, null, null, null));

            assertThat(body)
                    .containsEntry("selectedCredentialName", "Boulot")
                    .doesNotContainKey("selectedCredentialId");
        }

        @Test
        @DisplayName("with no name, the pin travels exactly as it did")
        void thePinIsUntouchedWithoutAName() {
            Map<String, Object> body = new LinkedHashMap<>();
            Map<String, Object> credentials = new LinkedHashMap<>();
            credentials.put("__credentialId__", 42L);
            CatalogExecuteModule.applyCredentialChoice(body, request(null, "user"),
                    new ToolExecutionContext("tenant-1", credentials, Map.of(), Set.of(),
                            null, null, null, null));

            assertThat(body).containsEntry("selectedCredentialId", 42L);
        }
    }

    @Nested
    @DisplayName("what is refused before anything runs")
    class Refusals {

        @Test
        @DisplayName("naming an account AND asking for the platform key is a contradiction, answered here")
        void nameAndPlatformPoolConflict() {
            // Platform keys are the platform's own and carry no name a caller could have
            // read. Sent on, this reaches the same refusal one hop later, from a guard
            // whose message is about a field this caller never used.
            ToolExecutionResult refusal =
                    CatalogExecuteModule.checkCredentialChoice(request("Boulot", "platform"));
            assertThat(refusal).isNotNull();
            assertThat(refusal.success()).isFalse();
            assertThat(refusal.errorCode()).isEqualTo(ToolErrorCode.INVALID_PARAMETER_VALUE);
            assertThat(refusal.error())
                    .contains("credential_name")
                    .contains("credential_source='platform'")
                    .contains("nothing was charged");
        }

        @Test
        @DisplayName("every other combination passes")
        void nothingElseIsRefused() {
            assertThat(CatalogExecuteModule.checkCredentialChoice(request("Boulot", null))).isNull();
            assertThat(CatalogExecuteModule.checkCredentialChoice(request("Boulot", "user"))).isNull();
            assertThat(CatalogExecuteModule.checkCredentialChoice(request(null, "platform"))).isNull();
            assertThat(CatalogExecuteModule.checkCredentialChoice(request(null, null))).isNull();
        }
    }

    @Nested
    @DisplayName("it names an account, never a provider parameter")
    class NeverAToolInput {

        @Test
        @DisplayName("credential_name is reserved, so it cannot reach the upstream request as an input")
        void reservedKey() {
            // The failure this prevents is silent on our side and loud on theirs: the
            // provider receives a parameter it never declared.
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("action", "execute");
            parameters.put("tool_id", "abc");
            parameters.put("credential_name", "Boulot");
            parameters.put("q", "newer_than:1d");

            assertThat(CatalogExecuteModule.toolInputsFor(parameters))
                    .containsEntry("q", "newer_than:1d")
                    .doesNotContainKey("credential_name");
        }
    }
}

package com.apimarketplace.agent.prompt;

import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.domain.ToolParameter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the LLM-facing contract of the conversation tools. These descriptions are
 * shipped to the model on every iteration; the unified {@code credential} tool text
 * in particular encodes hard-won behavioral rules (literal force:true, anti-loop,
 * the require decision table) that must survive any future compaction.
 *
 * <p>{@code request_credential} is a LEGACY ROUTING ALIAS only: it stays in
 * {@link ConversationToolDefinitions#ALL_CONVERSATION_TOOL_NAMES} so pre-rename
 * sessions keep routing, but it is never advertised by
 * {@link ConversationToolDefinitions#getConversationTools(boolean)}.
 */
@DisplayName("ConversationToolDefinitions")
class ConversationToolDefinitionsTest {

    private static ToolDefinition find(String name, boolean newConversation) {
        return ConversationToolDefinitions.getConversationTools(newConversation).stream()
                .filter(t -> name.equals(t.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Tool not found: " + name));
    }

    private static ToolParameter param(ToolDefinition tool, String name) {
        return tool.parameters().stream()
                .filter(p -> name.equals(p.name())).findFirst()
                .orElseThrow(() -> new AssertionError("Parameter not found: " + name));
    }

    @Nested
    @DisplayName("tool set")
    class ToolSet {

        @Test
        @DisplayName("new conversation carries set_conversation_title; follow-up does not")
        void titleToolOnlyOnNewConversation() {
            assertThat(ConversationToolDefinitions.getConversationTools(true))
                    .extracting(ToolDefinition::name)
                    .containsExactly("set_conversation_title", "get_tool_result", "credential");
            assertThat(ConversationToolDefinitions.getConversationTools(false))
                    .extracting(ToolDefinition::name)
                    .containsExactly("get_tool_result", "credential");
        }

        @Test
        @DisplayName("legacy request_credential is NEVER advertised - it exists only as a routing alias")
        void legacyAliasNotAdvertised() {
            assertThat(ConversationToolDefinitions.getConversationTools(true))
                    .extracting(ToolDefinition::name)
                    .doesNotContain("request_credential");
            assertThat(ConversationToolDefinitions.getConversationTools(false))
                    .extracting(ToolDefinition::name)
                    .doesNotContain("request_credential");
        }

        @Test
        @DisplayName("ALL_CONVERSATION_TOOL_NAMES routes 4 names: the 3 advertised + the legacy alias")
        void allNamesIncludeLegacyAliasForRouting() {
            assertThat(ConversationToolDefinitions.ALL_CONVERSATION_TOOL_NAMES)
                    .containsExactlyInAnyOrder(
                            "set_conversation_title", "get_tool_result",
                            "credential", "request_credential");
        }
    }

    @Nested
    @DisplayName("credential contract")
    class CredentialContract {

        @Test
        @DisplayName("action is the only required parameter and its enum lists all four actions")
        void actionIsOnlyRequiredParam() {
            ToolDefinition tool = find("credential", false);
            assertThat(tool.requiredParameters()).containsExactly("action");
            ToolParameter action = param(tool, "action");
            assertThat(action.required()).isTrue();
            assertThat(action.enumValues())
                    .containsExactlyInAnyOrder("list", "variables", "set_variable", "require");
        }

        @Test
        @DisplayName("exposes the require params (services/reason/force) and set_variable params (name/value/type/description/secret), all optional")
        void perActionParamsAreOptional() {
            ToolDefinition tool = find("credential", false);
            assertThat(tool.parameters()).extracting(ToolParameter::name)
                    .containsExactlyInAnyOrder(
                            "action", "services", "reason", "force",
                            "name", "value", "type", "description", "secret");
            for (String optional : List.of("services", "reason", "force",
                    "name", "value", "type", "description", "secret")) {
                assertThat(param(tool, optional).required())
                        .as("'%s' must stay optional - required is action-dependent, enforced at execution", optional)
                        .isFalse();
            }
            assertThat(param(tool, "force").type()).isEqualTo("boolean");
            assertThat(param(tool, "type").enumValues())
                    .containsExactlyInAnyOrder("STRING", "NUMBER", "BOOLEAN", "JSON");
        }

        @Test
        @DisplayName("secret param is a boolean documenting the write-only semantics (masked in every listing)")
        void secretParamPinsWriteOnlySemantics() {
            ToolParameter secret = param(find("credential", false), "secret");
            assertThat(secret.type()).isEqualTo("boolean");
            assertThat(secret.description())
                    .contains("set_variable only")
                    .contains("write-only")
                    .contains("masked");
        }

        @Test
        @DisplayName("description documents every action (list / variables / set_variable / require) with its response shape")
        void describesAllActions() {
            String d = find("credential", false).description();
            assertThat(d)
                    // list - connected services, never secrets
                    .contains("never returns secret values")
                    .contains("{connected:[{name,integration,status,isDefault,account}],count,defaultCount,hint}")
                    .contains("needs_reauth")
                    // variables - the {{$vars.name}} reference form, secret in the row shape
                    .contains("{{$vars.name}}")
                    .contains("{variables:[{name,value,type,scope,secret,description}],count}")
                    // set_variable - upsert semantics + plan-cap guidance
                    .contains("create or update a workflow variable")
                    .contains("LIMIT REACHED")
                    .contains("DO NOT RETRY")
                    // require - the connect card
                    .contains("Connect card");
        }

        @Test
        @DisplayName("description pins the set_variable UPDATE SEMANTICS (omit = keep, description=\"\" clears, secret=false re-exposes)")
        void updateSemanticsDocumented() {
            // Without these lines the agent re-sends type="STRING" on every
            // value rotation (the retype bug) or re-sends secret/description
            // it no longer knows. Omission must be documented as PRESERVE.
            String d = find("credential", false).description();
            assertThat(d)
                    .contains("UPDATE SEMANTICS")
                    .contains("omitted type/description/secret KEEP the existing values")
                    .contains("Pass description=\"\" to")
                    .contains("clear it")
                    .contains("secret=false explicitly");
        }

        @Test
        @DisplayName("description pins the masking invariant: secret=true variables return value=null")
        void secretMaskingInvariantDocumented() {
            String d = find("credential", false).description();
            assertThat(d)
                    .as("the agent must learn it can never read a secret value back")
                    .contains("secret=true variables return value=null")
                    .contains("never read their value back");
        }

        @Test
        @DisplayName("description keeps the run-output caveat: masking covers LISTINGS only, the resolved value still lands in run outputs")
        void runOutputCaveatDocumented() {
            // Without this sentence the agent could treat secret=true as full
            // redaction and freely echo run outputs that embed the resolved value.
            String d = find("credential", false).description();
            assertThat(d)
                    .contains("RESOLVED value")
                    .contains("still appears in run outputs")
                    .contains("do not echo run outputs");
        }

        @Test
        @DisplayName("description keeps the literal force:true instruction (models must set the PARAMETER, not just reason about it)")
        void forceMustBeLiteral() {
            // Models used to say "I should force reconnect" in reasoning without setting the
            // parameter - the description must keep demanding the LITERAL boolean.
            String d = find("credential", false).description();
            assertThat(d)
                    .contains("force: true")
                    .contains("boolean")
                    .contains("FORCING A RECONNECT");
        }

        @Test
        @DisplayName("description keeps the REQUIRE DECISION TABLE triage rows (credentialsRequired / invalid_grant / scope-quota stop row)")
        void decisionTableRowsSurvive() {
            String d = find("credential", false).description();
            assertThat(d)
                    .contains("REQUIRE DECISION TABLE")
                    .contains("credentialsRequired")
                    .contains("invalid_grant")
                    // the STOP row that prevents useless repeat calls
                    .contains("DO NOT call require again")
                    // the copy-pastable call shape
                    .contains("credential(action=\"require\", services=[\"X\"], reason=\"...\")");
        }

        @Test
        @DisplayName("description keeps the anti-loop rule and the already-exists triage")
        void antiLoopAndAlreadyExistsSurvive() {
            String d = find("credential", false).description();
            assertThat(d)
                    .contains("ANTI-LOOP")
                    .contains("more than once")
                    .contains("Credentials already exist");
        }
    }

    @Nested
    @DisplayName("get_tool_result contract")
    class GetToolResultContract {

        @Test
        @DisplayName("description keeps the compacted-hint trigger and the current-turn DO-NOT rule")
        void compactionHintContractSurvives() {
            String d = find("get_tool_result", false).description();
            assertThat(d)
                    .contains("[compacted: tool_call_id=")
                    .contains("DO NOT call this for the CURRENT turn");
        }
    }
}

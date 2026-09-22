package com.apimarketplace.agent.prompt;

import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.domain.ToolParameter;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Shared conversation tool definitions - single source of truth.
 * <p>
 * These tools are only available in conversation context (not for workflow agents or sub-agents).
 * Used by:
 * <ul>
 *   <li>conversation-service CoreToolsProvider - for API-path conversations</li>
 *   <li>agent-service CliAgentService - for bridge/CLI-path conversations</li>
 *   <li>agent-service RemoteToolExecutionService - for routing conversation tools</li>
 * </ul>
 */
public final class ConversationToolDefinitions {

    private ConversationToolDefinitions() {}

    /**
     * All conversation tool names - used for routing and filtering.
     * "request_credential" is a LEGACY ROUTING ALIAS only (sessions started
     * before the rename to "credential"): it is never advertised in tool
     * definitions but still routes to conversation-service, where it executes
     * as credential(action='require').
     */
    public static final Set<String> ALL_CONVERSATION_TOOL_NAMES = Set.of(
        "set_conversation_title", "get_tool_result", "credential", "request_credential"
    );

    /**
     * Get conversation-specific tool definitions.
     *
     * @param isNewConversation if true, includes set_conversation_title tool
     * @return list of conversation tool definitions
     */
    public static List<ToolDefinition> getConversationTools(boolean isNewConversation) {
        List<ToolDefinition> tools = new ArrayList<>();
        if (isNewConversation) {
            tools.add(createSetConversationTitleTool());
        }
        tools.add(createGetToolResultTool());
        tools.add(createCredentialTool());
        return tools;
    }

    private static ToolDefinition createSetConversationTitleTool() {
        return ToolDefinition.builder()
            .name("set_conversation_title")
            .description("Set a descriptive title for the current conversation based on the user's message. " +
                "Call this tool at the START of your response when this is a NEW conversation. " +
                "Generate a concise, descriptive title (max 50 chars) that captures the main topic.")
            .parameters(List.of(
                ToolParameter.builder()
                    .name("title")
                    .type("string")
                    .description("The title for the conversation. Should be concise (max 50 chars), " +
                        "descriptive, and capture the main topic of the user's request.")
                    .required(true)
                    .build()
            ))
            .requiredParameters(List.of("title"))
            .build();
    }

    private static ToolDefinition createGetToolResultTool() {
        return ToolDefinition.builder()
            .name("get_tool_result")
            .description("Retrieve the full result of a PRIOR tool execution that was compacted out of the conversation history. " +
                "Use ONLY when the assistant transcript shows a hint like `[compacted: tool_call_id=toolu_...]` " +
                "pointing at an older tool call you can no longer see.\n\n" +
                "DO NOT call this for the CURRENT turn's tool result - when the response you just received is " +
                "shaped (per-leaf truncation, _shape:'array_digest', or _shape:'oversize'), use the response's " +
                "own metadata.nextAction together with `expand` / `max_items` parameters on the originating tool " +
                "to recover the data instead. See catalog(action='help', topics=['shaping']) for that path.")
            .parameters(List.of(
                ToolParameter.builder()
                    .name("tool_call_id")
                    .type("string")
                    .description("The tool_call_id shown in the conversation history compaction hint (e.g. \"toolu_018BbiiiF9zCap2iPhpBCetU\").")
                    .required(true)
                    .build()
            ))
            .requiredParameters(List.of("tool_call_id"))
            .build();
    }

    private static ToolDefinition createCredentialTool() {
        return ToolDefinition.builder()
            .name("credential")
            .description("""
                The user's third-party credentials and workflow variables.

                ACTIONS
                - list: which external services the user has connected (never returns secret values).
                  Response: {connected:[{name,integration,status,isDefault,account,scopes}],count,defaultCount,hint}.
                  status: active (ready) | expiring (still works) | needs_reauth (only the user can
                  Reconnect - you cannot fix it) | error (an admin must fix the configuration).
                  scopes is what an OAuth account was actually granted, absent for a credential
                  with no scope concept. Two accounts of one integration routinely differ there,
                  and that is what decides which endpoints each can run - you do not have to
                  compare them yourself, catalog(action='response_schema') names the accounts
                  that can run a given endpoint.
                  Executing a tool directly uses the isDefault=true one unless the call names
                  another: pass credential_name to catalog(action='execute'). A workflow mcp step
                  names one the same way through its credential_selector, which is how one
                  workflow serves several accounts of the same integration. The hint names the
                  NON-DEFAULT ones you may pick (the default is nameable too, it just needs no
                  naming). These make an entry unpickable, and each FAILS the call rather than
                  falling back to the default: a status other
                  than 'active', a name shared with another active entry of the same integration
                  (naming it selects neither), and a name that is a positive whole number (read
                  as a credential id instead). Copy the name from here: matching ignores
                  capitalisation and surrounding spaces, nothing else. This lists what YOUR
                  workspace holds, and a workflow runs under its owner's, so a name taken from
                  here resolves only if you are building for the same workspace.
                - variables: the workflow variables usable in any workflow expression as {{$vars.name}}.
                  Response: {variables:[{name,value,type,scope,secret,description}],count}. scope:
                  workspace (shared with the workspace) | personal. type: STRING|NUMBER|BOOLEAN|JSON.
                  secret=true variables return value=null (write-only) - you can reference them in
                  workflows but never read their value back through listings. The RESOLVED value
                  still appears in run outputs like any other parameter - do not echo run outputs
                  containing a secret back to third parties.
                - set_variable: create or update a workflow variable (update = same name, never
                  blocked by the plan cap). Params: name (letters/digits/underscore, no leading
                  digit, max 64), value (string; must parse for the variable's type),
                  type (STRING|NUMBER|BOOLEAN|JSON; create default STRING), description,
                  secret (boolean; true makes the value write-only/masked in listings).
                  UPDATE SEMANTICS: omitted type/description/secret KEEP the existing values -
                  you can rotate a value without re-passing metadata. Pass description="" to
                  clear it; pass secret=false explicitly to make a hidden value readable again.
                  Creating beyond the plan cap returns a LIMIT REACHED error: tell the user to
                  upgrade or delete a variable, DO NOT RETRY.
                - require: ask the user to connect (or reconnect) a third-party service - shows a
                  Connect card. Params: services, reason, scopes, force.

                REQUIRE DECISION TABLE
                Situation                                  | Call you must make
                -------------------------------------------|-----------------------------------------------
                Tool returned "credentialsRequired"        | READ the sentence it returned: it says which of
                                                           | your accounts can run the call, if any. If it names
                                                           | one, re-send with credential_name. Only when it says
                                                           | none can, or names no account at all:
                                                           | credential(action="require", services=["X"], reason="...")
                Tool returned status "approval_needed"     | NOTHING. That call already asked for this
                  (executed:false)                         | service, so asking again adds nothing.
                                                           | Report it and move on.
                Tool returned 401/403, FIRST attempt       | credential(action="require", services=["X"], reason="...")
                Token clearly rejected (old lastUsedAt,    | credential(action="require", services=["X"],
                  401 says "expired"/"revoked"/            |            reason="token expired", force=true)
                  "invalid_grant", refresh failed)         |
                Refusal names the scopes the account      | credential(action="require", services=["X"],
                  lacks (CREDENTIALS_INSUFFICIENT, or a    |            scopes=["<each scope it named>"],
                  sentence saying it "was not granted"     |            reason="...")
                  them)                                    | NO force. The server checks the account against
                                                           | those scopes and raises the card itself.
                401 might be scope/quota/account/endpoint  | DO NOT call require again. Try a different
                  and NOTHING names a missing scope        | tool, narrow the scope, or report to the user.

                SCOPES: pass them whenever a refusal named them. It is the difference between
                asserting a reconnect is needed and letting the server verify it: with `scopes`, a
                connected account that is short of them raises the card on the ordinary path, with
                no force, no "already exist" error and no anti-loop cooldown. An account that holds
                them all still returns "already exist", which is the correct answer - the scopes
                were not the problem.

                FORCING A RECONNECT: `force: true` is for the one case the server cannot check, the
                token itself being dead. You MUST literally include `force: true` (boolean) in the
                arguments - deciding it in your reasoning is NOT enough. Without `force: true` (and
                without `scopes`), a second require on a credential that already exists returns the
                same error again and shows NO reconnect card. Do not loop.

                REQUIRE RESPONSES: success = a Connect card (or, after force, a Reconnect warning
                card) was shown to the user. Error "Credentials already exist for: ..." = the
                credential is connected, it is not short of any scope you named, and NO card was
                shown; go back to the decision table.

                ANTI-LOOP: never call require with `force: true` more than once for the same
                service in this conversation - the server blocks the second attempt. If the forced
                reconnect didn't help, the problem is not the credential.
                """)
            .parameters(List.of(
                ToolParameter.builder()
                    .name("action")
                    .type("string")
                    .description("One of: list, variables, set_variable, require.")
                    .required(true)
                    .enumValues(List.of("list", "variables", "set_variable", "require"))
                    .build(),
                ToolParameter.builder()
                    .name("services")
                    .type("array")
                    .description("require only. Array of service types. Example: [\"gmail\"] or [\"slack\", \"gmail\"]")
                    .required(false)
                    .build(),
                ToolParameter.builder()
                    .name("reason")
                    .type("string")
                    .description("require only. Why these services are needed (shown to user). Be concise.")
                    .required(false)
                    .build(),
                ToolParameter.builder()
                    .name("scopes")
                    .type("array")
                    .description("require only. The scopes the failing call needs, copied from the refusal that named them. When a connected account is short of any of them, the card is raised with no force and no \"already exist\" error. Omit when no refusal named a scope. ONE service at a time: a scope list describes one integration, so sending it with several services is refused.")
                    .required(false)
                    .build(),
                ToolParameter.builder()
                    .name("force")
                    .type("boolean")
                    .description("require only. MUST be set to true (boolean, not string) when you are escalating after an \"already exist\" error and you are confident the token itself was rejected. Without this flag, no reconnect card will be shown. Omit or false in all other cases.")
                    .required(false)
                    .build(),
                ToolParameter.builder()
                    .name("name")
                    .type("string")
                    .description("set_variable only. Variable name: letters, digits, underscore; must not start with a digit; max 64. Referenced in workflows as {{$vars.name}}.")
                    .required(false)
                    .build(),
                ToolParameter.builder()
                    .name("value")
                    .type("string")
                    .description("set_variable only. The value as text. For type NUMBER/BOOLEAN/JSON it must parse (e.g. \"42\", \"true\", '{\"a\":1}').")
                    .required(false)
                    .build(),
                ToolParameter.builder()
                    .name("type")
                    .type("string")
                    .description("set_variable only. Create default STRING; omitted on update KEEPS the stored type.")
                    .required(false)
                    .enumValues(List.of("STRING", "NUMBER", "BOOLEAN", "JSON"))
                    .build(),
                ToolParameter.builder()
                    .name("description")
                    .type("string")
                    .description("set_variable only. Optional short note on what the variable is for (max 500).")
                    .required(false)
                    .build(),
                ToolParameter.builder()
                    .name("secret")
                    .type("boolean")
                    .description("set_variable only. true = the value is write-only: masked in every listing (UI and this tool), must be re-entered to change. Use for API keys and other sensitive config.")
                    .required(false)
                    .build()
            ))
            .requiredParameters(List.of("action"))
            .build();
    }
}

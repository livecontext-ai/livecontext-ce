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

    /** All conversation tool names - used for routing and filtering. */
    public static final Set<String> ALL_CONVERSATION_TOOL_NAMES = Set.of(
        "set_conversation_title", "get_tool_result", "request_credential"
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
        tools.add(createRequestCredentialTool());
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

    private static ToolDefinition createRequestCredentialTool() {
        return ToolDefinition.builder()
            .name("request_credential")
            .description("""
                Ask the user to connect (or reconnect) a third-party service credential.

                ═══ DECISION TABLE ═══
                Situation                                  | Call you must make
                -------------------------------------------|-----------------------------------------------
                Tool returned "credentialsRequired"        | request_credential(services=["X"], reason="...")
                Tool returned 401/403, FIRST attempt       | request_credential(services=["X"], reason="...")
                  (you don't yet know if creds exist)      |   ↓ if you get an "already exist" error:
                                                           |   → reason about lastUsedAt + original error
                Token clearly rejected (old lastUsedAt,    | request_credential(services=["X"],
                  401 says "expired"/"revoked"/            |                    reason="token expired",
                  "invalid_grant", refresh failed)         |                    force=true)
                401 might be scope/quota/account/endpoint  | DO NOT call request_credential again.
                                                           | Try a different tool, narrow the scope, or
                                                           | report the failure to the user.

                ═══ CRITICAL - HOW TO FORCE A RECONNECT ═══
                When you decide the token is rejected, you MUST literally include
                `force: true` (boolean) in the arguments. Saying "I should force reconnect"
                in your reasoning is NOT enough - you have to set the parameter.

                Concrete example for an expired Gmail token:
                  request_credential(
                    services: ["gmail"],
                    reason: "Gmail token expired (401 Authentication expired)",
                    force: true
                  )

                Without `force: true`, calling this tool a second time on a credential
                that already exists will just return the same "already exist" error
                again - it will NOT show the user a reconnect card. Do not loop.

                ═══ RESPONSE SHAPES ═══
                • Success, credential created: a Connect card was shown to the user.
                • Error "Credentials already exist for: …": the credential is already
                  connected, so NO card was shown. This is NOT a hard failure to report -
                  it is a blocker telling you to stop and decide: (a) try a different tool /
                  approach (the 401/403 was likely scope/account/quota/endpoint, not the
                  token), (b) only if you are confident the token itself was rejected, call
                  again with `force: true`, or (c) report to the user.
                • Success after `force: true`: a Reconnect warning card was shown.

                ═══ ANTI-LOOP ═══
                Never call with `force: true` more than once for the same service in
                this conversation - the server will block the second attempt. If the
                first forced reconnect didn't help, the problem is not the credential.
                """)
            .parameters(List.of(
                ToolParameter.builder()
                    .name("services")
                    .type("array")
                    .description("Array of service types. Example: [\"gmail\"] or [\"slack\", \"gmail\"]")
                    .required(true)
                    .build(),
                ToolParameter.builder()
                    .name("reason")
                    .type("string")
                    .description("Why these services are needed (shown to user). Be concise.")
                    .required(true)
                    .build(),
                ToolParameter.builder()
                    .name("force")
                    .type("boolean")
                    .description("MUST be set to true (boolean, not string) when you are escalating after getting an \"already exist\" error and you are confident the token itself was rejected. Without this flag, no reconnect card will be shown. Omit or false in all other cases.")
                    .required(false)
                    .build()
            ))
            .requiredParameters(List.of("services", "reason"))
            .build();
    }
}

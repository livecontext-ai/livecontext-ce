package com.apimarketplace.agent.service.execution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ToolAuthorizationScopeResolver - gate applies only to interactive chat")
class ToolAuthorizationScopeResolverTest {

    private static Map<String, Object> chatCredentials() {
        Map<String, Object> creds = new HashMap<>();
        creds.put("conversationId", "conv-1");
        creds.put("__streamId__", "stream-1");
        creds.put("__agent_depth__", 0);
        return creds;
    }

    @Test
    @DisplayName("General shared-agent chat (no bound agent) is active")
    void generalChatIsActive() {
        assertThat(ToolAuthorizationScopeResolver.isActive(chatCredentials())).isTrue();
    }

    @Test
    @DisplayName("Agent-backed chat is exempt by default (agents = no authorization today)")
    void agentBackedChatIsExemptByDefault() {
        Map<String, Object> creds = chatCredentials();
        creds.put("__agentId__", "agent-1");
        assertThat(ToolAuthorizationScopeResolver.isActive(creds)).isFalse();
    }

    @Test
    @DisplayName("Per-agent seam activates an agent-backed chat when the flag is set")
    void agentBackedChatWithOverrideIsActive() {
        Map<String, Object> creds = chatCredentials();
        creds.put("__agentId__", "agent-1");
        creds.put("__requireToolAuthorization__", true);
        assertThat(ToolAuthorizationScopeResolver.isActive(creds)).isTrue();
    }

    @Test
    @DisplayName("Plain streamId key also counts as an interactive stream")
    void plainStreamIdCounts() {
        Map<String, Object> creds = new HashMap<>();
        creds.put("conversationId", "conv-1");
        creds.put("streamId", "stream-1");
        assertThat(ToolAuthorizationScopeResolver.isActive(creds)).isTrue();
    }

    @Test
    @DisplayName("Workflow-driven execution is exempt")
    void workflowIsExempt() {
        Map<String, Object> creds = chatCredentials();
        creds.put("__workflowRunId__", "run-1");
        assertThat(ToolAuthorizationScopeResolver.isActive(creds)).isFalse();
    }

    @Test
    @DisplayName("Task-driven execution is exempt")
    void taskIsExempt() {
        Map<String, Object> creds = chatCredentials();
        creds.put("__taskId__", "task-1");
        assertThat(ToolAuthorizationScopeResolver.isActive(creds)).isFalse();
    }

    @Test
    @DisplayName("Sub-agent (depth >= 1) is exempt - it inherits the parent's launch authorization")
    void subAgentIsExempt() {
        Map<String, Object> creds = chatCredentials();
        creds.put("__agent_depth__", 1);
        assertThat(ToolAuthorizationScopeResolver.isActive(creds)).isFalse();
    }

    @Test
    @DisplayName("CLI-bridge model does NOT exempt a general interactive chat (it backs the chat)")
    void cliBridgeGeneralChatIsGated() {
        Map<String, Object> creds = chatCredentials(); // general chat (no agentId), has streamId
        creds.put("__cliBridge__", true);
        assertThat(ToolAuthorizationScopeResolver.isActive(creds)).isTrue();
    }

    @Test
    @DisplayName("Headless context with no live stream is exempt (e.g. no streamId)")
    void headlessNoStreamIsExempt() {
        Map<String, Object> creds = new HashMap<>();
        creds.put("conversationId", "conv-1");
        creds.put("__cliBridge__", true); // no streamId → not interactive
        assertThat(ToolAuthorizationScopeResolver.isActive(creds)).isFalse();
    }

    @Test
    @DisplayName("Conversation without a live stream is exempt (not interactive)")
    void conversationWithoutStreamIsExempt() {
        Map<String, Object> creds = new HashMap<>();
        creds.put("conversationId", "conv-1");
        assertThat(ToolAuthorizationScopeResolver.isActive(creds)).isFalse();
    }

    @Test
    @DisplayName("Null / empty credentials are exempt")
    void nullCredentialsAreExempt() {
        assertThat(ToolAuthorizationScopeResolver.isActive(null)).isFalse();
        assertThat(ToolAuthorizationScopeResolver.isActive(new HashMap<>())).isFalse();
    }

    @Test
    @DisplayName("Per-agent seam: __requireToolAuthorization__ activates the gate even out of chat")
    void perAgentOverrideActivatesOutOfChat() {
        Map<String, Object> workflow = new HashMap<>();
        workflow.put("__workflowRunId__", "run-1");
        workflow.put("__requireToolAuthorization__", true);
        assertThat(ToolAuthorizationScopeResolver.isActive(workflow)).isTrue();

        Map<String, Object> subAgent = new HashMap<>();
        subAgent.put("__agent_depth__", 2);
        subAgent.put("__requireToolAuthorization__", "true");
        assertThat(ToolAuthorizationScopeResolver.isActive(subAgent)).isTrue();
    }
}

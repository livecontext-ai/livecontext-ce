package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.domain.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the tool-authorization gate decision in isolation (no downstream
 * HTTP / local execution) via the package-private {@code checkToolAuthorization}.
 */
@DisplayName("RemoteToolExecutionService - tool authorization gate")
class RemoteToolExecutionAuthorizationTest {

    private RemoteToolExecutionService service;

    @BeforeEach
    void setUp() {
        service = new RemoteToolExecutionService(new ObjectMapper());
    }

    private static Map<String, Object> chatCredentials() {
        Map<String, Object> creds = new HashMap<>();
        creds.put("conversationId", "conv-1");
        creds.put("__streamId__", "stream-1");
        creds.put("__agent_depth__", 0);
        return creds;
    }

    @Test
    @DisplayName("Sensitive action in interactive chat (not yet approved) yields a pause result")
    void sensitiveActionInChatYieldsPause() {
        ToolResult result = service.checkToolAuthorization(
                new ToolCall("call-1", "application", Map.of("action", "acquire"), null),
                chatCredentials(), System.currentTimeMillis());

        assertThat(result).isNotNull();
        assertThat(result.success()).isTrue();
        assertThat(result.metadata()).containsEntry("toolAuthorizationRequired", true);
        assertThat(result.metadata()).containsEntry("rule", "application:acquire");
        assertThat(result.metadata()).containsEntry("toolCallId", "call-1");
    }

    @Test
    @DisplayName("agent:execute is gated before the sub-agent interception path")
    void agentExecuteIsGated() {
        ToolResult result = service.checkToolAuthorization(
                new ToolCall("call-2", "agent", Map.of("action", "execute"), null),
                chatCredentials(), System.currentTimeMillis());

        assertThat(result).isNotNull();
        assertThat(result.metadata()).containsEntry("rule", "agent:execute");
    }

    @Test
    @DisplayName("Non-sensitive action proceeds (no gate)")
    void nonSensitiveActionProceeds() {
        ToolResult result = service.checkToolAuthorization(
                new ToolCall("call-3", "files", Map.of("action", "list"), null),
                chatCredentials(), System.currentTimeMillis());

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("Already-authorized rule proceeds (transient resume or persisted 'always authorize')")
    void alreadyAuthorizedProceeds() {
        Map<String, Object> creds = chatCredentials();
        creds.put("__approvedToolActions__", List.of("application:acquire"));

        ToolResult result = service.checkToolAuthorization(
                new ToolCall("call-4", "application", Map.of("action", "acquire"), null),
                creds, System.currentTimeMillis());

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("Sensitive action in an exempt (workflow) context proceeds without a card")
    void sensitiveActionInExemptContextProceeds() {
        Map<String, Object> creds = chatCredentials();
        creds.put("__workflowRunId__", "run-1");

        ToolResult result = service.checkToolAuthorization(
                new ToolCall("call-5", "catalog", Map.of("action", "execute"), null),
                creds, System.currentTimeMillis());

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("workflow:execute is gated - regression: running a workflow by id (workflow tool) was ungated and ran with no card")
    void workflowExecuteIsGated() {
        ToolResult result = service.checkToolAuthorization(
                new ToolCall("call-wf", "workflow", Map.of("action", "execute", "id", "wf-1"), null),
                chatCredentials(), System.currentTimeMillis());

        assertThat(result).isNotNull();
        assertThat(result.metadata()).containsEntry("toolAuthorizationRequired", true);
        assertThat(result.metadata()).containsEntry("rule", "workflow:execute");
    }

    @Test
    @DisplayName("application:acquire pause surfaces the publication id (application_id) for the install modal")
    void acquirePauseCarriesApplicationId() {
        ToolResult result = service.checkToolAuthorization(
                new ToolCall("call-6", "application",
                        Map.of("action", "acquire", "application_id", "pub-123"), null),
                chatCredentials(), System.currentTimeMillis());

        assertThat(result).isNotNull();
        assertThat(result.metadata()).containsEntry("rule", "application:acquire");
        assertThat(result.metadata()).containsEntry("applicationId", "pub-123");
    }

    @Test
    @DisplayName("application:execute pause carries no applicationId (install modal is acquire-only)")
    void executePauseHasNoApplicationId() {
        ToolResult result = service.checkToolAuthorization(
                new ToolCall("call-7", "application",
                        Map.of("action", "execute", "application_id", "pub-123"), null),
                chatCredentials(), System.currentTimeMillis());

        assertThat(result).isNotNull();
        assertThat(result.metadata()).containsEntry("rule", "application:execute");
        assertThat(result.metadata()).doesNotContainKey("applicationId");
    }

    @Test
    @DisplayName("Gate content carries executed=false and forbids inventing an outcome (workflow:execute)")
    void gateContentMarksNotExecuted() throws Exception {
        ToolResult result = service.checkToolAuthorization(
                new ToolCall("call-ex", "workflow", Map.of("action", "execute", "id", "wf-1"), null),
                chatCredentials(), System.currentTimeMillis());

        assertThat(result).isNotNull();
        Map<String, Object> content = new ObjectMapper().readValue(result.content(), Map.class);
        assertThat(content).containsEntry("status", "authorization_required");
        assertThat(content).containsEntry("executed", false);
        assertThat(String.valueOf(content.get("message")))
                .contains("has NOT run")
                .contains("NO result exists");
    }

    @Test
    @DisplayName("application:acquire gate message frames install as a user-driven, out-of-band step")
    void acquireGateMessageIsUserDrivenInstall() throws Exception {
        ToolResult result = service.checkToolAuthorization(
                new ToolCall("call-acq", "application",
                        Map.of("action", "acquire", "application_id", "pub-123"), null),
                chatCredentials(), System.currentTimeMillis());

        assertThat(result).isNotNull();
        Map<String, Object> content = new ObjectMapper().readValue(result.content(), Map.class);
        assertThat(content).containsEntry("executed", false);
        assertThat(String.valueOf(content.get("message")))
                .contains("NOT been installed")
                .contains("USER", "installation confirmation", "Resume the original task")
                .doesNotContain("come back and ask again");
    }

    @Test
    @DisplayName("Wildcard '*' grant (chatConfig.autoAuthorizeTools) bypasses any sensitive rule")
    void wildcardGrantBypassesEveryRule() {
        Map<String, Object> creds = chatCredentials();
        creds.put("__approvedToolActions__", List.of("*"));

        assertThat(service.checkToolAuthorization(
                new ToolCall("call-8", "application", Map.of("action", "acquire"), null),
                creds, System.currentTimeMillis())).isNull();
        assertThat(service.checkToolAuthorization(
                new ToolCall("call-9", "catalog", Map.of("action", "execute"), null),
                creds, System.currentTimeMillis())).isNull();
    }

    @Test
    @DisplayName("A specific grant bypasses only its own rule, not siblings")
    void specificGrantDoesNotBypassOtherRules() {
        Map<String, Object> creds = chatCredentials();
        creds.put("__approvedToolActions__", List.of("application:acquire"));

        // The granted rule proceeds…
        assertThat(service.checkToolAuthorization(
                new ToolCall("call-10", "application", Map.of("action", "acquire"), null),
                creds, System.currentTimeMillis())).isNull();
        // …but a different sensitive rule still pauses.
        ToolResult other = service.checkToolAuthorization(
                new ToolCall("call-11", "agent", Map.of("action", "execute"), null),
                creds, System.currentTimeMillis());
        assertThat(other).isNotNull();
        assertThat(other.metadata()).containsEntry("rule", "agent:execute");
    }

    // ---- Arming production: pin/unpin and a scheduled agent. These are the calls whose
    // effects outlive the conversation, so each is pinned end to end from the gate decision.

    @Test
    @DisplayName("workflow:pin is gated - regression: putting a version live armed every trigger with no card")
    void workflowPinIsGated() {
        ToolResult result = service.checkToolAuthorization(
                new ToolCall("call-10", "workflow",
                        Map.of("action", "pin", "workflow_id", "w-1", "version", 12), null),
                chatCredentials(), System.currentTimeMillis());

        assertThat(result).isNotNull();
        assertThat(result.metadata()).containsEntry("rule", "workflow:pin");
        // The card must be able to say WHICH workflow and version, or the question is
        // unanswerable. The name is not here on purpose - agent-service cannot resolve it.
        assertThat(result.metadata()).extracting("subject").asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("kind", "workflow")
                .containsEntry("id", "w-1")
                .containsEntry("version", 12);
    }

    @Test
    @DisplayName("workflow:unpin is gated - taking production off the air is not a silent action")
    void workflowUnpinIsGated() {
        ToolResult result = service.checkToolAuthorization(
                new ToolCall("call-11", "workflow", Map.of("action", "unpin", "workflow_id", "w-1"), null),
                chatCredentials(), System.currentTimeMillis());

        assertThat(result).isNotNull();
        assertThat(result.metadata()).containsEntry("rule", "workflow:unpin");
    }

    @Test
    @DisplayName("Creating an agent WITH a cron is gated, and the card carries the cron and its zone")
    void agentCreateWithCronIsGated() {
        ToolResult result = service.checkToolAuthorization(
                new ToolCall("call-12", "agent",
                        Map.of("action", "create", "name", "Inbox Watcher",
                               "schedule_cron", "0 9 * * *", "schedule_timezone", "Europe/Paris"), null),
                chatCredentials(), System.currentTimeMillis());

        assertThat(result).isNotNull();
        assertThat(result.metadata()).containsEntry("rule", "agent:schedule");
        assertThat(result.metadata()).extracting("subject").asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("cron", "0 9 * * *")
                .containsEntry("timezone", "Europe/Paris")
                .containsEntry("name", "Inbox Watcher");
    }

    @Test
    @DisplayName("Creating an agent WITHOUT a cron is not gated - that is why the rule is conditional")
    void agentCreateWithoutCronProceeds() {
        // If this ever starts returning a result, every agent anyone writes in chat gets a
        // card, which is the failure this design exists to avoid.
        ToolResult result = service.checkToolAuthorization(
                new ToolCall("call-13", "agent",
                        Map.of("action", "create", "name", "Researcher",
                               "system_prompt", "You research things"), null),
                chatCredentials(), System.currentTimeMillis());

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("Removing a schedule (blank cron) is not gated - what disarms is never held up")
    void agentUpdateClearingTheCronProceeds() {
        Map<String, Object> args = new HashMap<>();
        args.put("action", "update");
        args.put("agent_id", "a-1");
        args.put("schedule_cron", "");

        assertThat(service.checkToolAuthorization(
                new ToolCall("call-14", "agent", args, null),
                chatCredentials(), System.currentTimeMillis())).isNull();
    }

    @Test
    @DisplayName("A pin inside a scheduled run proceeds with no card - nobody is there to answer")
    void pinInAnExemptContextProceeds() {
        // The gate exists for the chat. In an unattended run a card would hang the run on a
        // click that will never come, so the scope decision must keep failing toward exempt.
        Map<String, Object> creds = chatCredentials();
        creds.put("__workflowRunId__", "run-1");

        assertThat(service.checkToolAuthorization(
                new ToolCall("call-15", "workflow",
                        Map.of("action", "pin", "workflow_id", "w-1", "version", 3), null),
                creds, System.currentTimeMillis())).isNull();
    }

    @Test
    @DisplayName("One standing grant on agent:schedule covers both create and update")
    void oneGrantCoversCreateAndUpdate() {
        // The user ticked "don't ask again" on the creation. Asking again the first time the
        // agent adjusts that same cron would read as the platform forgetting their answer.
        Map<String, Object> creds = chatCredentials();
        creds.put("__approvedToolActions__", List.of("agent:schedule"));

        assertThat(service.checkToolAuthorization(
                new ToolCall("call-16", "agent",
                        Map.of("action", "update", "agent_id", "a-1", "schedule_cron", "*/10 * * * *"), null),
                creds, System.currentTimeMillis())).isNull();
    }

    @Test
    @DisplayName("A gated call with nothing to name carries no subject at all")
    void rulesWithoutASubjectCarryNone() {
        ToolResult result = service.checkToolAuthorization(
                new ToolCall("call-17", "workflow", Map.of("action", "execute", "id", "w-1"), null),
                chatCredentials(), System.currentTimeMillis());

        assertThat(result).isNotNull();
        assertThat(result.metadata()).doesNotContainKey("subject");
    }

    @Test
    @DisplayName("The nested params shape the agent help publishes is gated, and its card names the cron")
    void nestedParamsShapeIsGatedAndNamed() {
        // agent(action='create', params={..., schedule_cron: ...}) is the form the tool's own
        // help gives in all three of its scheduled-agent examples, and AgentCrudModule flattens
        // it before reading anything. Read only the top level and this call arms a recurring
        // agent, returns success, and raises no card at all.
        ToolResult result = service.checkToolAuthorization(
                new ToolCall("call-18", "agent",
                        Map.of("action", "create",
                               "params", Map.of("name", "Daily Reporter",
                                                "system_prompt", "Generate reports.",
                                                "schedule_cron", "0 9 * * *",
                                                "schedule_timezone", "Europe/Paris")), null),
                chatCredentials(), System.currentTimeMillis());

        assertThat(result).isNotNull();
        assertThat(result.metadata()).containsEntry("rule", "agent:schedule");
        assertThat(result.metadata()).extracting("subject").asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("cron", "0 9 * * *")
                .containsEntry("timezone", "Europe/Paris")
                .containsEntry("name", "Daily Reporter");
    }

    @Test
    @DisplayName("A nested create with no cron still proceeds, so the merge did not over-gate")
    void nestedParamsWithoutCronProceeds() {
        assertThat(service.checkToolAuthorization(
                new ToolCall("call-19", "agent",
                        Map.of("action", "create",
                               "params", Map.of("name", "Researcher", "system_prompt", "Research.")), null),
                chatCredentials(), System.currentTimeMillis())).isNull();
    }
}

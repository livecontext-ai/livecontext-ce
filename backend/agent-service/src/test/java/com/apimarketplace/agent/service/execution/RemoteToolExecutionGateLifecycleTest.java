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
 * Multi-turn LIFECYCLE of the tool-authorization gate - the integrated flow the
 * 2026-06-03 live e2e exercised end-to-end, codified deterministically (no LLM,
 * no HTTP). {@link RemoteToolExecutionAuthorizationTest} pins each gate decision
 * in isolation (single call); this pins the SEQUENCE across turns and the
 * producer&rarr;consumer CONTRACT.
 *
 * <p><b>Contract pinned.</b> The {@code rule} the pause result emits (shown on the
 * authorization card and echoed back by conversation-service as the approved
 * action) is byte-for-byte the token the gate accepts in
 * {@code __approvedToolActions__} on the resume turn. Each test feeds the EMITTED
 * rule back - never a hand-typed literal - so a rule-key format drift between
 * {@code buildAuthorizationRequiredResult} (emits {@code rule}) and
 * {@code isAlreadyAuthorized} (consumes the token) turns this red.
 *
 * <p><b>Id-independence.</b> {@code grantSurvivesToolCallIdChange} resumes with a
 * NEW {@code toolCallId} for the same tool+action, pinning the invariant from
 * {@code ToolAuthorizationPolicy.ruleKey}'s javadoc ("never use the LLM-generated
 * toolCallId, which changes across resume turns") - the grant must key on the rule.
 *
 * <p>Out of scope here (already covered): a never-granted pause and the wildcard
 * blanket skip ({@code chatConfig.autoAuthorizeTools} &rarr; {@code "*"}) live in
 * {@code RemoteToolExecutionAuthorizationTest}; declining is "no token injected",
 * i.e. that same never-granted pause.
 */
@DisplayName("RemoteToolExecutionService - tool-authorization gate lifecycle (e2e codified)")
class RemoteToolExecutionGateLifecycleTest {

    private RemoteToolExecutionService service;

    @BeforeEach
    void setUp() {
        service = new RemoteToolExecutionService(new ObjectMapper());
    }

    /** Interactive general chat: conversationId + live stream + no bound agent → the gate is active. */
    private static Map<String, Object> chatCredentials() {
        Map<String, Object> creds = new HashMap<>();
        creds.put("conversationId", "conv-1");
        creds.put("__streamId__", "stream-1");
        creds.put("__agent_depth__", 0);
        return creds;
    }

    @Test
    @DisplayName("Authorize → resume: workflow:execute pauses, then proceeds once its emitted rule is granted")
    void workflowExecuteAuthorizeThenResumeProceeds() {
        ToolCall run = new ToolCall("call-wf", "workflow", Map.of("action", "execute", "id", "wf-1"), null);

        // Turn 1 - no grant yet → pause, the card is shown to the user.
        ToolResult pause = service.checkToolAuthorization(run, chatCredentials(), System.currentTimeMillis());
        assertThat(pause).isNotNull();
        assertThat(pause.metadata()).containsEntry("toolAuthorizationRequired", true);
        String emittedRule = (String) pause.metadata().get("rule");
        assertThat(emittedRule).as("pause must emit the canonical rule key").isEqualTo("workflow:execute");

        // Turn 2 - user clicked "Authorize"; conversation-service injects the EMITTED rule.
        Map<String, Object> resumeCreds = chatCredentials();
        resumeCreds.put("__approvedToolActions__", List.of(emittedRule));
        ToolResult resumed = service.checkToolAuthorization(run, resumeCreds, System.currentTimeMillis());
        assertThat(resumed).as("the granted rule must bypass the gate so the workflow runs").isNull();
    }

    @Test
    @DisplayName("Install → resume: application:acquire pause emits the application_id, then proceeds once its emitted rule is granted")
    void acquireInstallThenResumeProceeds() {
        ToolCall acquire = new ToolCall("call-acq", "application",
                Map.of("action", "acquire", "application_id", "pub-123"), null);

        // Turn 1 - pause carries the publication id the user's install modal needs.
        ToolResult pause = service.checkToolAuthorization(acquire, chatCredentials(), System.currentTimeMillis());
        assertThat(pause).isNotNull();
        assertThat(pause.metadata()).containsEntry("applicationId", "pub-123");
        String emittedRule = (String) pause.metadata().get("rule");
        assertThat(emittedRule).as("acquire pause must emit the canonical rule key").isEqualTo("application:acquire");

        // Turn 2 - user installed it; resume with the emitted rule granted → proceeds (harmless no-op re-call).
        Map<String, Object> resumeCreds = chatCredentials();
        resumeCreds.put("__approvedToolActions__", List.of(emittedRule));
        assertThat(service.checkToolAuthorization(acquire, resumeCreds, System.currentTimeMillis()))
                .as("the granted acquire rule must bypass the gate").isNull();
    }

    @Test
    @DisplayName("Grant survives a toolCallId change - approval keys on the rule, not the volatile tool-call id")
    void grantSurvivesToolCallIdChange() {
        // Turn 1 - pause; the model's tool-call id is "call-id-A".
        ToolCall turn1 = new ToolCall("call-id-A", "workflow", Map.of("action", "execute", "id", "wf-1"), null);
        ToolResult pause = service.checkToolAuthorization(turn1, chatCredentials(), System.currentTimeMillis());
        assertThat(pause).isNotNull();
        String emittedRule = (String) pause.metadata().get("rule");
        assertThat(emittedRule).as("pause must emit the canonical rule key to approve").isEqualTo("workflow:execute");

        // Turn 2 - the LLM re-issues the SAME action under a DIFFERENT tool-call id.
        // The injected grant is the rule key, so the gate must still bypass - the id is irrelevant.
        ToolCall turn2 = new ToolCall("call-id-B-different", "workflow", Map.of("action", "execute", "id", "wf-1"), null);
        Map<String, Object> resumeCreds = chatCredentials();
        resumeCreds.put("__approvedToolActions__", List.of(emittedRule));
        assertThat(service.checkToolAuthorization(turn2, resumeCreds, System.currentTimeMillis()))
                .as("a rule grant must survive a toolCallId change (id-independent)").isNull();

        // Guard: the same new id WITHOUT the grant still pauses - proving the bypass came from the
        // rule grant, not from anything carried by the call itself.
        ToolResult ungranted = service.checkToolAuthorization(turn2, chatCredentials(), System.currentTimeMillis());
        assertThat(ungranted).as("without the grant the new call id still pauses").isNotNull();
    }
}

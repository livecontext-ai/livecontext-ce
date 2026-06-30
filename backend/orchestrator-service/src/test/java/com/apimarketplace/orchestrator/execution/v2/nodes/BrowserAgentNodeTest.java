package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("BrowserAgentNode - runner stop-reason → canonical AgentStopReason mapping")
class BrowserAgentNodeTest {

    @Nested
    @DisplayName("mapStopReason")
    class MapStopReason {

        @Test
        @DisplayName("COMPLETED → COMPLETED (success path)")
        void completed() {
            assertThat(BrowserAgentNode.mapStopReason("COMPLETED", true)).isEqualTo("COMPLETED");
        }

        @Test
        @DisplayName("MAX_STEPS → MAX_ITERATIONS (partial outcome - agent ran out of step budget)")
        void maxSteps() {
            assertThat(BrowserAgentNode.mapStopReason("MAX_STEPS", false)).isEqualTo("MAX_ITERATIONS");
        }

        @Test
        @DisplayName("USER_TAKEOVER → STOPPED_BY_USER (human paused / aborted via browse_intervene)")
        void userTakeover() {
            assertThat(BrowserAgentNode.mapStopReason("USER_TAKEOVER", false)).isEqualTo("STOPPED_BY_USER");
        }

        @Test
        @DisplayName("LLM_FAILED / SCHEMA_MISMATCH / DOMAIN_BLOCKED collapse to ERROR")
        void hardErrors() {
            assertThat(BrowserAgentNode.mapStopReason("LLM_FAILED", false)).isEqualTo("ERROR");
            assertThat(BrowserAgentNode.mapStopReason("SCHEMA_MISMATCH", false)).isEqualTo("ERROR");
            assertThat(BrowserAgentNode.mapStopReason("DOMAIN_BLOCKED", false)).isEqualTo("ERROR");
        }

        @Test
        @DisplayName("TIMEOUT / CANCELLED / BUDGET_EXHAUSTED preserve their canonical names")
        void preservedReasons() {
            assertThat(BrowserAgentNode.mapStopReason("TIMEOUT", false)).isEqualTo("TIMEOUT");
            assertThat(BrowserAgentNode.mapStopReason("CANCELLED", false)).isEqualTo("CANCELLED");
            assertThat(BrowserAgentNode.mapStopReason("BUDGET_EXHAUSTED", false)).isEqualTo("BUDGET_EXHAUSTED");
        }

        @Test
        @DisplayName("unknown runner reason → ERROR (defensive default - runner contract drift)")
        void unknownReason() {
            assertThat(BrowserAgentNode.mapStopReason("RUNNER_BLEW_UP_NEW_REASON", false)).isEqualTo("ERROR");
        }

        @Test
        @DisplayName("null runner reason on success → COMPLETED")
        void nullReasonOnSuccess() {
            assertThat(BrowserAgentNode.mapStopReason(null, true)).isEqualTo("COMPLETED");
        }

        @Test
        @DisplayName("null runner reason on failure → ERROR")
        void nullReasonOnFailure() {
            assertThat(BrowserAgentNode.mapStopReason(null, false)).isEqualTo("ERROR");
        }

        @Test
        @DisplayName("case-insensitive: lowercase runner reason maps the same way")
        void caseInsensitive() {
            assertThat(BrowserAgentNode.mapStopReason("max_steps", false)).isEqualTo("MAX_ITERATIONS");
            assertThat(BrowserAgentNode.mapStopReason("completed", true)).isEqualTo("COMPLETED");
        }
    }

    /**
     * Pin the contract that prevents the workflow path from double-billing
     * via {@code BrowserAgentModule.recordObservabilityFromResult}. The
     * module reads {@code __skipObservability__} from the credentials map
     * via {@code String.valueOf(...).equals("true")} - if a future refactor
     * dropped or renamed the put() in {@code buildCallbackCredentials},
     * every workflow browser run would silently produce TWO
     * agent_executions rows + TWO credit_ledger debits.
     */
    @Nested
    @DisplayName("buildCallbackCredentials - workflow ↔ chat-tool observability mutual exclusion")
    class CallbackCredentialsContract {

        @Test
        @DisplayName("sets __skipObservability__=\"true\" so BrowserAgentModule skips its own recordObservability call")
        void setsSkipObservabilityFlag() {
            BrowserAgentNode node = new BrowserAgentNode("node-1", Map.of("llm", Map.of()));
            ExecutionContext ctx = mock(ExecutionContext.class);
            when(ctx.runId()).thenReturn("run-1");

            Map<String, Object> creds = node.buildCallbackCredentials(ctx);

            // The literal "true" String - module guard uses .equals("true"),
            // not equalsIgnoreCase. Don't change one side without the other.
            assertThat(creds).containsEntry("__skipObservability__", "true");
        }

        @Test
        @DisplayName("also includes __streamId__ + __toolCallId__ for live trace routing (regression: don't drop existing creds)")
        void retainsStreamAndToolCallIds() {
            BrowserAgentNode node = new BrowserAgentNode("node-X", Map.of("llm", Map.of()));
            ExecutionContext ctx = mock(ExecutionContext.class);
            when(ctx.runId()).thenReturn("run-X");

            Map<String, Object> creds = node.buildCallbackCredentials(ctx);

            assertThat(creds)
                .containsEntry("__streamId__", "run-X")
                .containsEntry("__toolCallId__", "node-X");
        }
    }
}

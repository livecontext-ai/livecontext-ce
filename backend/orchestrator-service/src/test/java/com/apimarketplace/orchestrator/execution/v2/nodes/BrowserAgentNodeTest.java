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

    @Nested
    @DisplayName("params resolution failure")
    class ParamsResolutionFailure {

        @Test
        @DisplayName("fails with the resolution error instead of browsing with the raw {{...}} config")
        void failsInsteadOfBrowsingWithRawConfig() {
            BrowserAgentNode node = new BrowserAgentNode("node-1",
                Map.of("task", "{{core:missing.output.task}}", "llm", Map.of()));
            com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter adapter =
                mock(com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter.class);
            when(adapter.resolveTemplates(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("Template error"));
            node.setTemplateAdapter(adapter);
            ExecutionContext ctx = ExecutionContext.create("run-1", "wr-1", "tenant-1", "item-0", 0, Map.of(), null);

            NodeExecutionResult result = node.execute(ctx);

            assertThat(result.isFailure()).isTrue();
            assertThat(result.errorMessage().orElse("")).contains("Template error");
        }
    }

    @Nested
    @DisplayName("resolved_params: the task the browser model received")
    class TaskReportedWhole {

        private Map<String, Object> report(BrowserAgentNode node, Map<String, Object> resolved) {
            ExecutionContext ctx = ExecutionContext.create("run-1", "wr-1", "tenant-1", "item-0", 0, Map.of(), null);
            return com.apimarketplace.orchestrator.services.template.ReportedParams.forReport(
                node.withTaskReportedWhole(resolved, ctx));
        }

        @Test
        @DisplayName("BUG: a long task is reported whole, never as its first 120 characters and '(N chars)'")
        void longTaskIsReportedWhole() {
            String task = "Open the supplier portal and download every invoice." + " Then check the totals.".repeat(150);
            BrowserAgentNode node = new BrowserAgentNode("node-1", Map.of("task", task, "llm", Map.of()));

            Map<String, Object> reported = report(node, new java.util.LinkedHashMap<>(Map.of("task", task)));

            assertThat(reported.get("task")).isEqualTo(task);
        }

        @Test
        @DisplayName("SECURITY: a workspace variable in the task is withheld, the rest shown")
        void workspaceVariableInTaskIsWithheld() {
            BrowserAgentNode node = new BrowserAgentNode("node-1",
                Map.of("task", "Log in with {{$vars.portal_password}} and export", "llm", Map.of()));
            com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter adapter =
                mock(com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter.class);
            when(adapter.resolveTemplates(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> inv.getArgument(0));
            node.setTemplateAdapter(adapter);

            Map<String, Object> reported = report(node,
                new java.util.LinkedHashMap<>(Map.of("task", "Log in with hunter2 and export")));

            assertThat(reported.get("task")).isEqualTo("Log in with <withheld: workspace variable> and export");
            assertThat(reported.toString()).doesNotContain("hunter2");
        }
    }

    @Nested
    @DisplayName("resolved_params: the browser agent's other params")
    class OtherParamsWithheld {

        @Test
        @DisplayName("SECURITY: a start url pulling a workspace variable is withheld, the api key masked, the task shown")
        void otherParamWithVariableIsWithheld() {
            Map<String, Object> config = new java.util.LinkedHashMap<>();
            config.put("task", "Export the invoices");
            config.put("start_url", "{{$vars.portal_url}}");
            config.put("llm", Map.of("provider", "openai", "api_key", "{{$vars.llm_key}}"));
            BrowserAgentNode node = new BrowserAgentNode("node-1", config);
            ExecutionContext ctx = ExecutionContext.create("run-1", "wr-1", "tenant-1", "item-0", 0, Map.of(), null);
            Map<String, Object> resolved = new java.util.LinkedHashMap<>();
            resolved.put("task", "Export the invoices");
            resolved.put("start_url", "https://portal.example/?token=SECRET-1");
            resolved.put("llm", Map.of("provider", "openai", "api_key", "SECRET-2"));

            Map<String, Object> reported = com.apimarketplace.orchestrator.services.template.ReportedParams.forReport(
                node.withTaskReportedWhole(resolved, ctx));

            assertThat(reported.get("task")).isEqualTo("Export the invoices");
            assertThat(reported.get("start_url")).isEqualTo("<withheld: workspace variable>");
            assertThat(reported.toString()).doesNotContain("SECRET-1").doesNotContain("SECRET-2");
        }
    }
}

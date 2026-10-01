package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.workflow.NodePolicy;
import com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which failures {@link NodePolicyRunner} retries, and how long it waits. The platform no longer
 * re-sends a refused provider call underneath the node, so the node's own retry has to tell a
 * refusal that repeats (400, 401, 404...) from one that asks to come back later (429, 503), and
 * honour the provider's Retry-After. Sleeps go through a recording virtual clock.
 */
@DisplayName("NodePolicyRunner - retry classification and provider-aware wait")
class NodePolicyRunnerRetryClassificationTest {

    private static final String NODE = "mcp:provider_call";

    private final List<Long> sleeps = new ArrayList<>();
    private final NodePolicyRunner runner = new NodePolicyRunner(sleeps::add);

    /** A failed catalog step as StepNode builds it: http_status at the top, catalog metadata nested. */
    private static NodeExecutionResult providerFailure(Integer status, Long retryAfterSeconds) {
        Map<String, Object> output = new HashMap<>();
        if (status != null) {
            output.put("http_status", status);
        }
        Map<String, Object> metadata = new HashMap<>();
        if (retryAfterSeconds != null) {
            metadata.put("retryAfterSeconds", retryAfterSeconds);
        }
        output.put("metadata", metadata);
        return NodeExecutionResult.failureWithOutput(NODE, "provider refused", output, 5);
    }

    /** A failed step whose provider error text says what happened, with no Retry-After. */
    private static NodeExecutionResult providerFailureSaying(int status, String error) {
        Map<String, Object> output = new HashMap<>();
        output.put("http_status", status);
        output.put("error", error);
        output.put("metadata", new HashMap<>());
        return NodeExecutionResult.failureWithOutput(NODE, error, output, 5);
    }

    private NodeExecutionResult finalOf(NodePolicy policy, NodeExecutionResult failure) throws Exception {
        return runner.run(policy, NODE, () -> failure, null);
    }

    private int attemptsFor(NodePolicy policy, NodeExecutionResult failure) throws Exception {
        AtomicInteger invocations = new AtomicInteger();
        runner.run(policy, NODE, () -> {
            invocations.incrementAndGet();
            return failure;
        }, null);
        return invocations.get();
    }

    private static NodePolicy retries(int count, long backoffMs, String retryOn) {
        return new NodePolicy(count, backoffMs, false, 0L, false, retryOn);
    }

    @Nested
    @DisplayName("Default classification (retryOn absent)")
    class DefaultClassification {

        @Test
        @DisplayName("REGRESSION: a 400 is a permanent refusal and is attempted once, not re-sent")
        void badRequestIsNotRetried() throws Exception {
            assertThat(attemptsFor(retries(3, 1000, null), providerFailure(400, null))).isEqualTo(1);
            assertThat(sleeps).isEmpty();
        }

        @Test
        @DisplayName("401, 403, 404, 409 and 422 are permanent refusals too")
        void otherClientRefusalsAreNotRetried() throws Exception {
            for (int status : new int[] {401, 403, 404, 409, 422}) {
                assertThat(attemptsFor(retries(2, 0, null), providerFailure(status, null)))
                        .as("HTTP %s", status).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("REGRESSION: a 403 carrying a Retry-After is a rate limit and is retried (GitHub secondary limit)")
        void forbiddenWithRetryAfterIsRetried() throws Exception {
            assertThat(attemptsFor(retries(1, 0, null), providerFailure(403, 5L))).isEqualTo(2);
            assertThat(sleeps).containsExactly(5_000L);
        }

        @Test
        @DisplayName("REGRESSION: a 403 saying rateLimitExceeded is a rate limit and is retried (Google)")
        void forbiddenRateLimitTextIsRetried() throws Exception {
            assertThat(attemptsFor(retries(1, 0, null),
                    providerFailureSaying(403, "{\"reason\":\"rateLimitExceeded\"}"))).isEqualTo(2);
            assertThat(attemptsFor(retries(1, 0, null),
                    providerFailureSaying(409, "Too Many Requests, try again later"))).isEqualTo(2);
        }

        @Test
        @DisplayName("an early stop is stamped policy_retry_stopped=permanent_refusal and final")
        void permanentRefusalIsStamped() throws Exception {
            NodeExecutionResult result = finalOf(retries(2, 0, null), providerFailure(404, null));

            assertThat(result.output()).containsEntry(ExecutionMetadataKeys.POLICY_RETRY_STOPPED, "permanent_refusal");
            assertThat(result.metadata()).containsEntry(ExecutionMetadataKeys.POLICY_RETRY_STOPPED, "permanent_refusal");
            assertThat(result.output()).containsEntry(ExecutionMetadataKeys.POLICY_FINAL_ATTEMPT, true);
        }

        @Test
        @DisplayName("an exhausted budget is final with no stop reason; a success is final too")
        void exhaustedAndSuccessAreFinalWithoutReason() throws Exception {
            NodeExecutionResult exhausted = finalOf(retries(1, 0, null), providerFailure(500, null));
            assertThat(exhausted.output()).containsEntry(ExecutionMetadataKeys.POLICY_FINAL_ATTEMPT, true)
                    .doesNotContainKey(ExecutionMetadataKeys.POLICY_RETRY_STOPPED);

            NodeExecutionResult success = runner.run(retries(1, 0, null), NODE,
                    () -> NodeExecutionResult.success(NODE, Map.of("ok", true)), null);
            assertThat(success.output()).containsEntry(ExecutionMetadataKeys.POLICY_FINAL_ATTEMPT, true)
                    .doesNotContainKey(ExecutionMetadataKeys.POLICY_RETRY_STOPPED);
        }

        @Test
        @DisplayName("a 429 is retried up to the budget")
        void rateLimitIsRetried() throws Exception {
            assertThat(attemptsFor(retries(2, 1000, null), providerFailure(429, null))).isEqualTo(3);
            assertThat(sleeps).containsExactly(1000L, 1000L);
        }

        @Test
        @DisplayName("a 503 is retried")
        void unavailableIsRetried() throws Exception {
            assertThat(attemptsFor(retries(1, 0, null), providerFailure(503, null))).isEqualTo(2);
        }

        @Test
        @DisplayName("408 and 425 are 4xx but not refusals, so they are retried")
        void ambiguousFourHundredsAreRetried() throws Exception {
            assertThat(attemptsFor(retries(1, 0, null), providerFailure(408, null))).isEqualTo(2);
            assertThat(attemptsFor(retries(1, 0, null), providerFailure(425, null))).isEqualTo(2);
        }

        @Test
        @DisplayName("a failure with no HTTP status (exception, non-HTTP node) is retried as before")
        void failureWithoutStatusIsRetried() throws Exception {
            assertThat(attemptsFor(retries(2, 0, null), NodeExecutionResult.failure(NODE, "boom"))).isEqualTo(3);
        }

        @Test
        @DisplayName("the status is also read from httpStatus.code when http_status is absent")
        void statusFromHttpStatusCode() throws Exception {
            NodeExecutionResult failure = NodeExecutionResult.failureWithOutput(
                    NODE, "refused", Map.of("httpStatus", Map.of("code", 404)), 1);
            assertThat(attemptsFor(retries(2, 0, null), failure)).isEqualTo(1);
        }

        @Test
        @DisplayName("the final result of a refused attempt keeps its annotations and continueOnFailure")
        void refusedAttemptIsAnnotatedAndContinues() throws Exception {
            NodePolicy policy = new NodePolicy(3, 0L, true, 0L, false, null);

            NodeExecutionResult result = runner.run(policy, NODE, () -> providerFailure(400, null), null);

            assertThat(result.output()).containsEntry(ExecutionMetadataKeys.POLICY_ATTEMPT, 1);
            assertThat(result.output()).containsEntry(ExecutionMetadataKeys.POLICY_MAX_ATTEMPTS, 4);
            assertThat(ExecutionMetadataKeys.isContinueOnFailure(result.metadata())).isTrue();
        }
    }

    @Nested
    @DisplayName("retryOn = rate_limit")
    class RateLimitOnly {

        @Test
        @DisplayName("retries a 429 and a 503")
        void retriesComeBackLater() throws Exception {
            assertThat(attemptsFor(retries(1, 0, "rate_limit"), providerFailure(429, null))).isEqualTo(2);
            assertThat(attemptsFor(retries(1, 0, "rate_limit"), providerFailure(503, null))).isEqualTo(2);
        }

        @Test
        @DisplayName("REGRESSION: never re-sends after an ambiguous 500 or 502, which may have been applied")
        void doesNotRetryAmbiguousServerErrors() throws Exception {
            assertThat(attemptsFor(retries(3, 0, "rate_limit"), providerFailure(500, null))).isEqualTo(1);
            assertThat(attemptsFor(retries(3, 0, "rate_limit"), providerFailure(502, null))).isEqualTo(1);
        }

        @Test
        @DisplayName("REGRESSION: a 500 or 502 saying 'try again later' or carrying a Retry-After is still not re-sent")
        void serverErrorWordingDoesNotReclassify() throws Exception {
            assertThat(attemptsFor(retries(3, 0, "rate_limit"),
                    providerFailureSaying(500, "Internal error, please try again later"))).isEqualTo(1);
            assertThat(attemptsFor(retries(3, 0, "rate_limit"), providerFailure(502, 5L))).isEqualTo(1);
        }

        @Test
        @DisplayName("retries Meta, AWS and Salesforce rate limits sent as a 4xx")
        void retriesProviderSpecificWording() throws Exception {
            for (String body : new String[] {
                    "(#4) Application request limit reached", "Rate exceeded",
                    "ThrottlingException", "REQUEST_LIMIT_EXCEEDED"}) {
                assertThat(attemptsFor(retries(1, 0, "rate_limit"), providerFailureSaying(400, body)))
                        .as(body).isEqualTo(2);
            }
        }

        @Test
        @DisplayName("the node and the agent share one rate-limit wording")
        void wordingMatchesTheAgentCopy() {
            assertThat(NodePolicyRunner.RATE_LIMIT_TEXT)
                    .isSameAs(com.apimarketplace.common.web.RateLimitSignals.WORDING);
        }

        @Test
        @DisplayName("retries a 403 that is a rate limit (Retry-After or rateLimitExceeded wording)")
        void retriesForbiddenRateLimits() throws Exception {
            assertThat(attemptsFor(retries(1, 0, "rate_limit"), providerFailure(403, 2L))).isEqualTo(2);
            assertThat(attemptsFor(retries(1, 0, "rate_limit"),
                    providerFailureSaying(403, "User Rate Limit Exceeded"))).isEqualTo(2);
        }

        @Test
        @DisplayName("stamps not_rate_limited when it declines a failure")
        void declinedIsStamped() throws Exception {
            NodeExecutionResult result = finalOf(retries(2, 0, "rate_limit"), providerFailure(500, null));
            assertThat(result.output()).containsEntry(ExecutionMetadataKeys.POLICY_RETRY_STOPPED, "not_rate_limited");
        }

        @Test
        @DisplayName("does not retry a failure with no HTTP status (a timeout may have been applied)")
        void doesNotRetryWithoutStatus() throws Exception {
            assertThat(attemptsFor(retries(3, 0, "rate_limit"), NodeExecutionResult.failure(NODE, "timeout")))
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Wait before the next attempt")
    class Wait {

        @Test
        @DisplayName("the provider's Retry-After wins when it is longer than retryBackoffMs")
        void retryAfterLongerThanBackoffIsUsed() throws Exception {
            attemptsFor(retries(1, 1000, null), providerFailure(429, 42L));
            assertThat(sleeps).containsExactly(42_000L);
        }

        @Test
        @DisplayName("retryBackoffMs wins when it is longer than the provider's Retry-After")
        void backoffLongerThanRetryAfterIsUsed() throws Exception {
            attemptsFor(retries(1, 60_000, null), providerFailure(429, 5L));
            assertThat(sleeps).containsExactly(60_000L);
        }

        @Test
        @DisplayName("REGRESSION: a provider asking for more than 60 s is not retried, the worker is not held")
        void retryAfterAboveProviderCapIsNotRetried() throws Exception {
            NodeExecutionResult result = finalOf(retries(3, 1000, null), providerFailure(429, 61L));

            assertThat(result.output()).containsEntry(ExecutionMetadataKeys.POLICY_RETRY_STOPPED, "provider_wait_too_long");
            assertThat(sleeps).isEmpty();
        }

        @Test
        @DisplayName("a stored backoff above the cap is clamped first: 120000 + Retry-After 90 s stops instead of waiting")
        void storedBackoffAboveTheCapDoesNotExtendTheProviderWait() throws Exception {
            NodeExecutionResult result = finalOf(retries(2, 120_000, null), providerFailure(429, 90L));

            assertThat(result.output()).containsEntry(ExecutionMetadataKeys.POLICY_RETRY_STOPPED, "provider_wait_too_long");
            assertThat(sleeps).isEmpty();
        }

        @Test
        @DisplayName("backoff at the cap (60000) and a shorter Retry-After waits the backoff")
        void backoffAtTheCapWins() throws Exception {
            assertThat(attemptsFor(retries(1, 60_000, null), providerFailure(429, 30L))).isEqualTo(2);
            assertThat(sleeps).containsExactly(60_000L);
        }

        @Test
        @DisplayName("a Retry-After of exactly 60 s is still waited out")
        void retryAfterAtProviderCapIsWaited() throws Exception {
            assertThat(attemptsFor(retries(1, 0, null), providerFailure(429, 60L))).isEqualTo(2);
            assertThat(sleeps).containsExactly(60_000L);
        }
    }

    @Nested
    @DisplayName("Refusals the platform made itself (no HTTP status)")
    class PlatformRefusals {

        private NodeExecutionResult agentFailure(String error, String stopReason) {
            Map<String, Object> output = new HashMap<>();
            output.put("error", error);
            if (stopReason != null) {
                output.put("stopReason", stopReason);
            }
            return NodeExecutionResult.failureWithOutput("agent:writer", error, output, 5);
        }

        @Test
        @DisplayName("REGRESSION: an exhausted credit budget is attempted once and says why (28 of 82 agent failures in prod)")
        void insufficientCreditsIsNotRetried() throws Exception {
            NodeExecutionResult failure = agentFailure("Insufficient credits (pre-flight tenant budget)", null);
            assertThat(attemptsFor(retries(3, 1000, null), failure)).isEqualTo(1);
            assertThat(sleeps).isEmpty();
            assertThat(finalOf(retries(3, 1000, null), failure).output())
                .containsEntry(ExecutionMetadataKeys.POLICY_RETRY_STOPPED, NodePolicyRunner.STOP_PERMANENT_REFUSAL)
                .containsEntry(ExecutionMetadataKeys.POLICY_FINAL_ATTEMPT, true);
        }

        private NodeExecutionResult catalogRefusal(String code) {
            Map<String, Object> output = new HashMap<>(Map.of(ExecutionMetadataKeys.CATALOG_REFUSAL, code, "error", code));
            return NodeExecutionResult.failureWithOutput("mcp:call", code, output, 5);
        }

        @Test
        @DisplayName("REGRESSION: a tool step the catalog refused itself is attempted once, as the help says")
        void catalogRefusalIsNotRetried() throws Exception {
            // The gateway returned these with nothing the runner could read, so it took them for a
            // fault: a stale tool id with retryCount 10 and a 60 s backoff held a worker ten minutes.
            for (String code : List.of("INSUFFICIENT_CREDITS", "PLAN_UPGRADE_REQUIRED",
                    "CREDENTIAL_SELECTION_UNRESOLVED", "TOOL_NOT_FOUND")) {
                sleeps.clear();
                assertThat(attemptsFor(retries(3, 1000, null), catalogRefusal(code))).as(code).isEqualTo(1);
                assertThat(sleeps).as(code).isEmpty();
                assertThat(finalOf(retries(3, 1000, null), catalogRefusal(code)).output())
                    .containsEntry(ExecutionMetadataKeys.POLICY_RETRY_STOPPED, NodePolicyRunner.STOP_PERMANENT_REFUSAL);
            }
        }

        @Test
        @DisplayName("only the catalog's refusal for lack of credits is a budget refusal, never continued; the others are ordinary failures")
        void onlyTheCreditRefusalIsNeverContinued() throws Exception {
            NodePolicy continuing = new NodePolicy(0, 0L, true);

            assertThat(NodePolicyRunner.isBudgetRefusal(catalogRefusal("INSUFFICIENT_CREDITS"))).isTrue();
            assertThat(finalOf(continuing, catalogRefusal("INSUFFICIENT_CREDITS")).output())
                .doesNotContainKey(ExecutionMetadataKeys.POLICY_CONTINUE_ON_FAILURE);
            for (String code : List.of("PLAN_UPGRADE_REQUIRED", "CREDENTIAL_SELECTION_UNRESOLVED", "TOOL_NOT_FOUND")) {
                assertThat(NodePolicyRunner.isBudgetRefusal(catalogRefusal(code))).as(code).isFalse();
                assertThat(finalOf(continuing, catalogRefusal(code)).output())
                    .as(code).containsEntry(ExecutionMetadataKeys.POLICY_CONTINUE_ON_FAILURE, true);
            }
        }

        @Test
        @DisplayName("the agent's and the workflow's budget tokens are not retried either")
        void budgetTokensAreNotRetried() throws Exception {
            assertThat(attemptsFor(retries(2, 0, null),
                agentFailure("BUDGET_EXHAUSTED: Agent 'Writer' - budget reached", null))).isEqualTo(1);
            assertThat(attemptsFor(retries(2, 0, null),
                agentFailure("WORKFLOW_BUDGET_REACHED: this workflow has spent 10 of its 10 credit cap", null))).isEqualTo(1);
        }

        @Test
        @DisplayName("an agent stopped by a person, out of budget or without tools is not retried (its stop reason)")
        void unretryableStopReasonsAreNotRetried() throws Exception {
            for (String reason : List.of("BUDGET_EXHAUSTED", "STOPPED_BY_USER", "NO_TOOLS", "stopped_by_user")) {
                assertThat(attemptsFor(retries(2, 0, null), agentFailure("stopped", reason)))
                    .as(reason).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("a transient agent failure is retried (a provider I/O error, an unparsable answer, a timeout)")
        void transientAgentFailuresAreRetried() throws Exception {
            assertThat(attemptsFor(retries(2, 0, null),
                agentFailure("Classification error: I/O error on POST request", null))).isEqualTo(3);
            assertThat(attemptsFor(retries(1, 0, null),
                agentFailure("Could not parse classification response", "ERROR"))).isEqualTo(2);
            assertThat(attemptsFor(retries(1, 0, null), agentFailure("timed out", "TIMEOUT"))).isEqualTo(2);
        }

        @Test
        @DisplayName("the budget token must START the error: a message that merely mentions credits is retried")
        void aMentionIsNotARefusal() throws Exception {
            assertThat(attemptsFor(retries(1, 0, null),
                agentFailure("upstream said: insufficient credits on the provider account", null))).isEqualTo(2);
        }

        @Test
        @DisplayName("REGRESSION: a budget refusal is never continued, like the engine's own credit gate")
        void budgetRefusalIsNotContinued() throws Exception {
            NodePolicy continuing = new NodePolicy(0, 0L, true, 0L, false, null);

            NodeExecutionResult refused = finalOf(continuing,
                agentFailure("Insufficient credits (pre-flight tenant budget)", null));
            NodeExecutionResult ordinary = finalOf(continuing, agentFailure("Provider timeout", null));

            assertThat(refused.metadata()).doesNotContainKey(ExecutionMetadataKeys.POLICY_CONTINUE_ON_FAILURE);
            assertThat(ordinary.metadata()).containsEntry(ExecutionMetadataKeys.POLICY_CONTINUE_ON_FAILURE, true);
        }

        @Test
        @DisplayName("the worker's tenant-budget guard (a classify or guardrail refusal, text only) is a budget refusal")
        void tenantBalanceGuardIsABudgetRefusal() throws Exception {
            for (String text : List.of("tenant balance exhausted (12.5 / 10)", "tenant balance is 0",
                    "Classification error: tenant balance 3.2 would be exceeded (run=3 + next=1 = 4)")) {
                assertThat(attemptsFor(retries(2, 0, null), agentFailure(text, null))).as(text).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("a person's stop or an agent without tools is not retried, but it is an ordinary failure: continued")
        void stopIsNotRetriedButContinued() throws Exception {
            NodeExecutionResult last = finalOf(new NodePolicy(2, 0L, true, 0L, false, null),
                agentFailure("stopped", "STOPPED_BY_USER"));

            assertThat(last.output()).containsEntry(ExecutionMetadataKeys.POLICY_RETRY_STOPPED,
                NodePolicyRunner.STOP_PERMANENT_REFUSAL);
            assertThat(last.metadata()).containsEntry(ExecutionMetadataKeys.POLICY_CONTINUE_ON_FAILURE, true);
        }

        @Test
        @DisplayName("under retryOn=rate_limit a budget refusal stops as not_rate_limited")
        void rateLimitOnlyStopsOnBudget() throws Exception {
            NodeExecutionResult last = finalOf(retries(2, 0, NodePolicy.RETRY_ON_RATE_LIMIT),
                agentFailure("Insufficient credits (pre-flight tenant budget)", null));
            assertThat(last.output()).containsEntry(ExecutionMetadataKeys.POLICY_RETRY_STOPPED,
                NodePolicyRunner.STOP_NOT_RATE_LIMITED);
        }
    }

    @Nested
    @DisplayName("afterFailedAttempt - the decision the async agent path shares")
    class SharedDecision {

        @Test
        @DisplayName("retry with the backoff while attempts remain, end on the last one")
        void retriesThenEnds() {
            NodePolicy policy = retries(2, 700, null);
            NodeExecutionResult failure = providerFailure(500, null);
            assertThat(NodePolicyRunner.afterFailedAttempt(policy, NODE, 1, failure))
                .isEqualTo(new NodePolicyRunner.NextAttempt(true, 700L, null));
            assertThat(NodePolicyRunner.afterFailedAttempt(policy, NODE, 3, failure))
                .isEqualTo(new NodePolicyRunner.NextAttempt(false, 0L, null));
        }

        @Test
        @DisplayName("a stop reason ends it early; a provider wait longer than allowed ends it too")
        void earlyStops() {
            assertThat(NodePolicyRunner.afterFailedAttempt(retries(2, 0, null), NODE, 1, providerFailure(404, null)).stopReason())
                .isEqualTo(NodePolicyRunner.STOP_PERMANENT_REFUSAL);
            assertThat(NodePolicyRunner.afterFailedAttempt(retries(2, 0, null), NODE, 1, providerFailure(429, 3_600L)).stopReason())
                .isEqualTo(NodePolicyRunner.STOP_PROVIDER_WAIT_TOO_LONG);
        }

        @Test
        @DisplayName("the wait is the longer of the backoff and the provider's Retry-After")
        void waitIsTheLonger() {
            assertThat(NodePolicyRunner.afterFailedAttempt(retries(1, 1000, null), NODE, 1, providerFailure(429, 5L)).waitMs())
                .isEqualTo(5_000L);
        }
    }

    @Nested
    @DisplayName("Caps on a stored plan written before them")
    class Caps {

        @Test
        @DisplayName("retryCount 50 runs at most 11 attempts")
        void retryCountIsClamped() throws Exception {
            assertThat(attemptsFor(retries(50, 0, null), providerFailure(500, null))).isEqualTo(11);
        }

        @Test
        @DisplayName("retryBackoffMs 1_000_000 waits at most 60000")
        void backoffIsClamped() throws Exception {
            attemptsFor(retries(1, 1_000_000, null), providerFailure(500, null));
            assertThat(sleeps).containsExactly(60_000L);
        }
    }
}

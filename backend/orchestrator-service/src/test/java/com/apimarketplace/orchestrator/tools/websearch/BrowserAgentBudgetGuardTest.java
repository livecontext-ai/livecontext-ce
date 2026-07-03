package com.apimarketplace.orchestrator.tools.websearch;

import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.config.WebSearchConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link BrowserAgentBudgetGuard} - the per-user concurrent +
 * daily-steps budget gate that short-circuits {@code agent_browse} BEFORE
 * the FastAPI submit round-trip.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BrowserAgentBudgetGuard")
class BrowserAgentBudgetGuardTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ListOperations<String, String> listOps;
    @Mock private ValueOperations<String, String> valueOps;

    @BeforeEach
    void wireRedisOps() {
        // Lenient because not every test uses both ops surfaces.
        lenient().when(redisTemplate.opsForList()).thenReturn(listOps);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    /**
     * Guard in FAIL-FAST mode (queue-wait disabled) - the mode where the
     * concurrent pre-check actively rejects. The queue-enabled default is
     * covered by {@link #queueWaitEnabled_skipsConcurrentPreCheck()}.
     */
    private BrowserAgentBudgetGuard guard(int concurrentLimit, int dailyStepsLimit) {
        return new BrowserAgentBudgetGuard(redisTemplate, concurrentLimit, dailyStepsLimit, false);
    }

    // ── Direct guard contract ─────────────────────────────────────────────

    @Test
    @DisplayName("checkBudget returns empty when LLEN < concurrent limit AND GET < daily steps limit")
    void belowBothLimits_returnsEmpty() {
        when(listOps.size(anyString())).thenReturn(0L);
        when(valueOps.get(anyString())).thenReturn("0");

        Optional<ToolExecutionResult> result = guard(1, 200).checkBudget("user-1");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("checkBudget rejects with RATE_LIMITED when concurrent LLEN >= limit")
    void atConcurrentLimit_rejects() {
        when(listOps.size(BrowserAgentBudgetGuard.concurrentKey("user-1"))).thenReturn(1L);

        Optional<ToolExecutionResult> result = guard(1, 200).checkBudget("user-1");

        assertThat(result).isPresent();
        ToolExecutionResult res = result.get();
        assertThat(res.success()).isFalse();
        assertThat(res.errorCode()).isEqualTo(ToolErrorCode.RATE_LIMITED);
        assertThat(res.error())
            .contains("already has an active browser-agent session")
            .contains("limit=1");
        // We never read the steps key once concurrent already failed.
        verify(valueOps, never()).get(anyString());
    }

    @Test
    @DisplayName("queue-wait enabled (default): concurrent pre-check is SKIPPED - the runner queues instead")
    void queueWaitEnabled_skipsConcurrentPreCheck() {
        // Saturated concurrent LIST, but queueing delegated to the runner:
        // the guard must NOT pre-reject (that would defeat the FIFO queue
        // that lets a workflow split run its browser branches in turn).
        lenient().when(listOps.size(BrowserAgentBudgetGuard.concurrentKey("user-1"))).thenReturn(5L);
        when(valueOps.get(anyString())).thenReturn("0");

        BrowserAgentBudgetGuard queued = new BrowserAgentBudgetGuard(redisTemplate, 1, 200, true);
        Optional<ToolExecutionResult> result = queued.checkBudget("user-1");

        assertThat(result).isEmpty();
        // The concurrent LLEN is not even consulted.
        verify(listOps, never()).size(anyString());
    }

    @Test
    @DisplayName("queue-wait enabled still rejects on the daily steps quota (a hard cap, not a queueable resource)")
    void queueWaitEnabled_stillEnforcesDailySteps() {
        String stepsKey = BrowserAgentBudgetGuard.stepsKey("user-1", BrowserAgentBudgetGuard.todayUtc());
        when(valueOps.get(stepsKey)).thenReturn("200");

        BrowserAgentBudgetGuard queued = new BrowserAgentBudgetGuard(redisTemplate, 1, 200, true);
        Optional<ToolExecutionResult> result = queued.checkBudget("user-1");

        assertThat(result).isPresent();
        assertThat(result.get().errorCode()).isEqualTo(ToolErrorCode.RATE_LIMITED);
        assertThat(result.get().error()).contains("daily steps quota exhausted");
    }

    @Test
    @DisplayName("checkBudget rejects with RATE_LIMITED when daily steps GET >= limit")
    void atDailyStepsLimit_rejects() {
        when(listOps.size(anyString())).thenReturn(0L);
        String stepsKey = BrowserAgentBudgetGuard.stepsKey("user-1", BrowserAgentBudgetGuard.todayUtc());
        when(valueOps.get(stepsKey)).thenReturn("200");

        Optional<ToolExecutionResult> result = guard(1, 200).checkBudget("user-1");

        assertThat(result).isPresent();
        assertThat(result.get().errorCode()).isEqualTo(ToolErrorCode.RATE_LIMITED);
        assertThat(result.get().error())
            .contains("daily steps quota exhausted")
            .contains("limit=200")
            .contains("used=200");
    }

    @Test
    @DisplayName("checkBudget skips entirely when userId is blank - no Redis touch")
    void blankUserId_skipsAllChecks() {
        Optional<ToolExecutionResult> result = guard(1, 200).checkBudget("");

        assertThat(result).isEmpty();
        verify(listOps, never()).size(anyString());
        verify(valueOps, never()).get(anyString());
    }

    @Test
    @DisplayName("checkBudget fails open on Redis exception - does NOT block the user")
    void redisExplodes_failsOpen() {
        when(listOps.size(anyString())).thenThrow(new RuntimeException("redis link dropped"));
        when(valueOps.get(anyString())).thenReturn("0");

        Optional<ToolExecutionResult> result = guard(1, 200).checkBudget("user-1");

        // Fail-open is the right choice: a flapping Redis would otherwise
        // lock every user out, and the runner re-checks authoritatively.
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Disabled limits (limit<=0) skip the corresponding check")
    void zeroLimits_skip() {
        // concurrent=0 disables that check; daily=0 disables steps check.
        Optional<ToolExecutionResult> result = guard(0, 0).checkBudget("user-1");

        assertThat(result).isEmpty();
        verify(listOps, never()).size(anyString());
        verify(valueOps, never()).get(anyString());
    }

    @Test
    @DisplayName("Key formats match the Python-side budget_gate.py exactly")
    void keyFormatsMatchPythonSide() {
        assertThat(BrowserAgentBudgetGuard.concurrentKey("u9"))
            .isEqualTo("agent:browser:user:u9:concurrent");
        assertThat(BrowserAgentBudgetGuard.stepsKey("u9", "2026-04-25"))
            .isEqualTo("agent:browser:user:u9:steps:2026-04-25");
    }

    // ── Integration with BrowserAgentModule ───────────────────────────────

    @Test
    @DisplayName("Module short-circuits BEFORE FastAPI submit when concurrent budget exhausted")
    void moduleShortCircuitsBeforeSubmit() {
        // Wire a guard that always rejects.
        BrowserAgentBudgetGuard rejectingGuard = new BrowserAgentBudgetGuard(redisTemplate, 1, 200) {
            @Override
            public Optional<ToolExecutionResult> checkBudget(String userId) {
                return Optional.of(ToolExecutionResult.failure(
                    ToolErrorCode.RATE_LIMITED,
                    "test rejection: concurrent saturated"));
            }
        };

        WebSearchConfig wsConfig = new WebSearchConfig();
        ObjectMapper om = new ObjectMapper();
        // We only need the restTemplate stub to verify it is NOT touched.
        RestTemplate restTemplate = org.mockito.Mockito.mock(RestTemplate.class);

        BrowserAgentModule module = new BrowserAgentModule(
            restTemplate, wsConfig, redisTemplate, om, rejectingGuard);

        // Tests need a tenantId to trigger the gate - pass it via context.
        ToolExecutionContext ctx = new ToolExecutionContext(
            "tenant-x",
            Map.of(),
            Map.of(), java.util.Set.of(),
            null, null, null, null);

        Optional<ToolExecutionResult> result = module.execute(
            "agent_browse", Map.of("task", "x"), null, ctx);

        assertThat(result).isPresent();
        assertThat(result.get().success()).isFalse();
        assertThat(result.get().errorCode()).isEqualTo(ToolErrorCode.RATE_LIMITED);
        assertThat(result.get().error()).contains("test rejection");

        // CRITICAL: the FastAPI submit must NOT have been called.
        verify(restTemplate, never()).postForObject(anyString(), any(), eq(Map.class));
    }

    @Test
    @DisplayName("Module skips guard when tenantId is null (legacy/test path)")
    void moduleSkipsGuardWhenNoTenant() {
        // A guard that would reject if asked - verify it is never asked.
        BrowserAgentBudgetGuard rejectingGuard = new BrowserAgentBudgetGuard(redisTemplate, 1, 200) {
            @Override
            public Optional<ToolExecutionResult> checkBudget(String userId) {
                throw new AssertionError("guard must not be called when tenantId is blank");
            }
        };

        WebSearchConfig wsConfig = new WebSearchConfig();
        ObjectMapper om = new ObjectMapper();
        RestTemplate restTemplate = org.mockito.Mockito.mock(RestTemplate.class);
        // Stub submit so the call doesn't NPE downstream.
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-x"));
        // The leftPop must return SOMETHING so submitAndAwait completes.
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(anyString(), any(java.time.Duration.class)))
            .thenReturn("{\"final_result\":\"ok\",\"stop_reason\":\"COMPLETED\"}");

        BrowserAgentModule module = new BrowserAgentModule(
            restTemplate, wsConfig, redisTemplate, om, rejectingGuard);

        // No context, no tenantId arg → guard MUST be skipped.
        // The `llm` block is required to bypass the CE deployment guardrail
        // (agentClient == null && llm == null → MISSING_PARAMETER); we keep
        // the runner path itself reachable so `fetchFinalResult` evaluates
        // its stop_reason fail-closed contract on the stubbed payload.
        Optional<ToolExecutionResult> result = module.execute(
            "agent_browse",
            Map.of(
                "task", "x",
                "llm", Map.of("provider", "openai", "model", "gpt-4")),
            null, null);

        assertThat(result).isPresent();
        assertThat(result.get().success()).isTrue();
    }
}

package com.apimarketplace.orchestrator.tools.websearch;

import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Per-user budget gate for {@code agent_browse} sessions.
 *
 * <p>Mirrors the Python-side gate in
 * {@code websearch-service/app/services/browser_agent/budget_gate.py} so we can
 * short-circuit the {@code POST /jobs/submit} round-trip when the per-user
 * caps are already saturated. This is purely a fast-path: the runner does the
 * authoritative LPUSH-and-check (the runner is the only writer of the
 * {@code :concurrent} LIST). We only READ here.</p>
 *
 * <p>Two counters, both keyed on the user_id (= {@code tenantId} in this system):</p>
 * <ul>
 *   <li>{@code agent:browser:user:{uid}:concurrent} - LIST. Reading {@code LLEN}
 *       tells us how many sessions are in-flight. We reject before submit when
 *       the count already meets/exceeds the limit.</li>
 *   <li>{@code agent:browser:user:{uid}:steps:{YYYY-MM-DD}} - STRING. Reading
 *       {@code GET} tells us how many steps have been used today. We reject
 *       when the cumulative count meets/exceeds the daily limit (the runner's
 *       INCR could push it 1 over the soft cap, but a 1-step grace is fine).</li>
 * </ul>
 *
 * <p><b>Bridge billing - see CLAUDE.md "Bridges - never short-circuit billing".</b>
 * The browser-agent budget is a *resource* gate (Chromium time + LLM steps),
 * NOT a *price* gate. Bridge sessions ARE counted toward the per-user step
 * quota exactly like direct-API sessions - the user opted into a billed model
 * (internal credits via the bridge); the steps cap is independent of model
 * pricing. There is no provider_kind='bridge' bypass here.</p>
 *
 * <p>Disabled when {@code websearch.enabled=false} (CE mode without the web
 * stack) - the {@link BrowserAgentModule} is also gated on the same property,
 * so the guard is never injected when the module isn't.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "websearch.enabled", havingValue = "true", matchIfMissing = true)
public class BrowserAgentBudgetGuard {

    /** Key format: {@code agent:browser:user:{uid}:concurrent}. Same as Python side. */
    private static final String CONCURRENT_KEY_FMT = "agent:browser:user:%s:concurrent";

    /** Key format: {@code agent:browser:user:{uid}:steps:{ymd}}. Same as Python side. */
    private static final String STEPS_KEY_FMT = "agent:browser:user:%s:steps:%s";

    private static final DateTimeFormatter UTC_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final StringRedisTemplate redisTemplate;
    private final int concurrentLimit;
    private final int dailyStepsLimit;

    public BrowserAgentBudgetGuard(
            @Qualifier("webSearchRedisTemplate") StringRedisTemplate redisTemplate,
            @Value("${websearch.browser-agent.per-user-concurrent-limit:1}") int concurrentLimit,
            @Value("${websearch.browser-agent.per-user-daily-steps-limit:200}") int dailyStepsLimit) {
        this.redisTemplate = redisTemplate;
        this.concurrentLimit = concurrentLimit;
        this.dailyStepsLimit = dailyStepsLimit;
    }

    /**
     * Check whether the user has budget left to start a new browser-agent session.
     *
     * @param userId the user / tenant id (must NOT be null/blank - caller short-circuits otherwise)
     * @return {@link Optional#empty()} when the user is within budget;
     *         a {@link ToolExecutionResult#failure} carrying
     *         {@link ToolErrorCode#RATE_LIMITED} when a cap is hit.
     */
    public Optional<ToolExecutionResult> checkBudget(String userId) {
        if (userId == null || userId.isBlank()) {
            // No user context - production code paths always have one; this
            // branch is hit only by test fixtures + diagnostic tooling.
            return Optional.empty();
        }

        // 1. Concurrent session limit (LLEN).
        if (concurrentLimit > 0) {
            String concurrentKey = String.format(CONCURRENT_KEY_FMT, userId);
            Long len = safeLLen(concurrentKey);
            if (len != null && len >= concurrentLimit) {
                log.info("Browser-agent concurrent budget exhausted: userId={} llen={} limit={}",
                    userId, len, concurrentLimit);
                return Optional.of(ToolExecutionResult.failure(
                    ToolErrorCode.RATE_LIMITED,
                    "Browser agent rejected: user already has an active browser-agent session "
                    + "(limit=" + concurrentLimit + ", in-flight=" + len + "). "
                    + "Wait for the current session to finish before starting another."));
            }
        }

        // 2. Daily steps quota (GET).
        if (dailyStepsLimit > 0) {
            String stepsKey = String.format(STEPS_KEY_FMT, userId, todayUtc());
            Integer steps = safeGetInt(stepsKey);
            if (steps != null && steps >= dailyStepsLimit) {
                log.info("Browser-agent daily steps budget exhausted: userId={} steps={} limit={}",
                    userId, steps, dailyStepsLimit);
                return Optional.of(ToolExecutionResult.failure(
                    ToolErrorCode.RATE_LIMITED,
                    "Browser agent rejected: daily steps quota exhausted "
                    + "(limit=" + dailyStepsLimit + " steps/day, used=" + steps + "). "
                    + "Quota resets at 00:00 UTC."));
            }
        }

        return Optional.empty();
    }

    /** Visible for tests. Format MUST match the Python-side `concurrent_key`. */
    public static String concurrentKey(String userId) {
        return String.format(CONCURRENT_KEY_FMT, userId);
    }

    /** Visible for tests. Format MUST match the Python-side `steps_key`. */
    public static String stepsKey(String userId, String ymd) {
        return String.format(STEPS_KEY_FMT, userId, ymd);
    }

    /** UTC date-string, same as the Python-side `_today_utc()`. */
    static String todayUtc() {
        return LocalDate.now(ZoneOffset.UTC).format(UTC_DATE);
    }

    private Long safeLLen(String key) {
        try {
            return redisTemplate.opsForList().size(key);
        } catch (Exception e) {
            log.warn("BrowserAgentBudgetGuard LLEN failed for {}: {}", key, e.getMessage());
            return null;  // fail-open: don't block on Redis hiccups, the runner will reject
        }
    }

    private Integer safeGetInt(String key) {
        try {
            String raw = redisTemplate.opsForValue().get(key);
            if (raw == null || raw.isBlank()) return null;
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("BrowserAgentBudgetGuard non-numeric value at {}: {}", key, e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("BrowserAgentBudgetGuard GET failed for {}: {}", key, e.getMessage());
            return null;
        }
    }
}

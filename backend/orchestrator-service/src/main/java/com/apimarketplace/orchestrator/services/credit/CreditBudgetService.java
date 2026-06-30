package com.apimarketplace.orchestrator.services.credit;

import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.common.scaling.cache.DistributedBudgetCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * In-memory credit budget tracker for workflow executions.
 *
 * Fetches the user's balance ONCE at workflow start, then decrements locally
 * on each node execution - zero HTTP overhead during execution.
 *
 * Supports shared per-tenant budget: multiple concurrent workflows share the same
 * budget entry via reference counting. Budget is only removed when the last
 * workflow completes.
 *
 * This is an optimistic local mirror, NOT the source of truth.
 * The actual balance is managed by auth-service (CreditService).
 * The async credit consumption (CreditConsumptionClient) still runs in parallel
 * to keep the real balance in sync.
 *
 * Budget storage is delegated to {@link DistributedBudgetCache}, which defaults
 * to an in-memory implementation (CE mode) and can be replaced with a Redis-backed
 * implementation for horizontal scaling (EE mode).
 */
@Service
public class CreditBudgetService {

    private static final Logger log = LoggerFactory.getLogger(CreditBudgetService.class);

    private static final String REFCOUNT_PREFIX = "refcount:";
    private static final int MAX_CAS_RETRIES = 100;

    private final CreditConsumptionClient creditClient;
    private final DistributedBudgetCache budgetCache;

    public CreditBudgetService(CreditConsumptionClient creditClient, DistributedBudgetCache budgetCache) {
        this.creditClient = creditClient;
        this.budgetCache = budgetCache;
    }

    /**
     * Initialize the budget for a user if not already present (setIfAbsent).
     * Called at workflow/trigger start. Multiple concurrent workflows share the same budget.
     * If auth-service is unreachable, budget will be ZERO (fail-closed via fetchBalance).
     */
    public void initBudget(String userId) {
        if (userId == null || userId.isBlank()) return;
        if (creditClient == null) return;

        if (!budgetCache.exists(userId)) {
            BigDecimal balance = creditClient.fetchBalance(userId);
            if (budgetCache.setIfAbsent(userId, balance)) {
                log.info("Credit budget initialized for user {}: {}", userId, balance);
            }
        }
    }

    /**
     * Increment the active workflow count for a user.
     * Call at workflow start, paired with decrementActiveWorkflows on completion.
     *
     * <p>Uses the distributed budget cache with a "refcount:" prefix so the count
     * is consistent across all instances in a multi-instance deployment.
     */
    public void incrementActiveWorkflows(String userId) {
        if (userId == null || userId.isBlank()) return;
        String refKey = REFCOUNT_PREFIX + userId;
        for (int attempt = 0; attempt < MAX_CAS_RETRIES; attempt++) {
            BigDecimal current = budgetCache.get(refKey);
            if (current == null) {
                if (budgetCache.setIfAbsent(refKey, BigDecimal.ONE)) {
                    log.debug("Active workflows for user {}: 1", userId);
                    return;
                }
                continue;
            }
            BigDecimal next = current.add(BigDecimal.ONE);
            if (budgetCache.compareAndSet(refKey, current, next)) {
                log.debug("Active workflows for user {}: {}", userId, next.intValue());
                return;
            }
        }
        log.error("CAS retry limit reached in incrementActiveWorkflows for user {}", userId);
    }

    /**
     * Decrement the active workflow count for a user.
     * When count reaches 0, removes both the counter and the budget entry.
     *
     * <p>Uses the distributed budget cache so the count is consistent across
     * all instances in a multi-instance deployment.
     */
    public void decrementActiveWorkflows(String userId) {
        if (userId == null || userId.isBlank()) return;
        String refKey = REFCOUNT_PREFIX + userId;
        for (int attempt = 0; attempt < MAX_CAS_RETRIES; attempt++) {
            BigDecimal current = budgetCache.get(refKey);
            if (current == null) {
                budgetCache.remove(userId);
                return;
            }
            BigDecimal next = current.subtract(BigDecimal.ONE);
            if (next.compareTo(BigDecimal.ZERO) <= 0) {
                if (budgetCache.compareAndSet(refKey, current, BigDecimal.ZERO)) {
                    budgetCache.remove(refKey);
                    budgetCache.remove(userId);
                    log.info("All workflows completed for user {}, budget removed", userId);
                    return;
                }
            } else {
                if (budgetCache.compareAndSet(refKey, current, next)) {
                    log.debug("Active workflows for user {} after decrement: {}", userId, next.intValue());
                    return;
                }
            }
        }
        log.error("CAS retry limit reached in decrementActiveWorkflows for user {}", userId);
    }

    /**
     * Refresh the budget by re-fetching from auth-service.
     * Only updates if the new balance is LOWER than current (conservative approach
     * to avoid race conditions where async consumption has already reduced the local budget).
     */
    public void refreshBudget(String userId) {
        if (userId == null || userId.isBlank()) return;
        if (creditClient == null) return;

        BigDecimal current = budgetCache.get(userId);
        if (current == null) {
            // No budget tracked yet, initialize
            initBudget(userId);
            return;
        }

        BigDecimal freshBalance = creditClient.fetchBalance(userId);
        for (int attempt = 0; attempt < MAX_CAS_RETRIES; attempt++) {
            current = budgetCache.get(userId);
            if (current == null) break;
            if (freshBalance.compareTo(current) < 0) {
                if (budgetCache.compareAndSet(userId, current, freshBalance)) {
                    log.info("Credit budget refreshed for user {}: {} -> {}", userId, current, freshBalance);
                    break;
                }
            } else {
                log.debug("Credit budget refresh skipped for user {}: current={} <= fresh={}",
                        userId, current, freshBalance);
                break;
            }
        }
    }

    /**
     * Try to consume credits from the local budget.
     * Returns true if sufficient credits remain, false if budget exhausted.
     * Thread-safe via CAS loop.
     *
     * If the user has no budget tracked yet, auto-initializes from auth-service
     * instead of silently allowing (fail-open). This prevents unbilled execution
     * when initBudget() was not called (e.g., step-by-step mode, signal resume).
     */
    public boolean tryConsume(String userId, BigDecimal cost) {
        if (userId == null || userId.isBlank()) return true;

        BigDecimal current = budgetCache.get(userId);
        if (current == null) {
            // Auto-init: fetch balance from auth-service instead of fail-open
            initBudget(userId);
            current = budgetCache.get(userId);
            if (current == null) return true; // creditClient is null or disabled - allow
        }

        for (int attempt = 0; attempt < MAX_CAS_RETRIES; attempt++) {
            current = budgetCache.get(userId);
            if (current == null) return true;
            if (current.compareTo(cost) < 0) {
                log.warn("Credit budget exhausted for user {}: budget={}, cost={}", userId, current, cost);
                return false;
            }
            if (budgetCache.compareAndSet(userId, current, current.subtract(cost))) {
                return true;
            }
        }
        log.error("CAS retry limit reached in tryConsume for user {}", userId);
        return false; // fail-closed on CAS exhaustion
    }

    /**
     * Try to consume 1 credit (standard node cost).
     */
    public boolean tryConsumeOne(String userId) {
        return tryConsume(userId, BigDecimal.ONE);
    }

    /**
     * Get remaining local budget (for logging/debugging).
     */
    public BigDecimal getRemainingBudget(String userId) {
        return budgetCache.get(userId);
    }

    /**
     * Cost-aware pre-flight gate for a workflow agent dispatch, mirroring the
     * chat path's {@code checkChatBudget} (ChatControllerV3 uses the same auth-service
     * endpoint). Asks auth-service whether the user's balance covers the projected cost
     * of this agent turn before we spend time dispatching to agent-service and paying
     * for the LLM call.
     *
     * <p>Fail-closed behaviour is delegated to {@link CreditConsumptionClient#checkChatBudget}:
     * unknown provider/model, auth-service unreachable with no valid cache → {@code false}.
     * The in-memory budget cache used by {@link #tryConsume} is not consulted here because
     * it tracks workflow-node flat fees (1 credit per node), not model-aware token cost.
     *
     * <p>Returns {@code true} when auth-service is disabled (CE mode without billing).
     *
     * @param userId          tenant / user id
     * @param provider        llm provider (e.g. {@code openai}, {@code claude-code})
     * @param model           llm model (e.g. {@code gpt-4o}, {@code claude-opus-4-6})
     * @param estPromptTokens upper-bound estimate of prompt tokens (include context + tools)
     * @param estCompletionTokens upper-bound estimate of completion tokens (agent's maxTokens)
     * @return {@code true} if the call is allowed to proceed
     */
    public boolean preflightAgentBudget(String userId, String provider, String model,
                                         int estPromptTokens, int estCompletionTokens) {
        if (creditClient == null) return true;
        return creditClient.checkChatBudget(userId, provider, model,
                estPromptTokens, estCompletionTokens);
    }
}

package com.apimarketplace.agent.service.cloud;

import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.common.credit.LlmCacheTokens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Settles a centralized CE LLM relay execution from the cloud-side accrual into ONE
 * {@code CE_LLM_RELAY} ledger line. Single source of the settle logic, shared by both triggers:
 * <ul>
 *   <li>the on-demand terminal settle ({@code POST /api/ce-llm/settle}), fired by CE when its agent
 *       loop ends;</li>
 *   <li>the crash-recovery {@link CeRelayReaperService} sweep, for executions whose CE never sent a
 *       terminal settle.</li>
 * </ul>
 *
 * <p>Idempotent: the underlying {@code consumeForCeRelay} dedupes on {@code (executionId,
 * CE_LLM_RELAY)}, so the terminal settle racing the reaper (or a retry) never double-bills. The
 * accrual is dropped only after the debit lands; a hard failure leaves it for the next attempt.
 */
@Service
public class CeRelaySettlementService {

    private static final Logger log = LoggerFactory.getLogger(CeRelaySettlementService.class);
    private static final String SOURCE_TYPE = "CE_LLM_RELAY";

    private final CeRelayAccrualStore accrualStore;
    private final CreditConsumptionClient creditClient;

    public CeRelaySettlementService(CeRelayAccrualStore accrualStore, CreditConsumptionClient creditClient) {
        this.accrualStore = accrualStore;
        this.creditClient = creditClient;
    }

    public enum SettleOutcome {
        /** A ledger line was written (or the idempotent already-settled no-op). */
        BILLED,
        /** Nothing to settle - no accrual, or a zero-usage phantom (cleaned up). */
        NOTHING_TO_BILL,
        /** A non-retryable debit failure (e.g. no active subscription): the usage was written to the
         *  dead-letter for reconciliation and the accrual dropped, so it is NOT retried forever. */
        DEAD_LETTERED,
        /** A transient/unattributable failure - accrual kept so the reaper retries later. */
        RETRY
    }

    /**
     * Settle one execution from its accrued usage. Drops the accrual on success / nothing-to-bill;
     * keeps it on a retryable failure.
     */
    public SettleOutcome settleFromAccrual(String executionId) {
        if (executionId == null || executionId.isBlank()) {
            return SettleOutcome.NOTHING_TO_BILL;
        }
        Optional<CeRelayAccrualStore.AccruedSnapshot> snapOpt = accrualStore.snapshot(executionId);
        if (snapOpt.isEmpty()) {
            // Already settled (accrual removed) or never accrued - idempotent no-op.
            accrualStore.remove(executionId); // drop any dangling index member
            return SettleOutcome.NOTHING_TO_BILL;
        }
        CeRelayAccrualStore.AccruedSnapshot snap = snapOpt.get();
        CeRelayAccrualStore.AccruedUsage u = snap.usage();
        if (u.promptTokens() <= 0 && u.completionTokens() <= 0) {
            // No billable tokens accrued - clean up the phantom rather than write a 0-row.
            accrualStore.remove(executionId);
            return SettleOutcome.NOTHING_TO_BILL;
        }
        if (snap.userId() == null || snap.userId().isBlank()) {
            log.warn("[CeRelaySettle] accrual for execution {} has no userId - cannot attribute, leaving for inspection",
                    executionId);
            return SettleOutcome.RETRY;
        }
        return settleDebit(snap, executionId, u);
    }

    private SettleOutcome settleDebit(CeRelayAccrualStore.AccruedSnapshot snap, String executionId,
                                      CeRelayAccrualStore.AccruedUsage u) {
        int prompt = clampToInt(u.promptTokens());
        int completion = clampToInt(u.completionTokens());
        var result = creditClient.consumeCredits(
                snap.userId(), SOURCE_TYPE, executionId, snap.provider(), snap.model(),
                prompt, completion,
                new LlmCacheTokens(
                        clampToInt(u.cacheCreationTokens()), clampToInt(u.cacheReadTokens()),
                        clampToInt(u.cachedTokens()), clampToInt(u.reasoningTokens())));
        // Require an EXPLICIT success signal before dropping the accrual - never treat a null or a
        // body missing "success" as billed (that would silently lose the usage).
        if (result != null && Boolean.TRUE.equals(result.get("success"))) {
            accrualStore.remove(executionId);
            return SettleOutcome.BILLED;
        }
        String reason = result == null
                ? "no response from credit service"
                : String.valueOf(result.getOrDefault("error", "unknown rejection"));
        if (isNonRetryable(reason)) {
            // Permanent (no active subscription): retrying for days then GC-ing on TTL would lose
            // the usage silently. Dead-letter it for reconciliation and stop retrying.
            creditClient.persistRejection(snap.userId(), SOURCE_TYPE, executionId,
                    snap.provider(), snap.model(), prompt, completion, reason);
            accrualStore.remove(executionId);
            log.warn("[CeRelaySettle] execution {} non-retryable ({}) - dead-lettered + accrual dropped",
                    executionId, reason);
            return SettleOutcome.DEAD_LETTERED;
        }
        log.warn("[CeRelaySettle] transient settle failure for execution {} ({}) - keeping accrual for retry",
                executionId, reason);
        return SettleOutcome.RETRY;
    }

    /**
     * A debit failure that will never succeed on retry: no active subscription (consumeForCeRelay
     * uses allowNegative, so a balance shortfall never fails - a 402/subscription error means there
     * is no wallet to debit at all). Transport/timeout errors are retryable and excluded.
     */
    private static boolean isNonRetryable(String reason) {
        String r = reason == null ? "" : reason.toLowerCase();
        return r.contains("402") || r.contains("subscription");
    }

    private static int clampToInt(long v) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, v));
    }
}

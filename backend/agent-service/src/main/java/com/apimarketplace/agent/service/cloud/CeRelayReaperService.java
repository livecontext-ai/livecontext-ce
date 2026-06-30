package com.apimarketplace.agent.service.cloud;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Crash-recovery reaper for centralized CE LLM relay billing.
 *
 * <p>The happy path settles a centralized execution as one {@code CE_LLM_RELAY} ledger line when CE
 * sends {@code /settle} at the agent-loop terminal. If a CE install crashes (or its network drops)
 * before sending that, the accrued usage would sit unbilled in {@link CeRelayAccrualStore}. This
 * sweeper periodically finds executions whose accrual has not been touched for a grace window -
 * i.e. abandoned - and settles them via {@link CeRelaySettlementService}, so consumed tokens are
 * NEVER lost.
 *
 * <p><b>Idempotent &amp; distributed-safe.</b> Settlement keys on {@code (executionId,
 * CE_LLM_RELAY)} which {@code consumeForCeRelay} dedupes, so racing a late {@code /settle} (or a
 * second instance) never double-bills. ShedLock still serialises the sweep across instances to
 * avoid wasted work.
 */
@Component
public class CeRelayReaperService {

    private static final Logger log = LoggerFactory.getLogger(CeRelayReaperService.class);

    private final CeRelayAccrualStore accrualStore;
    private final CeRelaySettlementService settlementService;

    /** Only reap executions whose accrual has been idle this long - must exceed a normal run. */
    @Value("${ce-relay.reaper.grace-seconds:1800}")
    private long graceSeconds;

    @Value("${ce-relay.reaper.batch-size:200}")
    private int batchSize;

    public CeRelayReaperService(CeRelayAccrualStore accrualStore, CeRelaySettlementService settlementService) {
        this.accrualStore = accrualStore;
        this.settlementService = settlementService;
    }

    @Scheduled(cron = "${ce-relay.reaper.cron:0 */5 * * * *}")
    @SchedulerLock(name = "ceRelayReaper_tick", lockAtMostFor = "PT4M", lockAtLeastFor = "PT5S")
    public void tick() {
        try {
            int settled = reapOnce(System.currentTimeMillis());
            if (settled > 0) {
                log.info("[CeRelayReaper] settled {} abandoned CE relay execution(s)", settled);
            }
        } catch (Exception e) {
            log.error("[CeRelayReaper] sweep failed", e);
        }
    }

    /**
     * Settle every accrual idle since before {@code nowEpochMs - grace}. Returns how many produced
     * a billed line (idempotent no-ops, phantoms and retries are not counted). Visible for tests.
     */
    int reapOnce(long nowEpochMs) {
        long cutoff = nowEpochMs - graceSeconds * 1000L;
        List<String> stale = accrualStore.findStale(cutoff, batchSize);
        int billed = 0;
        for (String executionId : stale) {
            if (settlementService.settleFromAccrual(executionId)
                    == CeRelaySettlementService.SettleOutcome.BILLED) {
                billed++;
            }
        }
        return billed;
    }
}

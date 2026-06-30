package com.apimarketplace.orchestrator.trigger.strategy;

import com.apimarketplace.orchestrator.trigger.ProductionRunResolver;
import com.apimarketplace.orchestrator.trigger.TriggerType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Round-7 PR4: routes incoming trigger dispatches to the right
 * {@link TriggerExecutionStrategy} based on {@link TriggerType}, and exposes shared
 * observability (Prometheus counter on {@code trigger_dispatch_total{trigger_type, verdict}}).
 *
 * <p>This is the unified entry-point that PR4 dispatchers (existing
 * {@code ScheduleExecutorService}, {@code WebhookDispatchService}, …) call to get a
 * verdict before deciding their per-trigger surface (HTTP code, cron skip, etc.).
 *
 * <p>Adoption is incremental: existing dispatchers keep their resolver call and just
 * additionally record the verdict via {@link #recordVerdict}. A future PR can collapse
 * the dispatchers entirely once the strategy interface covers their per-trigger
 * branching needs.
 */
@Component
public class TriggerDispatchCoordinator {

    private static final Logger logger = LoggerFactory.getLogger(TriggerDispatchCoordinator.class);
    private static final String METRIC_DISPATCH_TOTAL = "trigger_dispatch_total";

    private final Map<TriggerType, TriggerExecutionStrategy> byType =
        new EnumMap<>(TriggerType.class);
    private final ProductionRunResolver resolver;
    private final MeterRegistry meterRegistry;

    public TriggerDispatchCoordinator(List<TriggerExecutionStrategy> strategies,
                                      ProductionRunResolver resolver,
                                      MeterRegistry meterRegistry) {
        this.resolver = resolver;
        this.meterRegistry = meterRegistry;
        for (TriggerExecutionStrategy s : strategies) {
            TriggerExecutionStrategy existing = byType.put(s.triggerType(), s);
            if (existing != null) {
                throw new IllegalStateException(
                    "Two strategies registered for " + s.triggerType() +
                    ": " + existing.getClass() + " and " + s.getClass());
            }
        }
        logger.info("[TriggerDispatchCoordinator] registered {} strategies: {}",
            byType.size(), byType.keySet());
    }

    /**
     * Resolve the verdict for a trigger fire. Returns {@code DispatchVerdict.REFUSE_NO_TRIGGER}
     * if no strategy is registered for {@code triggerType} (defensive - should not happen
     * once all 5 strategies are wired).
     */
    public DispatchVerdict resolveVerdict(TriggerType triggerType, UUID workflowId) {
        TriggerExecutionStrategy strategy = byType.get(triggerType);
        if (strategy == null) {
            logger.warn("[TriggerDispatchCoordinator] no strategy for {} - verdict REFUSE_NO_TRIGGER",
                triggerType);
            recordVerdict(triggerType, DispatchVerdict.REFUSE_NO_TRIGGER);
            return DispatchVerdict.REFUSE_NO_TRIGGER;
        }
        DispatchVerdict verdict = strategy.resolveVerdict(workflowId, resolver);
        recordVerdict(triggerType, verdict);
        return verdict;
    }

    /**
     * Direct policy resolution - bypasses verdict recording. Used by existing
     * dispatchers that already have their own {@code productionRunResolver.resolve}
     * call site and only need to know "what policy do I use?".
     */
    public ProductionRunResolver.RunSelectionPolicy policyFor(TriggerType triggerType) {
        TriggerExecutionStrategy strategy = byType.get(triggerType);
        return strategy == null
            ? ProductionRunResolver.RunSelectionPolicy.LATEST_TRUSTED
            : strategy.runSelectionPolicy();
    }

    /**
     * Record an externally-resolved verdict. Used by dispatchers that retain their own
     * orchestration but want to emit the unified observability counter.
     */
    public void recordVerdict(TriggerType triggerType, DispatchVerdict verdict) {
        Counter.builder(METRIC_DISPATCH_TOTAL)
            .description("Trigger dispatch outcome counter (round-7 unified verdict)")
            .tags(Tags.of(
                "trigger_type", triggerType.getValue(),
                "verdict", verdict.name()
            ))
            .register(meterRegistry)
            .increment();
    }
}

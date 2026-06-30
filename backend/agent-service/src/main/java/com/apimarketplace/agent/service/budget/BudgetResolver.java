package com.apimarketplace.agent.service.budget;

import com.apimarketplace.agent.domain.AgentEntity;
import com.apimarketplace.agent.repository.AgentRepository;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

/**
 * Lazy budget reset service.
 *
 * <p>Activates the previously-dead {@code budget_reset_mode} / {@code budget_last_reset}
 * columns. Called once at the start of every agent execution. If the period associated
 * with the agent's {@code budgetResetMode} has rolled over since {@code budgetLastReset},
 * the {@code creditsConsumed} accumulator is reset to {@code 0} and {@code budgetLastReset}
 * is updated. The change is persisted in the same call.</p>
 *
 * <p>Supported reset modes:
 * <ul>
 *   <li>{@code cumulative} (default) - never resets, lifetime accumulator</li>
 *   <li>{@code weekly} - resets after 7 days have elapsed since the last reset</li>
 *   <li>{@code monthly} - resets when the calendar month differs from the last reset's month</li>
 * </ul>
 * Unknown values are treated as {@code cumulative}.</p>
 */
@Service
public class BudgetResolver {

    private static final Logger log = LoggerFactory.getLogger(BudgetResolver.class);

    private final AgentRepository agentRepository;
    private final EntityManager entityManager;

    public BudgetResolver(AgentRepository agentRepository,
                          EntityManager entityManager) {
        this.agentRepository = agentRepository;
        this.entityManager = entityManager;
    }

    /**
     * Load an agent inside a transaction before resolving its budget state.
     *
     * <p>PostgreSQL large-object backed {@code @Lob} fields cannot be materialized
     * in auto-commit mode. Guard construction happens from controller/request paths
     * that are not guaranteed to have an open transaction, so the lookup belongs in
     * this service rather than in callers.</p>
     */
    @Transactional
    public BudgetState resolveAndPersistForAgent(UUID agentId, String organizationId, Instant now) {
        if (agentId == null) {
            return BudgetState.disabled();
        }

        Optional<AgentEntity> entity = organizationId != null && !organizationId.isBlank()
            ? agentRepository.findByIdAndOrganizationIdStrict(agentId, organizationId)
            : agentRepository.findById(agentId);

        return entity.map(agent -> resolveAndPersist(agent, now))
            .orElseGet(BudgetState::disabled);
    }

    /**
     * Resolve the current effective budget state for an agent and persist any reset.
     *
     * <p><strong>Transaction:</strong> annotated {@code @Transactional} so that callers from
     * non-transactional contexts (e.g. {@code AgentRemoteExecutionService.buildAgentGuard},
     * invoked outside any service-level transaction) still get a write transaction opened
     * via Spring's proxy when a reset needs to be persisted. Callers already inside a
     * transaction simply join it. The method is idempotent: if no reset is due it performs
     * no writes, so the open transaction is harmless.</p>
     *
     * @param agent the agent entity (mutated and saved if a reset is due)
     * @param now   reference instant (typically {@code Instant.now()}; injected for tests)
     * @return the post-reset {@link BudgetState}
     */
    @Transactional
    public BudgetState resolveAndPersist(AgentEntity agent, Instant now) {
        if (agent == null) {
            return BudgetState.disabled();
        }
        BigDecimal totalBudget = agent.getCreditBudget();
        if (totalBudget == null || totalBudget.signum() <= 0) {
            return BudgetState.disabled();
        }

        String mode = agent.getBudgetResetMode();
        if (mode == null) mode = "cumulative";

        boolean reset = switch (mode) {
            case "weekly"  -> isMoreThan7DaysAgo(agent.getBudgetLastReset(), now);
            case "monthly" -> isInPreviousCalendarMonth(agent.getBudgetLastReset(), now);
            default        -> false; // cumulative or unknown → never reset
        };

        if (reset) {
            // Targeted CAS UPDATE with atomic credits_reserved = 0 gate (§4.6).
            // The prior pattern (setCreditsConsumed(ZERO); save(agent)) would:
            //  (1) silently no-op post-@DynamicUpdate + updatable=false on credits_consumed
            //  (2) race with sibling tryReserve UPDATEs by clobbering credits_reserved
            //      back to whatever stale in-memory value the entity held.
            //
            // Dispatch on nullness of budgetLastReset: Postgres prepared statements cannot
            // infer the type of a null parameter that only appears in an IS NULL disjunction
            // (SQLSTATE 42P18), so the repository exposes two physical queries - one for the
            // first-reset branch, one for steady-state. H2 accepts the unified form, which is
            // why the original single-query implementation passed unit tests and only blew up
            // at runtime on the first real agent reset in prod.
            Instant expectedLastReset = agent.getBudgetLastReset(); // may be null (first reset)
            int updated = expectedLastReset == null
                ? agentRepository.resetConsumedIfFirstReset(agent.getId(), now)
                : agentRepository.resetConsumedIfUnreservedAndUnchanged(
                    agent.getId(), now, expectedLastReset);

            // Detach defensively: any subsequent dirty flush of `agent` at transaction commit
            // would re-issue an UPDATE with stale in-memory values, racing the one we just
            // landed. Detaching makes that bug structurally impossible.
            entityManager.detach(agent);

            if (updated == 1) {
                log.info("[BUDGET-RESET] agent={} mode={} -> creditsConsumed reset to 0 (lastReset={})",
                    agent.getId(), mode, now);
                return new BudgetState(totalBudget, BigDecimal.ZERO, BigDecimal.ZERO, true);
            }
            // CAS lost - another thread already reset, credits_reserved > 0, or the reset
            // was not actually due. Re-read fresh state and fall through to the non-reset path.
            AgentEntity fresh = agentRepository.findById(agent.getId()).orElse(null);
            BigDecimal freshConsumed = fresh != null && fresh.getCreditsConsumed() != null
                ? fresh.getCreditsConsumed() : BigDecimal.ZERO;
            BigDecimal freshReserved = fresh != null && fresh.getCreditsReserved() != null
                ? fresh.getCreditsReserved() : BigDecimal.ZERO;
            return new BudgetState(totalBudget, freshConsumed, freshReserved, false);
        }

        BigDecimal consumed = agent.getCreditsConsumed() != null
            ? agent.getCreditsConsumed() : BigDecimal.ZERO;
        BigDecimal reserved = agent.getCreditsReserved() != null
            ? agent.getCreditsReserved() : BigDecimal.ZERO;
        return new BudgetState(totalBudget, consumed, reserved, false);
    }

    /** True when {@code lastReset} is null OR more than 7 days before {@code now}. */
    static boolean isMoreThan7DaysAgo(Instant lastReset, Instant now) {
        if (lastReset == null) return true;
        return ChronoUnit.DAYS.between(lastReset, now) >= 7;
    }

    /** True when {@code lastReset} is null OR falls in a calendar month strictly before {@code now}. */
    static boolean isInPreviousCalendarMonth(Instant lastReset, Instant now) {
        if (lastReset == null) return true;
        YearMonth lastMonth = YearMonth.from(LocalDate.ofInstant(lastReset, ZoneOffset.UTC));
        YearMonth nowMonth = YearMonth.from(LocalDate.ofInstant(now, ZoneOffset.UTC));
        return nowMonth.isAfter(lastMonth);
    }
}

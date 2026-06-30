package com.apimarketplace.agent.service.budget;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Array;
import java.util.List;
import java.util.UUID;

/**
 * Cascading caller-chain reservation service (§4.3 of AGENT_BUDGET_HIERARCHY.md).
 *
 * <p>When a parent agent spawns a child, credits are reserved atomically on EVERY ancestor in
 * the caller chain. At child termination, the held reservation is refunded and the actual cost
 * is added to every ancestor's {@code credits_consumed}. This guarantees
 * {@code Σ(consumed of all descendants of A) ≤ creditBudget(A)} at every depth without runtime
 * tree walks on the hot path.
 *
 * <p>All queries are native JDBC (not JPA) so they bypass the entity cache and any
 * {@code @DynamicUpdate}/{@code updatable = false} machinery that protects {@code credits_consumed}
 * and {@code credits_reserved} from JPA dirty flushes. See §6.2 writer audit table.
 */
@Service
public class BudgetReservationService {

    private static final Logger log = LoggerFactory.getLogger(BudgetReservationService.class);

    private final JdbcTemplate jdbc;

    public BudgetReservationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Atomically reserve {@code amount} on EVERY ancestor in {@code chain}. Either all succeed
     * (transaction commits), or any failure throws {@link InsufficientBudgetException} and the
     * whole transaction rolls back - no manual compensation needed.
     *
     * <p>No-op on empty chain, null amount, or non-positive amount.
     */
    @Transactional
    public void tryReserveChain(List<UUID> chain, BigDecimal amount) {
        if (chain == null || chain.isEmpty() || amount == null || amount.signum() <= 0) {
            return;
        }
        for (UUID ancestorId : chain) {
            if (!tryReserveOne(ancestorId, amount)) {
                log.warn("[BUDGET-CASCADE] reservation refused ancestor={} amount={} chain={}",
                    ancestorId, amount, chain);
                throw new InsufficientBudgetException(ancestorId, amount);
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("[BUDGET-CASCADE] reservation granted chain={} amount={}", chain, amount);
        }
    }

    /**
     * Single-row atomic reservation. Returns {@code false} on insufficient free, {@code true}
     * otherwise. Single SQL statement - no SELECT-then-UPDATE TOCTOU.
     *
     * <ul>
     *   <li>Limited agent with sufficient free → {@code reserved += amount}, returns true</li>
     *   <li>Limited agent with insufficient free → no update, returns false</li>
     *   <li>Unlimited agent ({@code credit_budget IS NULL}) → reserved unchanged (no-op write
     *       on the same row), returns true - one row matches so the UPDATE count is 1</li>
     * </ul>
     */
    boolean tryReserveOne(UUID agentId, BigDecimal amount) {
        int updated = jdbc.update(
            "UPDATE agent.agents " +
            "   SET credits_reserved = CASE " +
            "           WHEN credit_budget IS NULL THEN credits_reserved " +
            "           ELSE credits_reserved + ? " +
            "       END " +
            " WHERE id = ? " +
            "   AND (credit_budget IS NULL " +
            "        OR (credit_budget - credits_consumed - credits_reserved) >= ?)",
            amount, agentId, amount);
        return updated == 1;
    }

    /**
     * Walk the same chain at termination, refunding the held reservation and crediting the
     * actual cost at every ancestor. Each row update is independent and additive, so there is
     * no contention with the executing row's own writes.
     *
     * <p>{@code GREATEST(credits_reserved - :reserved, 0)} clamps at zero as belt-and-suspenders
     * in case {@link ReservationStartupCleanup} already cleared the reservation between the
     * reserve and settle phases (JVM crash + restart).
     *
     * <p><strong>V71 - observability split:</strong> in addition to bumping {@code credits_consumed},
     * this method now bumps {@code credits_consumed_from_subagents} by the same {@code actual}
     * on every ancestor. Both columns are written in the same single-row UPDATE, which enforces
     * the invariant {@code credits_consumed_from_subagents <= credits_consumed} by construction
     * (same row, same delta, same transaction - no way to drift). The column is surfaced by
     * {@code AgentCrudModule.buildBudgetResponse} as {@code budget.consumed_from_subagents} so
     * callers can split "own LLM spend" from "cascade spend" without tailing logs. See V71
     * migration comment + §4.3/§5.6 of AGENT_BUDGET_HIERARCHY.md for the full rationale.
     *
     * <p>Does not throw on empty chain or missing ancestor row - settle is a terminal cleanup,
     * we never want it to poison a recordFromRequest flow.
     */
    @Transactional
    public void settleReservationChain(List<UUID> chain, BigDecimal reserved, BigDecimal actual) {
        if (chain == null || chain.isEmpty()) {
            return;
        }
        BigDecimal safeReserved = reserved != null ? reserved : BigDecimal.ZERO;
        BigDecimal safeActual = actual != null ? actual : BigDecimal.ZERO;
        for (UUID ancestorId : chain) {
            // Every ancestor in `chain` is, by definition, a (transitive) parent of the
            // execution currently being settled - so from their perspective this spend IS
            // "from a sub-agent". Bumping credits_consumed_from_subagents alongside
            // credits_consumed in the same UPDATE keeps the two invariants tight:
            //   (1) consumed_from_subagents <= consumed (same delta, same row, same tx)
            //   (2) the breakdown is always consistent with what the API reads back,
            //       so frontends/LLMs can split "own LLM spend" from "cascade spend"
            //       without tailing logs - see V71 migration comment for the motivation.
            jdbc.update(
                "UPDATE agent.agents " +
                "   SET credits_reserved = GREATEST(credits_reserved - ?, 0), " +
                "       credits_consumed = credits_consumed + ?, " +
                "       credits_consumed_from_subagents = credits_consumed_from_subagents + ? " +
                " WHERE id = ?",
                safeReserved, safeActual, safeActual, ancestorId);
        }
        if (log.isDebugEnabled()) {
            BigDecimal refunded = safeReserved.subtract(safeActual);
            log.debug("[BUDGET-CASCADE] settle chain={} reserved={} actual={} refunded={}",
                chain, safeReserved, safeActual, refunded);
        }
    }

    /**
     * Returns the minimum free budget across every ancestor in the chain, or {@code null} if
     * every ancestor is unlimited ({@code credit_budget IS NULL}). Used to default-size a
     * reservation when the spawn call does not specify one.
     *
     * <p>There IS a TOCTOU window between this read and the subsequent
     * {@link #tryReserveChain(List, BigDecimal)}, but the conditional UPDATE in
     * {@link #tryReserveOne(UUID, BigDecimal)} refuses on race, so the worst case is a spurious
     * {@code BUDGET_EXHAUSTED} - never over-spawning.
     */
    /**
     * Startup cleanup - zero every non-zero {@code credits_reserved} row in one statement.
     * Intended to be called from {@link ReservationStartupCleanup} at
     * {@code ApplicationReadyEvent}: any reservation still held when the process starts cannot
     * belong to a live execution (agent-service is stateless per process) and must be a
     * leaked leftover from a previous JVM crash.
     *
     * <p>Returns the number of rows cleared for logging. Restricted to {@code > 0} rows so the
     * UPDATE scans an index on {@code credits_reserved > 0} instead of the whole agents table.
     */
    @Transactional
    public int clearAllReservations() {
        return jdbc.update(
            "UPDATE agent.agents SET credits_reserved = 0 WHERE credits_reserved > 0");
    }

    @Transactional(readOnly = true)
    public BigDecimal getMinFreeAcrossChain(List<UUID> chain) {
        if (chain == null || chain.isEmpty()) {
            return null;
        }
        return jdbc.query(
            "SELECT MIN(credit_budget - credits_consumed - credits_reserved) " +
            "  FROM agent.agents " +
            " WHERE id = ANY(?) AND credit_budget IS NOT NULL",
            ps -> {
                Array array = ps.getConnection().createArrayOf("uuid", chain.toArray(new UUID[0]));
                ps.setArray(1, array);
            },
            rs -> rs.next() ? rs.getBigDecimal(1) : null);
    }
}

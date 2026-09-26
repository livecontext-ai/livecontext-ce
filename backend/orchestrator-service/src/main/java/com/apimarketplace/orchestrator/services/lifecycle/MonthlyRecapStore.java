package com.apimarketplace.orchestrator.services.lifecycle;

import com.apimarketplace.orchestrator.services.badge.BadgeStatsCollector;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

/**
 * SQL of the monthly recap, orchestrator schema only.
 *
 * <p>A "run" is counted exactly as the trophy page counts a success: a closed epoch that
 * succeeded ({@link BadgeStatsCollector#SUCCESSFUL_EPOCH_CONDITION}), plus a COMPLETED run
 * that never opened an epoch (a one-shot run). A reusable-trigger workflow keeps one run row
 * forever and never reaches COMPLETED, so counting run rows alone would show zero for the
 * people who automate the most.
 *
 * <p>The ledger ({@code orchestrator.lifecycle_monthly_recaps}, V529) is what makes the recap
 * once per person and month: a row is CLAIMED before the send, and the candidate query skips
 * anyone already claimed for that month.
 */
@Component
public class MonthlyRecapStore {

    /** One person's month. */
    public record Recap(String tenantId, long runs, long activeWorkflows, long badges) {
    }

    static final String CANDIDATES_SQL = """
            WITH successes AS (
                SELECT r.tenant_id, r.workflow_id
                  FROM orchestrator.workflow_epochs e
                  JOIN orchestrator.workflow_runs r ON r.run_id_public = e.run_id
                 WHERE e.entry_type = 'EPOCH_HEADER'
                   AND e.started_at >= ? AND e.started_at < ?
                   AND %s
                UNION ALL
                SELECT r.tenant_id, r.workflow_id
                  FROM orchestrator.workflow_runs r
                 WHERE r.status = 'COMPLETED'
                   AND r.ended_at >= ? AND r.ended_at < ?
                   AND NOT EXISTS (SELECT 1 FROM orchestrator.workflow_epochs e
                                    WHERE e.run_id = r.run_id_public AND e.entry_type = 'EPOCH_HEADER')
            )
            SELECT s.tenant_id,
                   COUNT(*) AS runs,
                   COUNT(DISTINCT s.workflow_id) AS active_workflows,
                   (SELECT COUNT(*) FROM orchestrator.user_badges b
                     WHERE b.tenant_id = s.tenant_id AND b.unlocked_at >= ? AND b.unlocked_at < ?) AS badges
              FROM successes s
             WHERE NOT EXISTS (SELECT 1 FROM orchestrator.lifecycle_monthly_recaps m
                                WHERE m.tenant_id = s.tenant_id AND m.recap_month = ?)
             GROUP BY s.tenant_id
             ORDER BY s.tenant_id
            """.formatted(BadgeStatsCollector.SUCCESSFUL_EPOCH_CONDITION);

    private final JdbcTemplate jdbc;

    public MonthlyRecapStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Everyone with at least one successful run in {@code month} (UTC) whose recap for that
     * month is not claimed yet, with their three figures.
     */
    public List<Recap> candidates(YearMonth month) {
        Timestamp from = Timestamp.from(start(month));
        Timestamp to = Timestamp.from(start(month.plusMonths(1)));
        return jdbc.query(CANDIDATES_SQL,
                (rs, i) -> new Recap(rs.getString("tenant_id"), rs.getLong("runs"),
                        rs.getLong("active_workflows"), rs.getLong("badges")),
                from, to, from, to, from, to, month.toString());
    }

    /** True when this call claimed the person's recap for the month; false when it was already claimed. */
    public boolean claim(String tenantId, YearMonth month) {
        return jdbc.update("INSERT INTO orchestrator.lifecycle_monthly_recaps (tenant_id, recap_month) VALUES (?, ?) "
                + "ON CONFLICT (tenant_id, recap_month) DO NOTHING", tenantId, month.toString()) > 0;
    }

    /** Gives a claim back, so a later pass sends that recap after all. */
    public void release(String tenantId, YearMonth month) {
        jdbc.update("DELETE FROM orchestrator.lifecycle_monthly_recaps WHERE tenant_id = ? AND recap_month = ?",
                tenantId, month.toString());
    }

    private static Instant start(YearMonth month) {
        return month.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
    }
}

package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * V151: backfill {@code scope_id} on {@code auth.workflow_run_pricing_pin}, then
 * promote the column to {@code NOT NULL}.
 *
 * <p>Why a Java migration: a single SQL UPDATE on a 50M-row table holds an
 * AccessExclusiveLock for minutes and can deadlock concurrent reads. Flyway's
 * default tx wrapper also forbids embedded {@code COMMIT}s, so a {@code DO $$ LOOP}
 * batched approach inside a SQL migration would error at apply time.
 *
 * <p>The Java path:
 * <ol>
 *   <li>Open a non-tx connection (auto-commit ON) so each batch commits independently.</li>
 *   <li>Discover the max id once (cheap with the PK index).</li>
 *   <li>Iterate {@code id} ranges in 50K-row chunks; per range:
 *       {@code UPDATE … SET scope_id = run_id WHERE id BETWEEN :lo AND :hi AND scope_id IS NULL}.
 *       The predicate is idempotent - re-running the migration after a crash
 *       resumes from where it left off.</li>
 *   <li>After all batches: {@code ALTER COLUMN scope_id SET NOT NULL}. PG validates
 *       existing rows under AccessExclusive; on a fully-backfilled table that's
 *       seconds.</li>
 * </ol>
 *
 * <p><b>On crash:</b> Flyway marks V151 {@code failed}; restart aborts boot. Recovery:
 * <pre>
 *   mvn -pl backend/migration-service flyway:repair
 * </pre>
 * then restart migration-service. The {@code WHERE scope_id IS NULL} predicate
 * makes the resume idempotent - no duplicate work.
 *
 * <p>Documented in {@code OPERATOR_RUNBOOK.md §v148-v151-deploy}.
 */
public class V151__backfill_scope_id extends BaseJavaMigration {

    private static final Logger log = LoggerFactory.getLogger(V151__backfill_scope_id.class);
    private static final int BATCH_SIZE = 50_000;

    @Override
    public void migrate(Context context) throws Exception {
        Connection cx = context.getConnection();
        // Flyway typically wraps the connection in a transaction. We need our own
        // commit cadence per batch - set autoCommit on this connection so each
        // executeUpdate commits independently. Restored at the end via finally.
        boolean priorAutoCommit = cx.getAutoCommit();
        try {
            cx.setAutoCommit(true);

            long maxId;
            try (Statement st = cx.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT COALESCE(MAX(id), 0) FROM auth.workflow_run_pricing_pin")) {
                rs.next();
                maxId = rs.getLong(1);
            }
            log.info("V151 backfill: max id = {}, batch size = {}", maxId, BATCH_SIZE);

            long lo = 0;
            int totalUpdated = 0;
            int batches = 0;
            try (PreparedStatement ps = cx.prepareStatement(
                    "UPDATE auth.workflow_run_pricing_pin "
                  + "SET scope_id = run_id "
                  + "WHERE id > ? AND id <= ? AND scope_id IS NULL")) {
                while (lo < maxId) {
                    long hi = lo + BATCH_SIZE;
                    ps.setLong(1, lo);
                    ps.setLong(2, hi);
                    int rows = ps.executeUpdate();
                    totalUpdated += rows;
                    batches++;
                    if (batches % 20 == 0 || rows > 0) {
                        log.info("V151 backfill: batch {} ({}, {}] → {} rows updated (total {})",
                                batches, lo, hi, rows, totalUpdated);
                    }
                    lo = hi;
                }
            }
            log.info("V151 backfill: {} rows updated across {} batches", totalUpdated, batches);

            // Final: enforce NOT NULL. On a fully-backfilled table the validation scan
            // is fast (post-batch every row has scope_id set). If it fails, a NULL slipped
            // through (concurrent INSERT during migration without scope_id default) - fix
            // and rerun.
            try (Statement st = cx.createStatement()) {
                st.execute("ALTER TABLE auth.workflow_run_pricing_pin "
                         + "ALTER COLUMN scope_id SET NOT NULL");
            }
            log.info("V151 backfill: scope_id NOT NULL constraint enforced");
        } finally {
            cx.setAutoCommit(priorAutoCommit);
        }
    }

    /**
     * V151 manages its own tx boundaries (per-batch commits via auto-commit).
     * Returning {@code false} tells Flyway not to wrap us in its standard
     * single-tx envelope.
     */
    @Override
    public boolean canExecuteInTransaction() {
        return false;
    }
}

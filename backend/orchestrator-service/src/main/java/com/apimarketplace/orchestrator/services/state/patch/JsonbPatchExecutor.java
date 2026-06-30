package com.apimarketplace.orchestrator.services.state.patch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Applies a list of {@link JsonbPatch} operations to the {@code state_snapshot}
 * column of a {@code workflow_runs} row using a single composed
 * {@code jsonb_set(jsonb_set(…))} native UPDATE.
 *
 * <h2>Hibernate cache contract</h2>
 *
 * <p>The caller MUST detach the {@code WorkflowRunEntity} (via
 * {@link EntityManager#detach}) before calling
 * {@link #applyPatches(String, List)}. Reasons:
 *
 * <ol>
 *   <li><b>Auto-flush avoidance.</b> If the entity remains managed and dirty,
 *       Hibernate's auto-flush will write the in-memory {@code state_snapshot}
 *       string back to the DB right before the native UPDATE - clobbering the
 *       intent to use {@code jsonb_set}. Forcing detach + never calling
 *       {@code run.setStateSnapshot()} on the patch path eliminates this race.</li>
 *   <li><b>L1 cache staleness.</b> After the native UPDATE, the in-memory entity
 *       holds the pre-patch JSON. Detach forces any subsequent reader to either
 *       go through the {@code TxScopedSnapshotCache} (we update it explicitly
 *       in the caller) or re-{@code findById} from the DB (post-patch visible
 *       via MVCC same-tx).</li>
 * </ol>
 *
 * <p>This component does NOT detach automatically - the contract lives at
 * {@code StateSnapshotService.saveSnapshotPatched} which has the entity in
 * scope. Putting detach here would force this class to take an entity argument
 * and tightens the surface unnecessarily.
 *
 * <h2>SQL shape</h2>
 *
 * Patches are folded right-to-left into nested {@code jsonb_set} calls:
 *
 * <pre>
 * UPDATE orchestrator.workflow_runs
 * SET state_snapshot = jsonb_set(jsonb_set(state_snapshot, '{a,b}', '"x"'::jsonb), '{c}', '42'::jsonb)
 * WHERE run_id_public = ?
 * </pre>
 *
 * <p>The path text array is built via {@link JsonbPatch#toPostgresArrayLiteral()}
 * (defensively quoted) and bound as a parameter; the value is {@code ?::jsonb}
 * also bound as a parameter - no string concatenation of user data.
 *
 * <p>{@code create_missing} defaults to {@code true} so the cascade tolerates
 * "first write to this path" cases. Builders that touch a path under a
 * non-existent intermediate object key (e.g. an epoch that hasn't been opened)
 * MUST return {@link JsonbPatchBuilder.Result#fallback()} - Postgres'
 * {@code create_missing} only creates the LAST missing key, not intermediate
 * keys.
 */
@Component
public class JsonbPatchExecutor {

    private static final Logger log = LoggerFactory.getLogger(JsonbPatchExecutor.class);

    @PersistenceContext
    private EntityManager entityManager;

    private final ObjectMapper objectMapper;

    public JsonbPatchExecutor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Apply the given patches to the {@code workflow_runs} row identified by
     * {@code runIdPublic}. Returns the number of rows updated (0 means the run
     * was deleted between read and write - caller may want to retry).
     *
     * <p>Must be called inside an existing {@code @Transactional} boundary that
     * already holds a {@code PESSIMISTIC_WRITE} lock on the row (acquired
     * earlier via {@code findByRunIdPublicForUpdate}). This propagation is
     * {@link Propagation#MANDATORY} to make that contract explicit.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int applyPatches(String runIdPublic, List<JsonbPatch> patches) {
        return applyPatches(runIdPublic, patches, -1L);
    }

    /**
     * Variant of {@link #applyPatches(String, List)} that also stamps the
     * {@code state_snapshot_seq} SQL column to {@code newSeq} in the same
     * UPDATE - A2 mirror so the out-of-tx {@code SnapshotService} cache can
     * invalidate by seq alone without parsing the JSONB.
     *
     * <p>Pass {@code newSeq < 0} to skip the seq update (legacy behavior used
     * by {@code JsonbPatchPostgresIT} which does not exercise the seq column).
     * Production callers in {@code StateSnapshotService} always pass the
     * post-increment seq from {@code StateSnapshot.withIncrementedSeq()}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int applyPatches(String runIdPublic, List<JsonbPatch> patches, long newSeq) {
        if (patches == null || patches.isEmpty()) {
            throw new IllegalArgumentException("patches must not be null or empty");
        }
        boolean includeSeq = newSeq >= 0L;
        // Plan v4 §2b - use opKind-aware composer if any patch is DELTA;
        // otherwise fall back to the legacy ASSIGN-only composer (identical SQL,
        // safer for the 70+ existing tests that bind via positional matching).
        boolean anyDelta = patches.stream().anyMatch(p -> p.opKind() == JsonbPatch.OpKind.COMMUTATIVE_DELTA);
        String sql = anyDelta
                ? composeUpdateSql(patches, includeSeq, /*withCasPredicate*/ false)
                : composeUpdateSql(patches.size(), includeSeq);

        var query = entityManager.createNativeQuery(sql);
        query.setParameter("runIdPublic", runIdPublic);
        for (int i = 0; i < patches.size(); i++) {
            query.setParameter("p" + i, patches.get(i).toPostgresArrayLiteral());
            query.setParameter("v" + i, patches.get(i).jsonValue());
        }
        if (includeSeq) {
            query.setParameter("newSeq", newSeq);
        }
        int updated = query.executeUpdate();
        if (updated == 0) {
            log.warn("[JsonbPatch] applyPatches updated 0 rows for runIdPublic={} (deleted between lock and patch?)",
                    runIdPublic);
        }
        return updated;
    }

    /**
     * Plan v4 §1.6 - CAS variant of {@link #applyPatches(String, List, long)}.
     * Adds a {@code AND state_snapshot_seq = :expectedSeq} predicate to the
     * WHERE clause, so two concurrent writers on the same run race via
     * row-level UPDATE serialization in Postgres: whichever wins bumps
     * {@code state_snapshot_seq} (via the parallel SET in this UPDATE), the
     * loser sees rowCount=0 and retries with a fresh read.
     *
     * <p>Returns the number of rows updated. {@code 0} means CAS conflict -
     * the caller is responsible for re-reading {@code (seq, snapshot)} via
     * {@code findSeqAndStateSnapshotByRunIdPublic}, rebuilding the patch
     * against the fresh snapshot, calling {@code entityManager.clear()} to
     * fence Hibernate L1 (plan v4 §14), and retrying. Plan §1.7 spec budget:
     * 3 attempts with backoff {@code [1ms, 5ms, 15ms]} ± 20% jitter, then
     * fallback to {@link #applyPatches} on the legacy pessimistic-lock path.
     *
     * <p>{@code @Transactional(propagation = REQUIRED)} - unlike
     * {@link #applyPatches} which is MANDATORY (caller already holds
     * PESSIMISTIC_WRITE), the CAS path does NOT require a pre-existing tx;
     * it joins or starts one. The CAS predicate itself is the consistency
     * guard, not a row lock.
     *
     * <p>The V181 trigger ({@code state_snapshot_seq must not regress})
     * fires on {@code newSeq < OLD.seq} - under normal CAS flow this is
     * impossible (we read OLD.seq then write OLD.seq+1) but a builder bug
     * could violate it. The caller distinguishes trigger violation
     * (PostgresException SQLSTATE P0001) from CAS conflict (rowCount=0) and
     * increments {@code cas_attempt_count{outcome=trigger_violation}}.
     *
     * @param runIdPublic the run public ID
     * @param patches the list of jsonb_set operations (non-empty)
     * @param expectedSeq the seq value the caller expects on the DB row
     *                    (read via stateless projection - Hibernate L1 must
     *                    NOT have a managed entity for this run, plan §1.8)
     * @param newSeq the seq value to write (typically {@code expectedSeq + 1})
     * @return rowCount: {@code 1} on CAS success, {@code 0} on conflict (retry path)
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public int applyPatchesCas(String runIdPublic, List<JsonbPatch> patches,
                                long expectedSeq, long newSeq) {
        if (patches == null || patches.isEmpty()) {
            throw new IllegalArgumentException("patches must not be null or empty");
        }
        if (newSeq <= expectedSeq) {
            throw new IllegalArgumentException(String.format(
                    "newSeq (%d) must be > expectedSeq (%d) - V181 trigger would reject regression",
                    newSeq, expectedSeq));
        }
        // Plan v4 §2b - use opKind-aware composer for DELTA support; legacy
        // composer for pure-ASSIGN batches keeps SQL byte-identical for 70+
        // existing parity tests.
        boolean anyDelta = patches.stream().anyMatch(p -> p.opKind() == JsonbPatch.OpKind.COMMUTATIVE_DELTA);
        String sql = anyDelta
                ? composeUpdateSql(patches, /*includeSeq*/ true, /*withCasPredicate*/ true)
                : composeCasUpdateSql(patches.size());

        var query = entityManager.createNativeQuery(sql);
        query.setParameter("runIdPublic", runIdPublic);
        query.setParameter("expectedSeq", expectedSeq);
        query.setParameter("newSeq", newSeq);
        for (int i = 0; i < patches.size(); i++) {
            query.setParameter("p" + i, patches.get(i).toPostgresArrayLiteral());
            query.setParameter("v" + i, patches.get(i).jsonValue());
        }
        return query.executeUpdate();  // 0 = CAS conflict (loud at caller level via retry budget)
    }

    /**
     * Plan v4 §1.6 - compose the CAS UPDATE SQL. Like
     * {@link #composeUpdateSql(int, boolean)} with includeSeq=true, but
     * adds the {@code AND state_snapshot_seq = :expectedSeq} WHERE
     * predicate that makes the UPDATE optimistic.
     */
    static String composeCasUpdateSql(int patchCount) {
        if (patchCount <= 0) {
            throw new IllegalArgumentException("patchCount must be > 0");
        }
        StringBuilder expr = new StringBuilder("CAST(state_snapshot AS jsonb)");
        for (int i = 0; i < patchCount; i++) {
            expr = new StringBuilder("jsonb_set(")
                    .append(expr)
                    .append(", CAST(:p").append(i).append(" AS text[])")
                    .append(", CAST(:v").append(i).append(" AS jsonb)")
                    .append(", true)");
        }
        return "UPDATE orchestrator.workflow_runs SET state_snapshot = CAST(" + expr + " AS text), "
                + "state_snapshot_seq = :newSeq "
                + "WHERE run_id_public = :runIdPublic AND state_snapshot_seq = :expectedSeq";
    }

    /**
     * Compose the {@code UPDATE workflow_runs SET state_snapshot = jsonb_set(...)}
     * SQL string for a fixed patch count. Extracted as a package-private static
     * helper so both the production {@link #applyPatches} path AND the Postgres
     * integration test ({@code JsonbPatchPostgresIT}) call the same shape -
     * eliminates silent drift if the SQL evolves.
     *
     * <p>Shape (for n=2 patches):
     * <pre>
     * UPDATE orchestrator.workflow_runs
     * SET state_snapshot = CAST(
     *     jsonb_set(jsonb_set(CAST(state_snapshot AS jsonb),
     *                         CAST(:p0 AS text[]), CAST(:v0 AS jsonb), true),
     *               CAST(:p1 AS text[]), CAST(:v1 AS jsonb), true)
     *   AS text)
     * WHERE run_id_public = :runIdPublic
     * </pre>
     *
     * <p>Why CAST instead of {@code ::}: Hibernate's named-parameter parser
     * misreads {@code :p0::text[]} as the parameter named {@code p0::text}
     * (the second colon is consumed as part of the identifier). {@code CAST(:p0 AS text[])}
     * is unambiguous and produces identical SQL after Postgres planning.
     *
     * <p>Why CAST around {@code state_snapshot}: the column is TEXT (Hibernate
     * maps the Java String to TEXT, JSON content opaque to PG); jsonb_set
     * requires a jsonb first argument. Postgres re-serializes the outer jsonb
     * back to text via the assignment cast on SET.
     */
    static String composeUpdateSql(int patchCount) {
        return composeUpdateSql(patchCount, false);
    }

    /**
     * Plan v4 §2b - opKind-aware SQL composer. For each patch, emits
     * {@code jsonb_set(prev, path, value-expr, true)} where {@code value-expr}
     * depends on the patch's {@link JsonbPatch.OpKind}:
     *
     * <ul>
     *   <li>{@code ASSIGN}: {@code CAST(:vN AS jsonb)} - literal write.</li>
     *   <li>{@code COMMUTATIVE_DELTA}: {@code to_jsonb(COALESCE((CAST(state_snapshot AS jsonb)#>>:pN)::bigint, 0) + CAST(:vN AS bigint))}
     *       - read-modify-write the integer at the path. {@code COALESCE} handles
     *       the "first write" case where the path doesn't yet exist. Note we
     *       reference the OUTER {@code state_snapshot}, not the chained {@code expr},
     *       because at SQL planning time both refer to the row's pre-UPDATE value
     *       (Postgres evaluates all jsonb_set args against the row snapshot).</li>
     * </ul>
     *
     * <p>Callers MUST pre-validate that COMMUTATIVE_DELTA paths point at an
     * existing integer OR rely on the COALESCE(..., 0) default. Same-path
     * collision (DELTA+DELTA) should be MERGED via the coalescer BEFORE
     * reaching this composer - sending 2 same-path DELTA patches here
     * produces independent jsonb_set calls that each see the OLD row value,
     * so the SECOND one overwrites the FIRST. The coalescer's MERGE decision
     * is what prevents that; the composer is naive.
     */
    static String composeUpdateSql(List<JsonbPatch> patches, boolean includeSeq, boolean withCasPredicate) {
        if (patches == null || patches.isEmpty()) {
            throw new IllegalArgumentException("patches must be non-empty");
        }
        StringBuilder expr = new StringBuilder("CAST(state_snapshot AS jsonb)");
        for (int i = 0; i < patches.size(); i++) {
            JsonbPatch.OpKind kind = patches.get(i).opKind();
            String valueExpr;
            if (kind == JsonbPatch.OpKind.COMMUTATIVE_DELTA) {
                valueExpr = "to_jsonb(COALESCE((CAST(state_snapshot AS jsonb)#>>CAST(:p" + i
                        + " AS text[]))::bigint, 0) + CAST(:v" + i + " AS bigint))";
            } else {
                valueExpr = "CAST(:v" + i + " AS jsonb)";
            }
            expr = new StringBuilder("jsonb_set(")
                    .append(expr)
                    .append(", CAST(:p").append(i).append(" AS text[])")
                    .append(", ").append(valueExpr)
                    .append(", true)");
        }
        StringBuilder sql = new StringBuilder("UPDATE orchestrator.workflow_runs SET state_snapshot = CAST(")
                .append(expr).append(" AS text)");
        if (includeSeq) {
            sql.append(", state_snapshot_seq = :newSeq");
        }
        sql.append(" WHERE run_id_public = :runIdPublic");
        if (withCasPredicate) {
            sql.append(" AND state_snapshot_seq = :expectedSeq");
        }
        return sql.toString();
    }

    /**
     * Compose the patch UPDATE SQL with an optional {@code state_snapshot_seq}
     * column stamp (A2 - mirror the JSONB seq into a dedicated SQL column).
     * The seq update is appended to the SET clause so callers can invalidate
     * an out-of-tx cache without re-parsing the JSONB.
     *
     * <p>Shape (patchCount=1, includeSeq=true):
     * <pre>
     * UPDATE orchestrator.workflow_runs
     * SET state_snapshot = CAST(jsonb_set(...) AS text),
     *     state_snapshot_seq = :newSeq
     * WHERE run_id_public = :runIdPublic
     * </pre>
     */
    static String composeUpdateSql(int patchCount, boolean includeSeq) {
        if (patchCount <= 0) {
            throw new IllegalArgumentException("patchCount must be > 0");
        }
        // i indexes patches; we wrap from the inside out, so the FIRST patch
        // applies first and is the innermost. Equivalent: read patches in
        // order, each new patch wraps the previous result.
        StringBuilder expr = new StringBuilder("CAST(state_snapshot AS jsonb)");
        for (int i = 0; i < patchCount; i++) {
            expr = new StringBuilder("jsonb_set(")
                    .append(expr)
                    .append(", CAST(:p").append(i).append(" AS text[])")
                    .append(", CAST(:v").append(i).append(" AS jsonb)")
                    .append(", true)");
        }
        StringBuilder sql = new StringBuilder("UPDATE orchestrator.workflow_runs SET state_snapshot = CAST(")
                .append(expr).append(" AS text)");
        if (includeSeq) {
            sql.append(", state_snapshot_seq = :newSeq");
        }
        sql.append(" WHERE run_id_public = :runIdPublic");
        return sql.toString();
    }

    /**
     * Estimate the wire bytes a patch list will consume on the SQL side.
     * Sum of (path-array-literal length + value JSON length). Useful for the
     * {@code state_snapshot_patch_payload_bytes} metric.
     */
    public long estimatePayloadBytes(List<JsonbPatch> patches) {
        long total = 0L;
        for (JsonbPatch p : patches) {
            total += p.toPostgresArrayLiteral().length();
            total += p.jsonValue().length();
        }
        return total;
    }

    /** Helper for builders - JSON-encode a value via the shared mapper. */
    public String encode(Object value) throws JsonProcessingException {
        return objectMapper.writeValueAsString(value);
    }
}

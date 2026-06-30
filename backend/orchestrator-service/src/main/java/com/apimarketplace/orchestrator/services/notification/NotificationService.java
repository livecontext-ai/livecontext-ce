package com.apimarketplace.orchestrator.services.notification;

import com.apimarketplace.orchestrator.domain.NotificationReadStateEntity;
import com.apimarketplace.orchestrator.repository.NotificationReadStateRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Bell read-side service. Reads from the materialised
 * {@code orchestrator.notifications} table populated by
 * {@link NotificationEmitter}.
 *
 * <h3>Aggregation</h3>
 * Rows are folded per {@code (subject_type, subject_id)} so the bell shows one
 * line per failing workflow regardless of how many runs failed. The map is
 * bounded by {@link #MAX_BUCKETS} to prevent pathological tenants from
 * exploding bucket cardinality.
 *
 * <h3>Live name JOIN</h3>
 * Workflow names are not denormalised on the row - they're looked up
 * post-aggregation against the live workflow table. Deleted workflows render
 * as {@link #DELETED_WORKFLOW_LABEL}.
 *
 * <h3>Unread semantics</h3>
 * Each user has a single timestamp cursor in {@code notification_read_state}.
 * Items whose latest event is after the cursor are "unread". Mark-all-read
 * sets the cursor to {@code now()}.
 */
@Service
public class NotificationService {

    private static final Logger logger = LoggerFactory.getLogger(NotificationService.class);

    /** Raw rows fetched from the source query (post-LIMIT). */
    static final int RAW_FETCH_LIMIT = 50;

    /**
     * Hard cap on aggregated buckets - bounds payload + Java memory.
     * <p>P2a (multi-category): bumped from 20 to 40 to accommodate the
     * composite {@code (subject_id, category)} key. With 2 categories live
     * (RUN_FAILED, APPROVAL_PENDING) a typical user with ≤20 active
     * workflows still fits. P3+ adds CRED_EXPIRED / AGENT_TASK_ASSIGNED
     * / etc.; revisit if cap pressure is observed.
     */
    static final int MAX_BUCKETS = 40;

    /** Batch size for the live workflow-name JOIN (≤ MAX_BUCKETS). */
    static final int LIVE_NAME_BATCH = 40;

    /**
     * User-level notifications are addressed to a user, not to the currently
     * active workspace. They must remain visible while the user is browsing any
     * organization, otherwise CE invitation notifications disappear behind the
     * monolith's default active-organization header.
     */
    static final String USER_LEVEL_CATEGORY_SQL = "('ORG_INVITATION_PENDING')";

    /** Renders for orphaned subject_id rows (workflow deleted post-emit). */
    static final String DELETED_WORKFLOW_LABEL = "[deleted workflow]";

    private final NotificationReadStateRepository readStateRepository;
    private final WorkflowRunRepository workflowRunRepository;

    /**
     * Subject-type → resolver dispatch map, populated from Spring-discovered
     * beans. V1 has only {@link WorkflowSubjectNameResolver}; future P3+
     * categories (APPLICATION, AGENT_TASK, …) plug in by adding a new
     * {@link SubjectNameResolver} bean - no edit to this service required.
     * Without the dispatcher, every non-WORKFLOW row would render as
     * {@link #DELETED_WORKFLOW_LABEL} (caught by 3-Opus architecture audit
     * round 1 as a hard blocker for V2 expansion).
     */
    private final Map<String, SubjectNameResolver> resolversByType;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Once-per-subject-type WARN throttle. A bell row with an unknown
     * {@code subject_type} (e.g. an in-flight migration emitted before the
     * matching resolver bean was deployed) used to log a WARN per fetch -
     * at 60s polling × N replicas × M users that floods. Now we log once
     * per JVM lifetime per orphan type and let ops triage from there.
     */
    private final Set<String> warnedUnknownSubjectTypes = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public NotificationService(NotificationReadStateRepository readStateRepository,
                                WorkflowRunRepository workflowRunRepository,
                                List<SubjectNameResolver> resolvers) {
        this.readStateRepository = readStateRepository;
        this.workflowRunRepository = workflowRunRepository;
        Map<String, SubjectNameResolver> map = new HashMap<>();
        for (SubjectNameResolver r : resolvers) {
            // Throw on collision: two beans claiming the same subject_type
            // would silently shadow each other (Spring bean order is
            // implementation-defined). Caught at boot rather than at first
            // bell-fetch in prod.
            SubjectNameResolver previous = map.put(r.subjectType(), r);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate SubjectNameResolver for subject_type=" + r.subjectType()
                                + ": " + previous.getClass().getName()
                                + " vs " + r.getClass().getName());
            }
        }
        this.resolversByType = Map.copyOf(map);
        logger.info("[notification] Registered {} SubjectNameResolvers: {}",
                resolversByType.size(), resolversByType.keySet());
    }

    /** Default page size for the bell dropdown when the caller doesn't specify. */
    static final int DEFAULT_PAGE_SIZE = 15;

    /**
     * Backwards-compatible single-page fetch in personal scope (no org).
     * Returns up to {@link #MAX_BUCKETS} buckets in one shot, hasMore=false.
     *
     * Single-page fetch with explicit org scope (V220 default page size).
     * Convenience overload for {@code DashboardController.getHomeStatus},
     * which surfaces a fixed number of bell rows alongside the active
     * automations strip.
     */
    @Transactional(readOnly = true)
    public NotificationsResponse getNotifications(String userId, String activeOrgId) {
        return getNotifications(userId, activeOrgId, 0, MAX_BUCKETS);
    }

    /**
     * Paginated fetch with explicit org scope (V220).
     * <p>Scope semantics (matches sibling tables V210/V215/V218):
     * <ul>
     *   <li>{@code activeOrgId != null} → strict org scope:
     *       {@code WHERE organization_id = :orgId}. The bell shows rows
     *       belonging to the active workspace.</li>
     *   <li>{@code activeOrgId == null} (legacy fallback) → empty result.
     *       Post-V261 (2026-05-19) every notification row carries a non-null
     *       {@code organization_id} (personal workspaces use the user's
     *       default personal org), so the gateway-injected
     *       X-Organization-ID is always present and the strict-org branch
     *       handles every workspace. Pre-V261 the personal-strict
     *       (IS NULL + tenant_id) branch covered legacy NULL rows; those
     *       rows have been backfilled by V261.</li>
     * </ul>
     * {@code unreadCount} is computed across ALL buckets so the badge stays
     * correct when paginating. {@code items} is sliced to the requested page.
     *
     * @param page zero-based page index. Negative values are clamped to 0.
     * @param size page size (clamped to [1, {@link #MAX_BUCKETS}]).
     */
    @Transactional(readOnly = true)
    public NotificationsResponse getNotifications(String userId, String activeOrgId, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, MAX_BUCKETS));

        Instant lastSeenAt = readStateRepository.findByUserId(userId)
                .map(NotificationReadStateEntity::getLastSeenAt)
                .orElse(Instant.EPOCH);

        // P7 multi-subject-type: project optional payload fields needed by the
        // bell for non-workflow click-through (CREDENTIAL → integration/credentialId;
        // TRIGGER → triggerKind for icon + tab deep-link).
        // Postgres {@code payload->>} returns NULL when the key is missing - no
        // CASE WHEN needed, and never a numeric cast that could crash the read.
        //
        // V220 scope predicate selection - branch at build time so the partial
        // index (idx_notifications_organization_occurred) is usable in org scope
        // and idx_notifications_tenant_occurred in personal scope (both columns
        // are leading-edge in their respective indexes).
        //
        // Column order (keep aligned with row[N] reads below):
        //   row[0] subject_type    row[4] severity
        //   row[1] subject_id      row[5] occurred_at
        //   row[2] category        row[6] integration   (CREDENTIAL-only, else NULL)
        //   row[3] run_id_public   row[7] credential_id (CREDENTIAL-only, else NULL)
        //                          row[8] trigger_kind  (TRIGGER-only,    else NULL)
        // Post-V261 (2026-05-19): the gateway always injects X-Organization-ID
        // (personal workspaces resolve to the user's default personal org), so
        // the strict-org branch handles every workspace. The legacy
        // personal-scope IS NULL predicate was removed - a null activeOrgId is
        // a degenerate state that yields an empty page rather than executing
        // an unbounded fallback query.
        boolean orgScope = activeOrgId != null;
        if (!orgScope) {
            return new NotificationsResponse(java.util.List.of(), 0, safePage, safeSize, false);
        }
        // Post-V261 (2026-05-19): every notification row carries a non-null
        // organization_id (the emitter stamps the recipient's active org at
        // insert time; for ORG_INVITATION_PENDING cross-workspace
        // delivery the invitee's default personal org is used so the
        // notification appears in their personal sidebar). The legacy
        // {@code organization_id IS NULL} fallback returned zero rows post
        // V260 backfill and was removed.
        String scopePredicate = " WHERE organization_id = :orgId ";

        var query = entityManager.createNativeQuery(
                "SELECT subject_type, subject_id, category, run_id_public, severity, occurred_at," +
                        "       payload->>'integration'  AS integration," +
                        "       payload->>'credentialId' AS credential_id," +
                        "       payload->>'triggerKind'  AS trigger_kind " +
                        "  FROM orchestrator.notifications " +
                        scopePredicate +
                        "   AND occurred_at > now() - INTERVAL '30 days' " +
                        " ORDER BY occurred_at DESC " +
                        " LIMIT :limit"
        );
        query.setParameter("orgId", activeOrgId);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query
                .setParameter("limit", RAW_FETCH_LIMIT)
                .getResultList();

        // P2a: composite bucket key (subject_id, category). Two categories on
        // the same workflow (RUN_FAILED + APPROVAL_PENDING) MUST surface as
        // distinct rows - otherwise the bell shows arbitrary severity and
        // conflated count (round-2 audit blocker).
        Map<BucketKey, AggBucket> byKey = new LinkedHashMap<>();
        for (Object[] row : rows) {
            String subjectType = (String) row[0];
            UUID subjectId = (UUID) row[1];
            String category = (String) row[2];
            String runIdPublic = (String) row[3];
            String severity = (String) row[4];
            // Hibernate 6 returns Instant for timestamptz; older paths returned Timestamp.
            // Defensive coercion accepts both so the unit-test path (Timestamp mocks)
            // and the live JDBC path (Instant) both work.
            Instant occurredAt = toInstant(row[5]);
            // P7: payload->> always returns text. The emitter writes credentialId
            // as a Long (number) - Postgres coerces number → text on ->>, so this
            // surfaces as e.g. "42". The frontend uses it as a string in the URL
            // ?credentialId=42 without parseInt, which works because the credentials
            // page already accepts text in the query param.
            String integration = row.length > 6 ? (String) row[6] : null;
            String credentialId = row.length > 7 ? (String) row[7] : null;
            String triggerKind = row.length > 8 ? (String) row[8] : null;

            BucketKey key = new BucketKey(subjectId, category);

            // Bound bucket cardinality before computeIfAbsent grows it
            // unboundedly on adversarial input.
            if (!byKey.containsKey(key) && byKey.size() >= MAX_BUCKETS) {
                continue;
            }

            // First-encounter capture (= newest row per ORDER BY occurred_at DESC).
            // integration/credentialId/triggerKind are captured ONCE here; later
            // rows in the same bucket are older and their payload would be staler.
            AggBucket b = byKey.computeIfAbsent(key,
                    k -> new AggBucket(subjectId, subjectType, category, severity,
                            runIdPublic, occurredAt, integration, credentialId, triggerKind));
            b.count++;
            if (occurredAt.isBefore(b.firstEventAt)) b.firstEventAt = occurredAt;
            if (occurredAt.isAfter(b.lastEventAt)) {
                b.lastEventAt = occurredAt;
                b.latestRunIdPublic = runIdPublic;
            }
        }

        Map<UUID, String> subjectNames = resolveSubjectNames(byKey.values());
        // V220 follow-up - org-scope reads MUST resolve liveness against the
        // org-scoped finder. In a shared workspace the run was likely created
        // by a teammate (different tenant_id), and a tenant-scoped existence
        // check would silently nullify every deep-link → bell rows render as
        // "[click → unknown run]". Post-V261 every workspace flows through
        // the org-scoped finder; the personal-tenant fallback below remains
        // only as a defensive path for callers that pass null activeOrgId.
        Set<String> liveRunIdPublics = resolveLiveRunIdPublics(userId, activeOrgId, byKey.values());

        // Build full item list first - unreadCount must be computed across
        // ALL buckets, not just the current page (otherwise the bell badge
        // would jump as the user paginates).
        //
        // Stale-row gates (P3 inbox-link fix):
        //  - Subject deleted (workflow no longer in workflow table) →
        //    drop the bucket entirely. Surfacing "[deleted workflow]" rows
        //    that route to a 404 page is worse than silence.
        //  - Run deleted (latestRunIdPublic absent from workflow_runs) →
        //    nullify the run reference. The frontend then routes the click
        //    to /app/workflow/{id} (still meaningful - the workflow exists)
        //    instead of /run/{unknown}.
        List<NotificationItem> allItems = new ArrayList<>(byKey.size());
        int unreadCount = 0;
        for (AggBucket b : byKey.values()) {
            String name = subjectNames.get(b.subjectId);
            if (name == null) {
                // Subject was deleted post-emit. Drop silently - see comment above.
                continue;
            }
            boolean unread = b.lastEventAt.isAfter(lastSeenAt);
            if (unread) unreadCount++;
            String runIdPublic = b.latestRunIdPublic != null && liveRunIdPublics.contains(b.latestRunIdPublic)
                    ? b.latestRunIdPublic
                    : null;
            allItems.add(new NotificationItem(
                    b.subjectId,
                    name,
                    b.subjectType,
                    runIdPublic,
                    b.category,
                    b.severity,
                    b.count,
                    b.firstEventAt,
                    b.lastEventAt,
                    unread,
                    b.integration,
                    b.credentialId,
                    b.triggerKind));
        }

        // In-memory page slice. byKey iteration order matches the
        // ORDER BY occurred_at DESC of the SELECT, so allItems is already
        // sorted latest-first; no extra sort needed.
        int total = allItems.size();
        int from = Math.min(safePage * safeSize, total);
        int to = Math.min(from + safeSize, total);
        List<NotificationItem> pageItems = allItems.subList(from, to);
        boolean hasMore = to < total;

        return new NotificationsResponse(pageItems, unreadCount, safePage, safeSize, hasMore);
    }

    /**
     * Bulk-delete notification rows by {@code (subject_id, category)} bucket,
     * scoped to the caller's active workspace.
     * <p>Scope predicate mirrors {@link #getNotifications(String, String, int, int)}:
     * <ul>
     *   <li>{@code activeOrgId != null} → {@code organization_id = :orgId}.
     *       Post-V261 (2026-05-19) this branch is taken for every workspace
     *       because the gateway always injects X-Organization-ID (personal
     *       workspaces resolve to the user's default personal org).</li>
     *   <li>{@code activeOrgId == null} → no-op (returns 0). Pre-V261 this
     *       branch applied the legacy
     *       {@code organization_id IS NULL AND tenant_id = :tenantId}
     *       predicate; those rows have been backfilled by V261.</li>
     * </ul>
     * The org-mode delete intentionally does NOT cross-check {@code tenant_id};
     * any org-teammate is allowed to clear bell rows they can see (same
     * contract as PR28 conversation sharing). Defence against spoofed bodies
     * is unchanged - the gateway resolves both headers from the JWT.
     *
     * <p>Publishes {@code notification.removed} ONCE if at least one row was
     * deleted so the frontend invalidates and refetches.
     *
     * @return number of rows actually deleted
     */
    @Transactional
    public int deleteBuckets(String userId, String activeOrgId, java.util.List<BucketRef> buckets) {
        if (buckets == null || buckets.isEmpty()) return 0;
        // Filter to non-null entries with both fields set; refuse anything else.
        java.util.List<UUID> subjectIds = new ArrayList<>();
        java.util.List<String> categories = new ArrayList<>();
        for (BucketRef b : buckets) {
            if (b == null || b.subjectId() == null || b.category() == null || b.category().isBlank()) continue;
            subjectIds.add(b.subjectId());
            categories.add(b.category());
        }
        if (subjectIds.isEmpty()) return 0;

        // JPQL doesn't support tuple IN-list; use a flat OR of (subject=?, category=?).
        // Bound to MAX_BUCKETS=40 entries per call so the WHERE clause stays sane.
        // Post-V261 (2026-05-19): the gateway always injects X-Organization-ID
        // (personal workspaces resolve to the user's default personal org), so
        // the strict-org branch handles every workspace. The legacy
        // personal-scope IS NULL predicate was removed.
        boolean orgScope = activeOrgId != null;
        if (!orgScope) {
            return 0;
        }
        StringBuilder where = new StringBuilder();
        int firstBucketParam = 2;
        for (int i = 0; i < subjectIds.size(); i++) {
            if (i > 0) where.append(" OR ");
            where.append("(n.subjectId = ?").append(firstBucketParam + i * 2)
                 .append(" AND n.category = ?").append(firstBucketParam + 1 + i * 2).append(")");
        }
        // Post-V261: org-strict only - legacy IS NULL fallback removed.
        String scopeClause = "n.organizationId = ?1";
        var query = entityManager.createQuery(
                "DELETE FROM NotificationEntity n WHERE " + scopeClause + " AND (" + where + ")");
        query.setParameter(1, activeOrgId);
        for (int i = 0; i < subjectIds.size(); i++) {
            query.setParameter(firstBucketParam + i * 2, subjectIds.get(i));
            query.setParameter(firstBucketParam + 1 + i * 2, categories.get(i));
        }
        int rows = query.executeUpdate();
        return rows;
    }

    /** Reference to a single bucket - used as input for {@link #deleteBuckets}. */
    public record BucketRef(UUID subjectId, String category) {}

    /** Composite bucket key for P2a multi-category aggregation. */
    private record BucketKey(UUID subjectId, String category) {}

    /**
     * Coerce a JDBC timestamp value to {@link Instant}. Hibernate 6 against
     * Postgres {@code timestamptz} columns returns {@link Instant} directly;
     * legacy paths and the unit-test fixtures return {@link Timestamp}.
     * Both shapes are valid; throw on anything unexpected so a future driver
     * change is surfaced loudly instead of silently miscast.
     */
    private static Instant toInstant(Object raw) {
        if (raw instanceof Instant i) return i;
        if (raw instanceof Timestamp ts) return ts.toInstant();
        if (raw instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        throw new IllegalStateException(
                "Unexpected timestamp type from JDBC: " + (raw == null ? "null" : raw.getClass()));
    }

    /**
     * Mark-all-read sets the cursor to {@code now()}. Native UPSERT -
     * idempotent and race-safe in a single statement. The previous
     * UPDATE-then-INSERT pattern threw {@code DataIntegrityViolationException}
     * inside an outer {@code @Transactional}, which Spring elevates to
     * {@code UnexpectedRollbackException} at commit (audit found the
     * try/catch was a false-positive guard).
     */
    @Transactional
    public void markAllAsRead(String userId) {
        Instant now = Instant.now();
        entityManager.createNativeQuery(
                "INSERT INTO orchestrator.notification_read_state " +
                        "(user_id, last_seen_at, updated_at) " +
                        "VALUES (?, ?, ?) " +
                        "ON CONFLICT (user_id) DO UPDATE SET " +
                        "  last_seen_at = EXCLUDED.last_seen_at, " +
                        "  updated_at   = EXCLUDED.updated_at"
        )
                .setParameter(1, userId)
                .setParameter(2, now)
                .setParameter(3, now)
                .executeUpdate();
    }

    /**
     * Batch-validate that the runs referenced by the aggregated buckets still
     * exist. Returns the subset of {@code latestRunIdPublic} values present in
     * {@code workflow_runs} for the calling workspace. Any caller that
     * intersects against this set gets a "is this run still around?" gate
     * without N+1 queries.
     *
     * <p>Scope predicate mirrors the SELECT in
     * {@link #getNotifications(String, String, int, int)}:
     * <ul>
     *   <li>{@code activeOrgId != null} → org-scope (run.organizationId = orgId).
     *       Required because the run was likely created by a teammate, and a
     *       tenant predicate would spuriously nullify the deep-link.</li>
     *   <li>{@code activeOrgId == null} → tenant scope (run.tenantId = userId).
     *       Personal-scope rows.</li>
     * </ul>
     *
     * <p>Empty input → empty output (no DB hit). Bounded by {@link #MAX_BUCKETS}
     * (40) since one entry per bucket at most.
     */
    private Set<String> resolveLiveRunIdPublics(String tenantId,
                                                 String activeOrgId,
                                                 java.util.Collection<AggBucket> buckets) {
        Set<String> referenced = buckets.stream()
                .map(b -> b.latestRunIdPublic)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        if (referenced.isEmpty()) return Set.of();
        List<String> live = (activeOrgId != null)
                ? workflowRunRepository.findExistingRunIdPublicsByOrganizationId(activeOrgId, referenced)
                : workflowRunRepository.findExistingRunIdPublics(tenantId, referenced);
        return new HashSet<>(live);
    }

    /**
     * Group buckets by {@code subject_type}, dispatch to the matching
     * {@link SubjectNameResolver}, merge results. Each resolver runs at
     * most once per fetch with up to {@link #MAX_BUCKETS} ids - bounded
     * cost regardless of subject_type fan-out.
     *
     * <p>Subjects with no registered resolver (e.g. an in-flight migration
     * landed a row before the resolver bean was deployed) silently drop out
     * of the name map. The caller now skips the bucket entirely (Issue 3
     * fix) rather than rendering a clickable placeholder that 404s; a WARN
     * log surfaces the misconfiguration without breaking the bell.
     */
    private Map<UUID, String> resolveSubjectNames(java.util.Collection<AggBucket> buckets) {
        if (buckets.isEmpty()) return Map.of();
        Map<String, Set<UUID>> idsByType = new HashMap<>();
        for (AggBucket b : buckets) {
            idsByType.computeIfAbsent(b.subjectType, k -> new HashSet<>()).add(b.subjectId);
        }
        Map<UUID, String> names = new HashMap<>();
        for (Map.Entry<String, Set<UUID>> e : idsByType.entrySet()) {
            SubjectNameResolver resolver = resolversByType.get(e.getKey());
            if (resolver == null) {
                if (warnedUnknownSubjectTypes.add(e.getKey())) {
                    logger.warn("[notification] No SubjectNameResolver bean for subject_type={} (first occurrence; subsequent rows of this type render as placeholder without further warnings)",
                            e.getKey());
                }
                continue;
            }
            names.putAll(resolver.resolveNames(e.getValue()));
        }
        return names;
    }

    private static final class AggBucket {
        final UUID subjectId;
        final String subjectType;
        final String category;
        final String severity;
        // Payload fields captured at first-hit (newest, ORDER BY occurred_at DESC).
        // Null when not applicable to the subject_type - Postgres payload->>
        // returns NULL when the key is missing, and the SELECT projects all
        // optional fields unconditionally. Per-field nullability is documented
        // on NotificationItem (CREDENTIAL-only: integration, credentialId;
        // TRIGGER-only: triggerKind).
        final String integration;
        final String credentialId;
        final String triggerKind;
        String latestRunIdPublic;
        int count = 0;
        Instant firstEventAt;
        Instant lastEventAt;

        AggBucket(UUID subjectId, String subjectType, String category, String severity,
                   String runIdPublic, Instant occurredAt,
                   String integration, String credentialId, String triggerKind) {
            this.subjectId = subjectId;
            this.subjectType = subjectType;
            this.category = category;
            this.severity = severity;
            this.integration = integration;
            this.credentialId = credentialId;
            this.triggerKind = triggerKind;
            this.latestRunIdPublic = runIdPublic;
            this.firstEventAt = occurredAt;
            this.lastEventAt = occurredAt;
        }
    }
}

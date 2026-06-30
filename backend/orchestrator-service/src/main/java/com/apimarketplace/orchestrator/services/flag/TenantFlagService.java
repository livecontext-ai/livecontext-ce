package com.apimarketplace.orchestrator.services.flag;

import com.apimarketplace.orchestrator.domain.TenantFlagEntity;
import com.apimarketplace.orchestrator.repository.TenantFlagRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-tenant kernel-runtime feature flag service (P2.3.3 deliverable).
 *
 * <p>Two-tier architecture:
 * <ul>
 *   <li><strong>In-memory cache</strong> (this class): {@code ConcurrentHashMap<flagName,
 *       ConcurrentHashMap<tenantId, Boolean>>}. Hot read path. O(1) lookup with
 *       no allocation. Satisfies the audit B C5 cost contract: 1000 lookups in
 *       under 1ms total.</li>
 *   <li><strong>DB</strong> ({@code orchestrator.tenant_flags}, V175): durable
 *       backing only. Read on startup to warm the cache; written on operator
 *       flips inside the same transaction as the {@code flag_flip_audit} row.</li>
 * </ul>
 *
 * <p><strong>Default-OFF semantics</strong>: a missing entry returns false. No
 * exceptions thrown for unknown (flag, tenant) pairs - the absence is meaningful.
 *
 * <p><strong>Flip contract</strong>: every {@link #flip(String, String, boolean,
 * String, String)} writes to the DB AND the audit log AND the cache, in a single
 * transaction. If any leg fails, all roll back (per {@link FlagFlipAuditWriter}'s
 * {@code @Transactional(MANDATORY)} contract). The cache update happens last so
 * it only commits to the in-memory state if the DB+audit succeeded.
 *
 * <p><strong>Thread safety</strong>: ConcurrentHashMap provides atomic per-key
 * read/write; the flip method is the only writer. Cache loads on startup are
 * single-threaded (Spring lifecycle).
 */
@Service
public class TenantFlagService {

    private static final Logger logger = LoggerFactory.getLogger(TenantFlagService.class);

    private final TenantFlagRepository repository;
    private final FlagFlipAuditWriter auditWriter;

    /**
     * Outer key: flag_name. Inner key: tenant_id. Value: boolean. ConcurrentHashMap
     * at both levels for safe concurrent flip + read.
     */
    private final Map<String, Map<String, Boolean>> cache = new ConcurrentHashMap<>();

    public TenantFlagService(TenantFlagRepository repository, FlagFlipAuditWriter auditWriter) {
        this.repository = repository;
        this.auditWriter = auditWriter;
    }

    /**
     * Warm the in-memory cache from DB on application startup. Called once;
     * subsequent updates flow through {@link #flip(String, String, boolean, String, String)}
     * which keeps the cache consistent.
     *
     * <p>Failure mode: if the DB read fails at startup (e.g. db-host unavailable),
     * the cache stays empty and all flag lookups return false (default-OFF). The
     * service starts cleanly; operator can re-trigger via a manual cache reload
     * once the DB is reachable. This is more lenient than fail-CLOSED because
     * the cache is durable backing for an opt-in feature flag - no flag means
     * "use existing pre-flag behavior".
     */
    @PostConstruct
    void loadCache() {
        try {
            int loaded = 0;
            for (TenantFlagEntity row : repository.findAll()) {
                cache.computeIfAbsent(row.getFlagName(), k -> new ConcurrentHashMap<>())
                        .put(row.getTenantId(), row.getValue());
                loaded++;
            }
            logger.info("[TenantFlag] Loaded {} flag entries from DB into in-memory cache", loaded);
        } catch (Exception e) {
            logger.warn("[TenantFlag] Failed to warm cache from DB at startup - defaulting all flags to OFF: {}",
                    e.getMessage());
        }
    }

    /**
     * Hot read path. Returns the flag value for (flagName, tenantId), defaulting
     * to false when no entry exists. O(1), no I/O.
     *
     * @param flagName the flag identifier (e.g. {@code state-snapshot.elide-running-nodes})
     * @param tenantId the tenant scope (must be non-null and non-empty)
     * @return {@code true} when the flag is ON for this tenant, {@code false} otherwise
     */
    public boolean isEnabled(String flagName, String tenantId) {
        if (flagName == null || tenantId == null || tenantId.isEmpty()) {
            return false;
        }
        Map<String, Boolean> tenantMap = cache.get(flagName);
        if (tenantMap == null) {
            return false;
        }
        Boolean v = tenantMap.get(tenantId);
        return v != null && v;
    }

    /**
     * Three-state lookup. Returns the explicit per-tenant value when one was
     * persisted (true or false), or {@link Optional#empty()} when no row
     * exists for {@code (flagName, tenantId)}.
     *
     * <p>Used by callers that need to distinguish "explicitly OFF" from
     * "never set" - e.g. {@link com.apimarketplace.orchestrator.services.state.elide.TenantFlagBackedElideResolver}
     * applies a flag-specific default-ON when the row is absent. The generic
     * {@link #isEnabled(String, String)} collapses both cases to false and
     * is unsuitable for flags whose default differs from OFF.
     *
     * <p>O(1), no I/O - same hot-path contract as {@link #isEnabled}.
     *
     * @param flagName the flag identifier
     * @param tenantId the tenant scope
     * @return the explicit value, or empty when no row exists
     */
    public Optional<Boolean> getValue(String flagName, String tenantId) {
        if (flagName == null || tenantId == null || tenantId.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Boolean> tenantMap = cache.get(flagName);
        if (tenantMap == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(tenantMap.get(tenantId));
    }

    /**
     * Flip a flag for a tenant - writes DB row + audit log + cache update, all
     * in the same {@code @Transactional} boundary. The audit row uses
     * {@link FlagFlipAuditWriter#recordFlip} which is {@code @Transactional(MANDATORY)},
     * so the surrounding TX from this method propagates automatically.
     *
     * <p>If the DB write or audit write throws, the entire flip rolls back
     * (per {@link org.springframework.transaction.annotation.Transactional} default).
     * The cache update happens AFTER the DB commit semantically - but we update
     * it inline before commit because Spring's TX is REQUIRES_NEW by default and
     * the rollback handler won't unwind the cache. Acceptable risk: if a rare
     * commit-failure-after-method-return occurs, the cache is briefly stale (one
     * flag flip diverged from DB) until the next loadCache (process restart).
     * Future hardening: register a TransactionSynchronization to apply cache on
     * commit, revert on rollback.
     *
     * @param flagName       the flag identifier
     * @param tenantId       the tenant scope
     * @param organizationId the organization scope (V265 NOT NULL on flag_flip_audit
     *                       - must be non-null/non-blank for any caller writing
     *                       a flip row)
     * @param newValue       desired flag value
     * @param actor          who initiated the flip (user/service identifier)
     * @param reason         human-readable reason (required for forensic value)
     */
    @Transactional
    public void flip(String flagName, String tenantId, String organizationId,
                      boolean newValue, String actor, String reason) {
        if (flagName == null || flagName.isEmpty()) {
            throw new IllegalArgumentException("flagName is required");
        }
        if (tenantId == null || tenantId.isEmpty()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (organizationId == null || organizationId.isBlank()) {
            throw new IllegalArgumentException("organizationId is required (V265 NOT NULL)");
        }

        // Read previous value for audit-row "old_value" - null-safe.
        Boolean previous = cache.getOrDefault(flagName, Map.of()).get(tenantId);
        String oldValueStr = previous == null ? null : previous.toString();

        // 1. Persist the DB row (same TX). Composite-PK upsert via save() -
        //    JPA will issue UPDATE if (flagName, tenantId) row exists, INSERT
        //    otherwise.
        TenantFlagEntity flagRow = new TenantFlagEntity(
                flagName, tenantId, newValue, Instant.now(), actor);
        flagRow.setOrganizationId(organizationId);
        repository.save(flagRow);

        // 2. Audit row - same TX, fail-the-flip-if-write-fails (FlagFlipAuditWriter
        //    contract per P2.1.7). If this throws, the DB row above also rolls back.
        auditWriter.recordFlip(flagName, tenantId, organizationId, oldValueStr,
                Boolean.toString(newValue), actor, reason);

        // 3. Cache update - last, so a write-side failure above keeps the cache
        //    in sync with what's durable.
        cache.computeIfAbsent(flagName, k -> new ConcurrentHashMap<>())
                .put(tenantId, newValue);

        logger.info("[TenantFlag] Flipped flag={}, tenant={}, old={}, new={}, actor={}",
                flagName, tenantId, oldValueStr, newValue, actor);
    }
}

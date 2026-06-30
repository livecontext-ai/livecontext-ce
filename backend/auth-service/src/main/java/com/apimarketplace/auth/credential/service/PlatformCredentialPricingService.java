package com.apimarketplace.auth.credential.service;

import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.AuthType;
import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.PlatformCredential;
import com.apimarketplace.auth.credential.domain.PlatformCredentialPricingVersion;
import com.apimarketplace.auth.credential.domain.PricingVersionEntry;
import com.apimarketplace.auth.credential.domain.WorkflowRunPricingPin;
import com.apimarketplace.auth.credential.repository.PlatformCredentialPricingVersionRepository;
import com.apimarketplace.auth.credential.repository.PlatformCredentialRepository;
import com.apimarketplace.auth.credential.repository.PricingVersionEntryRepository;
import com.apimarketplace.auth.credential.repository.WorkflowRunPricingPinRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Writer for immutable pricing-version snapshots attached to a platform credential.
 *
 * <p>The key invariant is that {@code version} is monotonically increasing with
 * no gaps per credential. Without coordination, two admins publishing at the
 * same time could each read {@code max=3} and both insert {@code version=4},
 * which violates the {@code (platform_credential_id, version)} unique constraint
 * and leaks into billing: a stale in-flight run might bind to the losing v4,
 * while a new run binds to the winning v4 with a different rate. We serialize
 * on a PostgreSQL transaction-level advisory lock keyed by credential id so the
 * read-max/insert-next sequence is atomic.
 *
 * <p>Never update an existing row; publish always produces a new version.
 */
@Service
public class PlatformCredentialPricingService {

    private static final Logger log = LoggerFactory.getLogger(PlatformCredentialPricingService.class);

    // Stable namespace for the advisory-lock key so it doesn't collide with
    // other advisory locks elsewhere in auth-service.
    private static final long ADVISORY_LOCK_NAMESPACE = 0x70634D6172L; // "pcMar"

    private final JdbcTemplate jdbc;
    private final PlatformCredentialPricingVersionRepository versionRepo;
    private final PricingVersionEntryRepository entryRepo;
    private final PlatformCredentialRepository credentialRepo;
    private final WorkflowRunPricingPinRepository pinRepo;
    private final MarkupPolicy policy;

    public PlatformCredentialPricingService(JdbcTemplate jdbc,
                                             PlatformCredentialPricingVersionRepository versionRepo,
                                             PricingVersionEntryRepository entryRepo,
                                             PlatformCredentialRepository credentialRepo,
                                             WorkflowRunPricingPinRepository pinRepo,
                                             MarkupPolicy policy) {
        this.jdbc = jdbc;
        this.versionRepo = versionRepo;
        this.entryRepo = entryRepo;
        this.credentialRepo = credentialRepo;
        this.pinRepo = pinRepo;
        this.policy = policy;
    }

    /**
     * Publish a new pricing version for the given credential.
     *
     * <p>Runs under an advisory lock so the read-max / insert-next sequence is
     * atomic against concurrent publishes. The lock is released when the
     * transaction commits.
     *
     * @throws IllegalArgumentException if the credential does not exist or the
     *                                  markup config violates policy.
     */
    @Transactional
    public PlatformCredentialPricingVersion publishNextVersion(Long credentialId,
                                                                BigDecimal defaultMarkup,
                                                                Map<UUID, BigDecimal> perToolOverrides,
                                                                String createdBy) {
        PlatformCredential credential = credentialRepo.findById(credentialId)
                .orElseThrow(() -> new IllegalArgumentException("credential not found: " + credentialId));

        // Policy enforcement at the write boundary - DB CHECK is the backstop.
        policy.validateMarkupConfig(credential.authType(), defaultMarkup,
                credential.maxCallsPerRun() != null ? credential.maxCallsPerRun() : 0);
        if (perToolOverrides != null) {
            for (var e : perToolOverrides.entrySet()) {
                if (e.getValue() == null || e.getValue().signum() < 0) {
                    throw new IllegalArgumentException(
                            "per-tool markup must be >= 0 (toolId=" + e.getKey() + ")");
                }
            }
        }
        // A version with no default AND no overrides would bill zero for every
        // tool, which is indistinguishable from "not priced". Refuse it so the
        // version history stays meaningful and the inspector toggle stays honest.
        if (defaultMarkup == null
                && (perToolOverrides == null || perToolOverrides.isEmpty())) {
            throw new IllegalArgumentException(
                    "pricing version needs either a default markup or at least one per-tool override");
        }

        acquireAdvisoryLock(credentialId);

        Integer max = versionRepo.findMaxVersion(credentialId);
        int next = (max == null) ? 1 : max + 1;

        PlatformCredentialPricingVersion version = new PlatformCredentialPricingVersion();
        version.setPlatformCredentialId(credentialId);
        version.setVersion(next);
        version.setDefaultMarkupCredits(defaultMarkup);
        version.setCreatedBy(createdBy);
        PlatformCredentialPricingVersion saved = versionRepo.save(version);

        if (perToolOverrides != null && !perToolOverrides.isEmpty()) {
            List<PricingVersionEntry> entries = new ArrayList<>(perToolOverrides.size());
            for (var e : perToolOverrides.entrySet()) {
                PricingVersionEntry entry = new PricingVersionEntry();
                entry.setPricingVersionId(saved.getId());
                entry.setApiToolId(e.getKey());
                entry.setMarkupCredits(e.getValue());
                entries.add(entry);
            }
            entryRepo.saveAll(entries);
        }

        log.info("Published pricing version v{} for credential {} (default={}, overrides={})",
                next, credentialId, defaultMarkup,
                perToolOverrides == null ? 0 : perToolOverrides.size());
        return saved;
    }

    public Optional<PlatformCredentialPricingVersion> findLatest(Long credentialId) {
        return versionRepo.findLatest(credentialId);
    }

    /**
     * V148+ idempotent bootstrap: if the credential has NO pricing version yet,
     * publish v1 with the supplied {@code defaultMarkup} + {@code perToolOverrides}.
     * If a version already exists (any version), no-op and return the latest.
     *
     * <p>Called by catalog-service's {@code ApiMigrationImporter} after the
     * api_tools seed is complete, so the markup overrides reference UUIDs that
     * exist in {@code catalog.api_tools}. Migration-service can't do this
     * because Flyway runs before catalog seed.
     *
     * <p>Cutover safety net: without this, replacing the V141 hardcoded image-gen
     * pricing with admin-published versions would leave a window where new
     * users have no pricing → free image-gen until admin manually publishes.
     * The bootstrap mirrors V141 numbers (gpt-image-1.5-low/med/high=9/34/133,
     * mini=5/11/36, gemini-2.5-flash-image=39, gemini-3-pro-image=134) so
     * day-1 economics match pre-cutover.
     */
    @Transactional
    public PlatformCredentialPricingVersion bootstrapV1IfAbsent(Long credentialId,
                                                                 BigDecimal defaultMarkup,
                                                                 Map<UUID, BigDecimal> perToolOverrides,
                                                                 String createdBy) {
        Optional<PlatformCredentialPricingVersion> existing = versionRepo.findLatest(credentialId);
        if (existing.isPresent()) {
            log.debug("bootstrapV1IfAbsent: credential {} already has version {} - skipping",
                    credentialId, existing.get().getVersion());
            return existing.get();
        }
        log.info("bootstrapV1IfAbsent: publishing v1 for credential {} (default={}, overrides={})",
                credentialId, defaultMarkup, perToolOverrides == null ? 0 : perToolOverrides.size());
        return publishNextVersion(credentialId, defaultMarkup, perToolOverrides, createdBy);
    }

    public Optional<PlatformCredentialPricingVersion> findByCredentialAndVersion(Long credentialId,
                                                                                  Integer version) {
        return versionRepo.findByPlatformCredentialIdAndVersion(credentialId, version);
    }

    public List<PlatformCredentialPricingVersion> findAllVersions(Long credentialId) {
        return versionRepo.findByPlatformCredentialIdOrderByVersionDesc(credentialId);
    }

    /**
     * Overrides map for a pricing version, keyed by apiToolId. Empty map when
     * the version has no per-tool overrides (i.e. every tool uses the default).
     */
    public Map<UUID, BigDecimal> findOverrides(Long pricingVersionId) {
        List<PricingVersionEntry> entries = entryRepo.findByPricingVersionId(pricingVersionId);
        Map<UUID, BigDecimal> out = new java.util.LinkedHashMap<>();
        for (PricingVersionEntry e : entries) {
            out.put(e.getApiToolId(), e.getMarkupCredits());
        }
        return out;
    }

    /**
     * Non-admin read used by the workflow inspector: for the latest published
     * pricing version of {@code credentialId}, resolve the per-call markup
     * that would apply to {@code apiToolId}.
     *
     * <p>Returns empty when the credential has no published pricing version at
     * all - the inspector reads this as "no platform pricing available" and
     * hides the user/platform source toggle.
     */
    @Transactional(readOnly = true)
    public Optional<BigDecimal> resolveLatestMarkupForTool(Long credentialId, UUID apiToolId) {
        Optional<PlatformCredentialPricingVersion> latest = versionRepo.findLatest(credentialId);
        if (latest.isEmpty()) {
            return Optional.empty();
        }
        Optional<PricingVersionEntry> entry = entryRepo
                .findByPricingVersionIdAndApiToolId(latest.get().getId(), apiToolId);
        return Optional.of(policy.resolveEffectiveMarkup(latest.get(), entry));
    }

    /**
     * True when the latest pricing version of {@code credentialId} has any
     * non-zero rate - either a positive default or at least one positive
     * per-tool override. Used by the inspector when no specific tool context
     * is known (e.g. the node has not yet been bound to a tool), so the
     * toggle still reflects "this integration has pricing somewhere".
     */
    @Transactional(readOnly = true)
    public boolean hasAnyNonZeroMarkup(Long credentialId) {
        Optional<PlatformCredentialPricingVersion> latest = versionRepo.findLatest(credentialId);
        if (latest.isEmpty()) {
            return false;
        }
        BigDecimal def = latest.get().getDefaultMarkupCredits();
        if (def != null && def.signum() > 0) {
            return true;
        }
        for (PricingVersionEntry e : entryRepo.findByPricingVersionId(latest.get().getId())) {
            if (e.getMarkupCredits() != null && e.getMarkupCredits().signum() > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code pg_advisory_xact_lock} held for the remainder of the transaction.
     * Key derived from the credential id so unrelated credentials don't contend.
     */
    private void acquireAdvisoryLock(Long credentialId) {
        // Single-bigint form: Postgres only exposes pg_advisory_xact_lock(bigint)
        // and pg_advisory_xact_lock(int, int). The namespace constant exceeds int32
        // range, so we fold it together with credentialId into one bigint key.
        long key = ADVISORY_LOCK_NAMESPACE ^ Long.rotateLeft(credentialId, 17);
        jdbc.execute("SELECT pg_advisory_xact_lock(" + key + ")");
    }

    /**
     * Convenience check used by callers that want to refuse markup on oauth2 up-front.
     */
    public boolean canCarryMarkup(AuthType authType) {
        return authType != AuthType.OAUTH2;
    }

    /**
     * Admin action: cancel every live (non-cancelled) pin belonging to a user.
     * Each affected run stops billing markup at its next debit. Returns the
     * number of pins transitioned from live → cancelled. Idempotent - a second
     * call returns 0 because the pins are already cancelled.
     */
    @Transactional
    public int cancelActivePinsForUser(Long userId) {
        List<WorkflowRunPricingPin> live = pinRepo.findByUserIdAndCancelledFalse(userId);
        if (live.isEmpty()) {
            return 0;
        }
        Set<String> runIds = new HashSet<>();
        for (WorkflowRunPricingPin pin : live) {
            runIds.add(pin.getRunId());
        }
        Instant now = Instant.now();
        int total = 0;
        for (String runId : runIds) {
            total += pinRepo.cancelByRunId(runId, now);
        }
        log.info("Cancelled {} active markup pins for user {} across {} runs", total, userId, runIds.size());
        return total;
    }

    /**
     * V148+ scope-aware lookup. Returns the live (non-cancelled) pin for the
     * given scope+credential tuple, or empty if none exists. Lookup-only;
     * never creates a pin. Used by the delinquent-bypass branch of
     * {@code CreditService.tryReserveMarkup} to decide whether the caller is
     * a fresh request or an in-flight RUN sub-allocation.
     */
    @Transactional(readOnly = true)
    public Optional<WorkflowRunPricingPin> lookupPin(String scopeKind, String scopeId, Long credentialId) {
        return pinRepo.findByScopeKindAndScopeIdAndPlatformCredentialIdAndCancelledFalse(
                scopeKind, scopeId, credentialId);
    }

    /**
     * V148+ scope-aware existence check. Cheaper than {@link #lookupPin} when
     * the caller only needs the boolean - no entity hydration.
     */
    @Transactional(readOnly = true)
    public boolean existsPin(String scopeKind, String scopeId, Long credentialId) {
        return pinRepo.existsByScopeKindAndScopeIdAndPlatformCredentialIdAndCancelledFalse(
                scopeKind, scopeId, credentialId);
    }

    /**
     * V148+ unified pin create-or-touch. Replaces {@link #savePin} for new
     * code (the legacy method delegates to this with {@code scopeKind="RUN"}).
     *
     * <p>Atomic semantics:
     * <ol>
     *   <li>Look up existing live pin by {@code (scopeKind, scopeId, credentialId)}.</li>
     *   <li>If found: bump {@code last_used_at} and return - refreshes the
     *       STREAM-pin TTL even if no new ledger row is written.</li>
     *   <li>If absent: insert a new pin, bound to {@code pricingVersionId}.
     *       Race-safe via the partial UNIQUE on
     *       {@code (scope_kind, scope_id, platform_credential_id) WHERE cancelled = FALSE}
     *       - a concurrent insert raises {@link DataIntegrityViolationException},
     *       caught here, and the winner is re-read.</li>
     * </ol>
     *
     * <p>"Touch on hit" semantics matter for STREAM pins: a long-running chat
     * session whose tool calls pin lazily on call #1 will then keep the pin
     * fresh on every subsequent call so the 30-day TTL sweep doesn't reap the
     * pin mid-conversation.
     */
    @Transactional
    public WorkflowRunPricingPin pinForScope(String scopeKind, String scopeId,
                                              Long userId, Long credentialId,
                                              Long pricingVersionId) {
        Optional<WorkflowRunPricingPin> existing =
                pinRepo.findByScopeKindAndScopeIdAndPlatformCredentialIdAndCancelledFalse(
                        scopeKind, scopeId, credentialId);
        if (existing.isPresent()) {
            WorkflowRunPricingPin pin = existing.get();
            pin.setLastUsedAt(Instant.now());
            return pinRepo.save(pin);
        }
        WorkflowRunPricingPin pin = new WorkflowRunPricingPin();
        pin.setScopeKind(scopeKind);
        pin.setScopeId(scopeId);
        pin.setRunId(scopeId); // legacy mirror - drop after post-soak migration
        pin.setUserId(userId);
        pin.setPlatformCredentialId(credentialId);
        pin.setPricingVersionId(pricingVersionId);
        pin.setLastUsedAt(Instant.now());
        try {
            return pinRepo.save(pin);
        } catch (DataIntegrityViolationException raced) {
            return pinRepo.findByScopeKindAndScopeIdAndPlatformCredentialIdAndCancelledFalse(
                            scopeKind, scopeId, credentialId)
                    .orElseThrow(() -> raced);
        }
    }

    /**
     * V148+ scope-aware cancellation. Called by:
     * <ul>
     *   <li>Workflow run-terminal chokepoint: {@code cancelPinsForScope("RUN", runId)}.</li>
     *   <li>Stream end-of-conversation hook (if any): {@code cancelPinsForScope("STREAM", streamId)}.</li>
     * </ul>
     * Idempotent - re-running is a no-op once pins are cancelled. Returns the
     * number of rows transitioned from live → cancelled.
     */
    @Transactional
    public int cancelPinsForScope(String scopeKind, String scopeId) {
        if (scopeKind == null || scopeId == null) return 0;
        return pinRepo.cancelByScope(scopeKind, scopeId, Instant.now());
    }

    /**
     * Resolve the effective per-call markup for a (scope, credential, tool)
     * tuple. Creates the pin lazily on first call; on hit, just looks up the
     * pinned version's entry/default rate.
     *
     * <p>Returns empty when the credential has no published pricing version -
     * caller (catalog) interprets that as "fail-closed: refuse the call". The
     * delinquent gate is enforced separately by {@code tryReserveMarkup}.
     */
    @Transactional
    public Optional<ResolvedMarkup> resolveScopeMarkup(String scopeKind, String scopeId,
                                                        Long userId, Long credentialId,
                                                        UUID apiToolId) {
        Optional<PlatformCredentialPricingVersion> latest = versionRepo.findLatest(credentialId);
        if (latest.isEmpty()) {
            return Optional.empty();
        }
        WorkflowRunPricingPin pin = pinForScope(scopeKind, scopeId, userId, credentialId,
                latest.get().getId());
        // Use the pinned version (not necessarily the latest) so a mid-session
        // admin republish doesn't reprice an already-live scope.
        Long pinnedVersionId = pin.getPricingVersionId();
        Optional<PlatformCredentialPricingVersion> pinnedVersion =
                versionRepo.findById(pinnedVersionId);
        if (pinnedVersion.isEmpty()) {
            return Optional.empty();
        }
        Optional<PricingVersionEntry> entry =
                entryRepo.findByPricingVersionIdAndApiToolId(pinnedVersionId, apiToolId);
        BigDecimal effective = policy.resolveEffectiveMarkup(pinnedVersion.get(), entry);
        return Optional.of(new ResolvedMarkup(pin.getId(), pinnedVersionId, effective));
    }

    /** DTO returned by {@link #resolveScopeMarkup}. */
    public record ResolvedMarkup(Long pinId, Long pricingVersionId, BigDecimal effectiveMarkup) {}

    /**
     * Create a pin binding a run to a specific (credential, pricing version) pair.
     * Idempotent under concurrency: if another thread wins the race to insert
     * between our read and our write, the unique constraint {@code uk_wrpp_run_cred}
     * throws {@link DataIntegrityViolationException} - we swallow it and re-read
     * the winning row. Without this second catch, check-then-insert under parallel
     * run-init for the same {@code (runId, credentialId)} would silently drop the
     * losing pin and the run would bill zero markup for that credential.
     */
    @Transactional
    public WorkflowRunPricingPin savePin(String runId, Long userId,
                                          Long credentialId, Long pricingVersionId) {
        Optional<WorkflowRunPricingPin> existing =
                pinRepo.findByRunIdAndPlatformCredentialId(runId, credentialId);
        if (existing.isPresent()) {
            return existing.get();
        }
        WorkflowRunPricingPin pin = new WorkflowRunPricingPin();
        pin.setRunId(runId);
        pin.setUserId(userId);
        pin.setPlatformCredentialId(credentialId);
        pin.setPricingVersionId(pricingVersionId);
        try {
            return pinRepo.save(pin);
        } catch (DataIntegrityViolationException raced) {
            return pinRepo.findByRunIdAndPlatformCredentialId(runId, credentialId)
                    .orElseThrow(() -> raced);
        }
    }

    /**
     * All pins (live and cancelled) attached to a run. Used by the debit path
     * to decide whether a tool call should bill markup and against which rate.
     */
    public List<WorkflowRunPricingPin> findPinsForRun(String runId) {
        return pinRepo.findByRunId(runId);
    }

    /**
     * Terminal-state / cancel hook: cancel every live pin on a run. Called by
     * the orchestrator's run-terminal chokepoint so a failed/cancelled run does
     * not continue billing markup if any stragglers fire. Idempotent.
     */
    @Transactional
    public int cancelPinsForRun(String runId) {
        return pinRepo.cancelByRunId(runId, Instant.now());
    }

    /**
     * Live pin lookup used by the hot-path debit flow. Returns empty when no pin
     * exists for {@code (runId, credentialId)} or the pin has been cancelled -
     * either case means markup does not apply for this tool call.
     */
    public Optional<WorkflowRunPricingPin> findLivePin(String runId, Long credentialId) {
        return pinRepo.findByRunIdAndPlatformCredentialId(runId, credentialId)
                .filter(p -> !p.isCancelled());
    }
}

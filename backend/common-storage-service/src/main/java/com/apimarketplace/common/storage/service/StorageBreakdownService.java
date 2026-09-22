package com.apimarketplace.common.storage.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.apimarketplace.common.storage.domain.OrgStorageBreakdown;
import com.apimarketplace.common.storage.domain.TenantStorageBreakdown;
import com.apimarketplace.common.storage.domain.TenantStorageBreakdownId;
import com.apimarketplace.common.storage.repository.OrgStorageBreakdownRepository;
import com.apimarketplace.common.storage.repository.TenantStorageBreakdownRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

/**
 * Shared service for incremental storage accounting.
 *
 * <p>Legacy tenant-scope calls write only tenant rows. Org-aware overloads write
 * both tenant and organization rollups so personal and workspace views stay
 * independent.</p>
 */
@Service
public class StorageBreakdownService {

    private static final Logger log = LoggerFactory.getLogger(StorageBreakdownService.class);

    /** Single format string so the WARN and the throttled DEBUG stay byte-identical. */
    private static final String UNDERFLOW_MSG =
            "[StorageBreakdown] Negative delta clamped: tenant={} category={} "
                    + "currentBytes={} deltaBytes={} projectedBytes={} "
                    + "currentCount={} deltaCount={} projectedCount={}";

    /** One WARN per tenant+category per hour PER INSTANCE. The cache is in-process and
     *  this class is linked into most services, so an operator watching the whole fleet
     *  can legitimately see several lines an hour for one pair; that is not a broken
     *  throttle. The repeats it drops carry no new information: EXECUTION_DATA drifts to
     *  0 by design (incremental tracking is deliberately skipped for workflow runs), so
     *  an un-throttled warning drowns the log - 3861 lines in 3 days in prod, 3779 of
     *  them the same tenant+category. */
    static final Duration UNDERFLOW_WARN_INTERVAL = Duration.ofHours(1);

    /** Key space is tenants x categories, so it is naturally bounded; the cap only
     *  guards against an unforeseen tenant-id explosion. Caffeine evicts the least
     *  useful ENTRIES at the ceiling, which matters: a plain size check plus clear()
     *  would drop every key at once and hand a WARN back to every drifting tenant,
     *  inverting the throttle exactly when the most tenants are drifting. */
    static final int UNDERFLOW_WARN_KEYS_MAX = 5_000;

    private final TenantStorageBreakdownRepository breakdownRepository;
    private final OrgStorageBreakdownRepository orgBreakdownRepository;

    /** Keys that have already warned inside the current window. Value is a constant;
     *  only membership matters. */
    private final Cache<String, Boolean> underflowWarnedKeys;

    // Two constructors, so the injection point has to be explicit: without it Spring
    // falls back to looking for a no-arg constructor and the context fails to start.
    @Autowired
    public StorageBreakdownService(TenantStorageBreakdownRepository breakdownRepository,
                                   OrgStorageBreakdownRepository orgBreakdownRepository) {
        this(breakdownRepository, orgBreakdownRepository, Ticker.systemTicker());
    }

    /** Visible for tests: lets the throttle window be driven without sleeping. */
    StorageBreakdownService(TenantStorageBreakdownRepository breakdownRepository,
                            OrgStorageBreakdownRepository orgBreakdownRepository,
                            Ticker ticker) {
        this.breakdownRepository = breakdownRepository;
        this.orgBreakdownRepository = orgBreakdownRepository;
        this.underflowWarnedKeys = Caffeine.newBuilder()
                .expireAfterWrite(UNDERFLOW_WARN_INTERVAL)
                .maximumSize(UNDERFLOW_WARN_KEYS_MAX)
                .ticker(ticker)
                .build();
    }

    /**
     * Atomic increment, safe for concurrent calls. Tenant scope only.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void increment(String tenantId, String category, long deltaBytes, int deltaCount) {
        increment(tenantId, category, deltaBytes, deltaCount, null);
    }

    /**
     * Atomic increment, safe for concurrent calls. Org-aware overload.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void increment(String tenantId, String category, long deltaBytes, int deltaCount, String organizationId) {
        if (deltaBytes == 0 && deltaCount == 0) {
            return;
        }

        try {
            if (deltaBytes < 0 || deltaCount < 0) {
                logIfWouldUnderflow(tenantId, category, deltaBytes, deltaCount);
            }
            breakdownRepository.incrementUsage(tenantId, category, deltaBytes, deltaCount);
        } catch (Exception e) {
            log.error("Storage breakdown increment failed; quota drift likely for tenant={} category={}; "
                            + "daily reconciliation will repair: [{}] {}",
                    tenantId, category, e.getClass().getSimpleName(), e.getMessage(), e);
        }

        if (organizationId != null && !organizationId.isBlank()) {
            try {
                orgBreakdownRepository.incrementUsage(organizationId, category, deltaBytes, deltaCount);
            } catch (Exception e) {
                log.warn("Failed to increment org breakdown for org={}, category={}: {}",
                        organizationId, category, e.getMessage());
            }
        }
    }

    /**
     * Pre-read the row only when the delta is negative and log when SQL will clamp at zero.
     */
    private void logIfWouldUnderflow(String tenantId, String category, long deltaBytes, int deltaCount) {
        // The pre-read below exists ONLY to build the diagnostic. Once this pair has
        // warned inside the window the line goes to DEBUG, which no shipped profile
        // enables, so the row would be fetched and the message discarded. On the measured
        // prod case that is 3779 of 3861 database round-trips buying nothing, which would
        // make this a log fix and not a load fix. Skip early unless DEBUG is actually on.
        if (!log.isDebugEnabled()
                && underflowWarnedKeys.getIfPresent(throttleKey(tenantId, category)) != null) {
            return;
        }
        try {
            TenantStorageBreakdownId id = new TenantStorageBreakdownId(tenantId, category);
            breakdownRepository.findById(id).ifPresent(row -> {
                long projectedBytes = row.getUsedBytes() + deltaBytes;
                int projectedCount = row.getItemCount() + deltaCount;
                if (projectedBytes < 0 || projectedCount < 0) {
                    Object[] args = {
                            tenantId, category,
                            row.getUsedBytes(), deltaBytes, projectedBytes,
                            row.getItemCount(), deltaCount, projectedCount};
                    if (shouldWarnForUnderflow(tenantId, category)) {
                        log.warn(UNDERFLOW_MSG, args);
                    } else {
                        log.debug(UNDERFLOW_MSG, args);
                    }
                }
            });
        } catch (Exception e) {
            log.debug("Could not pre-check underflow for tenant={}, category={}: {}",
                    tenantId, category, e.getMessage());
        }
    }

    /**
     * True at most once per {@link #UNDERFLOW_WARN_INTERVAL} for a given
     * tenant+category. Every other clamp on that pair goes to DEBUG, which at the
     * log levels every profile ships means it is dropped, not written somewhere
     * quieter: the intent is one line per drifting pair per hour, full stop.
     *
     * <p>The decision is a single atomic {@code putIfAbsent}. {@code increment} is
     * documented as safe for concurrent calls, and a get-then-put here would let two
     * threads clamping the same pair both see an absent key and both warn.
     */
    private boolean shouldWarnForUnderflow(String tenantId, String category) {
        return underflowWarnedKeys.asMap().putIfAbsent(throttleKey(tenantId, category), Boolean.TRUE) == null;
    }

    /** Separator matters: without it tenant "a" + category "bc" and tenant "ab" +
     *  category "c" would share one entry and silence each other. */
    private static String throttleKey(String tenantId, String category) {
        return tenantId + "::" + category;
    }

    /** Visible for tests: the ceiling actually installed on the throttle cache. */
    long underflowWarnedKeysMaximum() {
        return underflowWarnedKeys.policy().eviction().orElseThrow().getMaximum();
    }

    /** Visible for tests: entries retained after pending eviction work is drained. */
    long underflowWarnedKeysSize() {
        underflowWarnedKeys.cleanUp();
        return underflowWarnedKeys.estimatedSize();
    }

    /**
     * Track a save operation. Tenant scope only.
     */
    public void trackSave(String tenantId, String category, long sizeBytes) {
        trackSave(tenantId, category, sizeBytes, null);
    }

    /**
     * Track a save operation. Org-aware overload.
     */
    public void trackSave(String tenantId, String category, long sizeBytes, String organizationId) {
        increment(tenantId, category, sizeBytes, 1, organizationId);
    }

    /**
     * Track a delete operation. Tenant scope only.
     */
    public void trackDelete(String tenantId, String category, long sizeBytes) {
        trackDelete(tenantId, category, sizeBytes, null);
    }

    /**
     * Track a delete operation. Org-aware overload.
     */
    public void trackDelete(String tenantId, String category, long sizeBytes, String organizationId) {
        increment(tenantId, category, -sizeBytes, -1, organizationId);
    }

    /**
     * Track a size change. Tenant scope only.
     */
    public void trackSizeChange(String tenantId, String category, long deltaBytes) {
        trackSizeChange(tenantId, category, deltaBytes, null);
    }

    /**
     * Track a size change. Org-aware overload.
     */
    public void trackSizeChange(String tenantId, String category, long deltaBytes, String organizationId) {
        increment(tenantId, category, deltaBytes, 0, organizationId);
    }

    /**
     * Total usage across all tenant categories.
     */
    @Transactional(readOnly = true)
    public long getTotalUsage(String tenantId) {
        return breakdownRepository.sumTotalUsage(tenantId);
    }

    /**
     * Per-category breakdown for tenant scope.
     */
    @Transactional(readOnly = true)
    public List<TenantStorageBreakdown> getBreakdown(String tenantId) {
        return breakdownRepository.findByTenantId(tenantId);
    }

    /**
     * Per-category breakdown for organization scope.
     */
    @Transactional(readOnly = true)
    public List<OrgStorageBreakdown> getOrgBreakdown(String organizationId) {
        return orgBreakdownRepository.findByOrganizationId(organizationId);
    }

    /**
     * Absolute set for tenant-scope reconciliation.
     */
    @Transactional
    public void setUsage(String tenantId, String category, long usedBytes, int itemCount) {
        breakdownRepository.setUsage(tenantId, category, usedBytes, itemCount);
    }

    /**
     * Absolute set for organization-scope reconciliation.
     */
    @Transactional
    public void setOrgUsage(String organizationId, String category, long usedBytes, int itemCount) {
        orgBreakdownRepository.setUsage(organizationId, category, usedBytes, itemCount);
    }
}

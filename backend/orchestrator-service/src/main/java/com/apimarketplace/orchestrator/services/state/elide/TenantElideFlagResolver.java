package com.apimarketplace.orchestrator.services.state.elide;

/**
 * Per-tenant resolver for {@code state-snapshot.elide-running-nodes} (P2.3 deliverable).
 *
 * <p>Decouples {@link EpochStateRunningElideSerializer} from the concrete
 * tenant-flag system so the serializer can be unit-tested with a stub and
 * production wiring can plug in the real flag service.
 *
 * <p><strong>Cost contract</strong> (per design rev12 §3.5 / audit B C5):
 * implementations MUST resolve in O(1) at write time - no DB roundtrip, no
 * Redis call. Saves run on the orchestrator hot path (52 sites) and any
 * non-trivial flag-lookup latency multiplies through.
 *
 * <p>Recommended: an in-memory cache (Caffeine, ConcurrentHashMap) refreshed
 * asynchronously from the source-of-truth flag store on a background thread.
 *
 * <p><strong>Failure handling</strong>: implementations may throw on lookup
 * failure but the serializer fails-OPEN (treats throws as "do not elide").
 * Prefer returning false on transient errors so the call site doesn't spin a
 * stack-trace per save during a flag-store outage.
 */
@FunctionalInterface
public interface TenantElideFlagResolver {

    /**
     * @param tenantId the tenant scope (non-null, non-empty by serializer contract)
     * @return {@code true} when {@code state-snapshot.elide-running-nodes} is on
     *         for this tenant, {@code false} otherwise (default OFF)
     */
    boolean isElideEnabled(String tenantId);
}

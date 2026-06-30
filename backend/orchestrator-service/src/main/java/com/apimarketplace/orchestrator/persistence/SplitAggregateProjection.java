package com.apimarketplace.orchestrator.persistence;

/**
 * Lightweight projection for {@code (normalizedKey, epoch, count)} tuples used by
 * {@code AgentRecoveryService.recoverOrphanedAggregatesForRun}. Replaces the prior
 * {@code findByRunId(runId)} load (full entity including JSONB columns) which was the
 * 2026-05-07 OOM shape - 17k rows × heavy JSONB.
 *
 * <p>JPQL constructor expression requires a non-record class to remain Hibernate-friendly
 * across versions; the equality semantics ({@code equals}/{@code hashCode}) are intentionally
 * value-based to keep deduplication in {@code Set}-shaped consumers correct.
 */
public class SplitAggregateProjection {

    private final String normalizedKey;
    private final int epoch;
    private final long itemCount;

    public SplitAggregateProjection(String normalizedKey, Integer epoch, Long itemCount) {
        this.normalizedKey = normalizedKey;
        this.epoch = epoch != null ? epoch : 0;
        this.itemCount = itemCount != null ? itemCount : 0L;
    }

    public String normalizedKey() {
        return normalizedKey;
    }

    public int epoch() {
        return epoch;
    }

    public long itemCount() {
        return itemCount;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SplitAggregateProjection other)) return false;
        return epoch == other.epoch
            && itemCount == other.itemCount
            && java.util.Objects.equals(normalizedKey, other.normalizedKey);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(normalizedKey, epoch, itemCount);
    }

    @Override
    public String toString() {
        return "SplitAggregateProjection{normalizedKey=" + normalizedKey + ", epoch=" + epoch + ", count=" + itemCount + "}";
    }
}

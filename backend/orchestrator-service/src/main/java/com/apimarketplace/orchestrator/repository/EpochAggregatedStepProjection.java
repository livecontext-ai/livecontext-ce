package com.apimarketplace.orchestrator.repository;

/**
 * {@link AggregatedStepProjection} for a range of epochs: the same (alias, status) row, keyed by
 * the epoch it belongs to, plus one representative error message for the group.
 */
public interface EpochAggregatedStepProjection extends AggregatedStepProjection {

    /** The epoch this row aggregates. Never null (legacy NULL-epoch rows are filtered out). */
    Integer getEpoch();

    /** One error message of the group, meaningful on FAILED rows only; null otherwise. */
    String getErrorMessage();
}

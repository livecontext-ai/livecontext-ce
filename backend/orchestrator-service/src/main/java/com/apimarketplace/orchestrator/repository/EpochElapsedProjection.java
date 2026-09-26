package com.apimarketplace.orchestrator.repository;

/**
 * How long one node held one epoch: per-iteration spans summed (see
 * {@code WorkflowStepDataRepository#getElapsedByRunIdAndEpochRange}).
 */
public interface EpochElapsedProjection {

    Integer getEpoch();

    String getStepAlias();

    Long getElapsedMs();
}

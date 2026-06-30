package com.apimarketplace.orchestrator.services.resume.state;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.WorkflowStepDataEntity;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Loads step data from workflow_step_data for output/input display.
 *
 * <p>StatusCounts are now sourced from StateSnapshot (single source of truth).
 * This loader only provides step entities for OUTPUT display in the API response.
 *
 * <p>Uses lightweight queries (excluding input_data JSONB) for performance.
 * Output is loaded separately via StorageService using output_storage_id.
 */
public class StateDataLoader {

    private static final Logger logger = LoggerFactory.getLogger(StateDataLoader.class);

    private final WorkflowStepDataRepository stepDataRepository;

    public StateDataLoader(WorkflowStepDataRepository stepDataRepository) {
        this.stepDataRepository = stepDataRepository;
    }

    /**
     * Load and prepare step data for output display.
     *
     * <p>StepStateBuilder consumes only the last entity per alias, so the loader scopes the
     * fetch accordingly:
     * <ul>
     *   <li>Multi-epoch or rerun: one row per alias across all epochs (latest by id).</li>
     *   <li>First execution (epoch 0): all entities of that single epoch.</li>
     * </ul>
     *
     * <p>The multi-epoch branch must stay bounded - loading every row caused the prod OOM
     * on 2026-05-07 12:40 UTC, where a 37-item split run had 17 180 rows that PgResultSet
     * materialised on ~30 concurrent reconstructState calls (~660 MB of result-set heap).
     */
    public StateReconstructor.StepDataPreparation loadAndPrepareStepData(
            WorkflowRunEntity runEntity,
            int currentEpoch,
            Map<String, Object> metadata) {

        List<WorkflowStepDataEntity> stepEntities;

        if (currentEpoch > 0 || (metadata != null && metadata.containsKey("resetSteps"))) {
            logger.info("[loadAndPrepareStepData] Multi-epoch/rerun (epoch {}): loading latest entity per alias", currentEpoch);
            stepEntities = stepDataRepository.findLatestPerAliasLightweight(runEntity.getId());
        } else {
            // Post-2026-05-22 OOM hardening: epoch=0 was the last unbounded branch.
            // A workflow that fans 1000 split items returned 1000 rows × ~30 listener threads
            // = humongous-region storm. DISTINCT ON (step_alias) caps at ~32 aliases.
            // StepStateBuilder consumes only last entity per alias, so semantics are preserved.
            stepEntities = stepDataRepository.findByWorkflowRunIdAndEpochLatestPerAliasLightweight(
                runEntity.getId(), currentEpoch);
        }

        Map<String, List<WorkflowStepDataEntity>> stepsByAlias = stepEntities.stream()
            .collect(Collectors.groupingBy(WorkflowStepDataEntity::getStepAlias));

        return new StateReconstructor.StepDataPreparation(stepEntities, stepsByAlias);
    }
}

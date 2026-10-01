package com.apimarketplace.orchestrator.integration.repository;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowEntity.WorkflowStatus;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.WorkflowStepDataEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The query behind get_node_output's omitted epoch (workflow and application tools), run against
 * a real database: the MOST RECENT epoch in which THIS node wrote a row, whatever the other
 * nodes, runs and split items did. A wrong answer here is green-when-wrong: the agent gets a
 * real report of the wrong fire.
 */
@DataJpaIntegrationTest
class WorkflowStepDataLatestEpochQueryTest {

    private static final String TENANT = "tenant-latest-epoch";
    private static final String RUN = "run-latest-epoch";
    private static final String OTHER_RUN = "run-latest-epoch-other";

    @Autowired
    private WorkflowStepDataRepository stepDataRepository;

    @Autowired
    private TestEntityManager entityManager;

    private UUID runUuid;

    @BeforeEach
    void setUp() {
        WorkflowEntity wf = new WorkflowEntity(TENANT, "Latest epoch", "u");
        wf.setId(UUID.randomUUID());
        wf.setStatus(WorkflowStatus.ACTIVE);
        wf.setIsActive(true);
        wf.setOrganizationId(TENANT);
        entityManager.persist(wf);
        WorkflowRunEntity run = new WorkflowRunEntity(wf, TENANT, RUN, null, null, "u");
        run.setStatus(RunStatus.WAITING_TRIGGER);
        run.setOrganizationId(TENANT);
        entityManager.persist(run);
        entityManager.flush();
        runUuid = run.getId();
    }

    private void row(String runId, String nodeKey, int epoch, int itemIndex) {
        WorkflowStepDataEntity step = new WorkflowStepDataEntity();
        step.setWorkflowRunId(runUuid);
        step.setRunId(runId);
        step.setStepAlias(nodeKey);
        step.setNormalizedKey(nodeKey);
        step.setToolId("tool");
        step.setStatus("COMPLETED");
        step.setTenantId(TENANT);
        step.setOrganizationId(TENANT);
        step.setStartTime(Instant.now());
        step.setEpoch(epoch);
        step.setSpawn(0);
        step.setIteration(0);
        step.setItemIndex(itemIndex);
        entityManager.persist(step);
    }

    @Test
    @DisplayName("regression: the node's latest epoch is 3 when it ran in epochs 1 and 3 (split items included), not a sibling's or another run's later epoch")
    void latestEpochOfTheNodeOnly() {
        row(RUN, "mcp:fetch", 1, 0);
        row(RUN, "mcp:fetch", 3, 0);
        row(RUN, "mcp:fetch", 3, 1);   // split item 1 of the same fire
        row(RUN, "mcp:fetch", 3, 2);   // split item 2
        row(RUN, "core:later_node", 5, 0);   // another node ran in a later epoch
        row(OTHER_RUN, "mcp:fetch", 9, 0);   // same key, another run
        entityManager.flush();
        entityManager.clear();

        assertThat(stepDataRepository.findLatestEpochByRunIdAndNormalizedKey(RUN, "mcp:fetch")).isEqualTo(3);
        assertThat(stepDataRepository.findLatestEpochByRunIdAndNormalizedKey(RUN, "core:later_node")).isEqualTo(5);
    }

    @Test
    @DisplayName("a node that never wrote a row in this run answers null (the tools turn it into a not-found, never a guess)")
    void neverRanIsNull() {
        row(RUN, "mcp:fetch", 2, 0);
        entityManager.flush();

        assertThat(stepDataRepository.findLatestEpochByRunIdAndNormalizedKey(RUN, "mcp:ghost")).isNull();
        assertThat(stepDataRepository.findLatestEpochByRunIdAndNormalizedKey("no-such-run", "mcp:fetch")).isNull();
    }
}

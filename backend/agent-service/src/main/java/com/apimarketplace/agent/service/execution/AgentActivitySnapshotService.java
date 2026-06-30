package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.domain.AgentExecutionEntity;
import com.apimarketplace.agent.repository.AgentExecutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Re-publishes a snapshot of an agent's currently-running executions to the
 * {@code ws:agent:activity:{agentId}} channel. This is the late-subscribe replay for the
 * agent-activity WS channel: a client that subscribes with {@code requestSnapshot=true}
 * AFTER the agent started (especially a bridge/CLI agent, whose only lifecycle events are
 * execution_started/completed) would otherwise stay idle until completion.
 *
 * <p>Shared by BOTH execution paths so the logic exists once: the cloud gateway path
 * ({@code InternalAgentController#activitySnapshot} → this service) and the CE monolith WS
 * path ({@code MonolithWsActionHandler#triggerSnapshot} → this service). Channel access is
 * authorized upstream (gateway/monolith ChannelAuthorizer) before this runs.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentActivitySnapshotService {

    /**
     * Recency window. Executions left RUNNING longer than this are treated as crashed-pod
     * leftovers (no terminal event was ever written) and excluded, so the snapshot never
     * resurrects a phantom shimmer. Aligns with the frontend store's 10-min stale-clear
     * timer, with headroom so a genuinely long-running agent is never prematurely hidden.
     */
    static final Duration MAX_AGE = Duration.ofMinutes(15);

    private final AgentExecutionRepository agentExecutionRepository;
    private final AgentActivityPublisher agentActivityPublisher;

    /**
     * Re-publish {@code execution_started} for each of the agent's RUNNING executions
     * started within the recency window. Idempotent for clients already tracking the
     * execution (the frontend store preserves their live counters on a same-execution
     * re-announcement). Returns the number of executions re-announced.
     */
    @Transactional(readOnly = true)
    public int publishRunningSnapshot(UUID agentId) {
        if (agentId == null) return 0;
        Instant cutoff = Instant.now().minus(MAX_AGE);
        List<AgentExecutionEntity> running =
                agentExecutionRepository.findRunningByAgentEntityIdSince(agentId, cutoff);
        for (AgentExecutionEntity e : running) {
            agentActivityPublisher.publishExecutionStarted(
                    agentId.toString(),
                    e.getId() != null ? e.getId().toString() : null,
                    e.getModel(),
                    e.getSource(),
                    e.getTaskId() != null ? e.getTaskId().toString() : null);
        }
        log.debug("[FLEET_ACTIVITY] Snapshot for agent {} re-published {} running execution(s)",
                agentId, running.size());
        return running.size();
    }
}

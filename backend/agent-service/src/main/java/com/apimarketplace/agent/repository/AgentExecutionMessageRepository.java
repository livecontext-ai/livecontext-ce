package com.apimarketplace.agent.repository;

import com.apimarketplace.agent.domain.AgentExecutionMessageEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AgentExecutionMessageRepository extends JpaRepository<AgentExecutionMessageEntity, Long> {

    List<AgentExecutionMessageEntity> findByExecutionIdOrderBySequenceNumber(UUID executionId);

    /** Paginated ASC by sequenceNumber - natural chronological order for agent tools. */
    Page<AgentExecutionMessageEntity> findByExecutionIdOrderBySequenceNumber(UUID executionId, Pageable pageable);

    /**
     * Paginated DESC by sequenceNumber - page 0 is the newest batch of messages for an execution.
     * Frontend reverses for chronological display; long agent loops with thousands of messages
     * would otherwise OOM on the un-paginated method.
     */
    Page<AgentExecutionMessageEntity> findByExecutionIdOrderBySequenceNumberDesc(UUID executionId, Pageable pageable);
}

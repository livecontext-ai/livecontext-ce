package com.apimarketplace.agent.repository;

import com.apimarketplace.agent.domain.AgentExecutionToolCallEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AgentExecutionToolCallRepository extends JpaRepository<AgentExecutionToolCallEntity, Long> {

    List<AgentExecutionToolCallEntity> findByExecutionIdOrderBySequenceNumber(UUID executionId);

    /**
     * Paginated DESC by sequenceNumber - page 0 is the newest batch. Tool-call payloads
     * embed full request/response bodies (HTTP responses, file blobs, agent traces) that
     * routinely run into MB-scale per row; the un-paginated list-fetcher OOM'd the JVM
     * on long agent loops. Frontend reverses to ASC for chronological display.
     */
    Page<AgentExecutionToolCallEntity> findByExecutionIdOrderBySequenceNumberDesc(UUID executionId, Pageable pageable);
}

package com.apimarketplace.agent.repository;

import com.apimarketplace.agent.domain.AgentExecutionIterationEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AgentExecutionIterationRepository extends JpaRepository<AgentExecutionIterationEntity, Long> {
    // Batch insert via saveAll()

    List<AgentExecutionIterationEntity> findByExecutionIdOrderByIterationNumber(UUID executionId);

    /** Paginated DESC by iterationNumber - page 0 = newest. Long agent loops can persist thousands of iterations per execution. */
    Page<AgentExecutionIterationEntity> findByExecutionIdOrderByIterationNumberDesc(UUID executionId, Pageable pageable);
}

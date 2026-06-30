package com.apimarketplace.orchestrator.repository;

import com.apimarketplace.orchestrator.domain.WorkflowRunStatusEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WorkflowRunStatusRepository extends JpaRepository<WorkflowRunStatusEntity, UUID> {

    Optional<WorkflowRunStatusEntity> findByRunId(UUID runId);

}

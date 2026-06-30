package com.apimarketplace.agent.repository;

import com.apimarketplace.agent.domain.AgentTaskEventEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AgentTaskEventRepository extends JpaRepository<AgentTaskEventEntity, Long> {

    List<AgentTaskEventEntity> findByTaskIdOrderByCreatedAtAsc(UUID taskId);

    /** Paginated audit trail - DESC so page 0 is the newest batch. */
    Page<AgentTaskEventEntity> findByTaskIdOrderByCreatedAtDesc(UUID taskId, Pageable pageable);

    void deleteByTaskId(UUID taskId);

    // ===== Org-strict variants (post-V281) =====
    // Defense-in-depth - see AgentTaskNoteRepository for rationale.
    // Backed by idx_task_events_org_task_created.

    List<AgentTaskEventEntity> findByTaskIdAndOrganizationIdOrderByCreatedAtAsc(UUID taskId, String organizationId);

    Page<AgentTaskEventEntity> findByTaskIdAndOrganizationIdOrderByCreatedAtDesc(UUID taskId, String organizationId, Pageable pageable);
}

package com.apimarketplace.agent.repository;

import com.apimarketplace.agent.domain.AgentTaskNoteEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AgentTaskNoteRepository extends JpaRepository<AgentTaskNoteEntity, UUID> {

    List<AgentTaskNoteEntity> findByTaskIdOrderByCreatedAtAsc(UUID taskId);

    /** Paginated note browse - DESC so page 0 is the newest batch. */
    Page<AgentTaskNoteEntity> findByTaskIdOrderByCreatedAtDesc(UUID taskId, Pageable pageable);

    void deleteByTaskId(UUID taskId);

    // ===== Org-strict variants (post-V281) =====
    // Defense-in-depth: a caller from another org receives an empty list even if a regression
    // bypasses the parent-task check. Backed by idx_task_notes_org_task_created.

    List<AgentTaskNoteEntity> findByTaskIdAndOrganizationIdOrderByCreatedAtAsc(UUID taskId, String organizationId);

    Page<AgentTaskNoteEntity> findByTaskIdAndOrganizationIdOrderByCreatedAtDesc(UUID taskId, String organizationId, Pageable pageable);
}

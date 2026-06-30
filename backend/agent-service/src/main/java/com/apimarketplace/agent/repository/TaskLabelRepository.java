package com.apimarketplace.agent.repository;

import com.apimarketplace.agent.domain.TaskLabelEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Board-scoped access to the {@link TaskLabelEntity} catalog. Null-org-safe
 * (personal workspace = organization_id IS NULL), mirroring TaskStatusRepository.
 */
public interface TaskLabelRepository extends JpaRepository<TaskLabelEntity, UUID> {

    @Query("SELECT l FROM TaskLabelEntity l WHERE l.tenantId = :tenantId "
            + "AND ((:orgId IS NULL AND l.organizationId IS NULL) OR l.organizationId = :orgId) "
            + "ORDER BY l.name ASC")
    List<TaskLabelEntity> findBoard(@Param("tenantId") String tenantId, @Param("orgId") String orgId);

    @Query("SELECT l FROM TaskLabelEntity l WHERE l.id IN :ids AND l.tenantId = :tenantId "
            + "AND ((:orgId IS NULL AND l.organizationId IS NULL) OR l.organizationId = :orgId)")
    List<TaskLabelEntity> findBoardByIds(@Param("ids") List<UUID> ids,
                                         @Param("tenantId") String tenantId,
                                         @Param("orgId") String orgId);

    @Query("SELECT l FROM TaskLabelEntity l WHERE l.id = :id AND l.tenantId = :tenantId "
            + "AND ((:orgId IS NULL AND l.organizationId IS NULL) OR l.organizationId = :orgId)")
    Optional<TaskLabelEntity> findScoped(@Param("id") UUID id,
                                         @Param("tenantId") String tenantId,
                                         @Param("orgId") String orgId);

    /**
     * Case-insensitive board-scoped lookup by name, mirroring the
     * {@code uq_task_labels_board_name} unique index (tenant, org, lower(name)).
     * Used to reject a duplicate label with a friendly 400 before the DB
     * constraint would otherwise surface as a 500.
     */
    @Query("SELECT l FROM TaskLabelEntity l WHERE l.tenantId = :tenantId "
            + "AND ((:orgId IS NULL AND l.organizationId IS NULL) OR l.organizationId = :orgId) "
            + "AND lower(l.name) = lower(:name)")
    Optional<TaskLabelEntity> findBoardByName(@Param("tenantId") String tenantId,
                                              @Param("orgId") String orgId,
                                              @Param("name") String name);
}

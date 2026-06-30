package com.apimarketplace.agent.repository;

import com.apimarketplace.agent.domain.TaskStatusEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Board-scoped access to {@link TaskStatusEntity} (the configurable columns).
 * <p>
 * A board is identified by {@code (tenantId, organizationId)} where a NULL
 * {@code organizationId} is the personal workspace. Every query is null-safe on
 * the org so the personal scope (org IS NULL) and a workspace scope are never
 * confused (a derived {@code = :orgId} would silently never match NULL rows).
 */
public interface TaskStatusRepository extends JpaRepository<TaskStatusEntity, UUID> {

    @Query("SELECT s FROM TaskStatusEntity s WHERE s.tenantId = :tenantId "
            + "AND ((:orgId IS NULL AND s.organizationId IS NULL) OR s.organizationId = :orgId) "
            + "ORDER BY s.position ASC, s.createdAt ASC")
    List<TaskStatusEntity> findBoard(@Param("tenantId") String tenantId, @Param("orgId") String orgId);

    @Query("SELECT s FROM TaskStatusEntity s WHERE s.tenantId = :tenantId "
            + "AND ((:orgId IS NULL AND s.organizationId IS NULL) OR s.organizationId = :orgId) "
            + "AND s.key = :key")
    Optional<TaskStatusEntity> findBoardKey(@Param("tenantId") String tenantId,
                                            @Param("orgId") String orgId,
                                            @Param("key") String key);

    @Query("SELECT COUNT(s) FROM TaskStatusEntity s WHERE s.tenantId = :tenantId "
            + "AND ((:orgId IS NULL AND s.organizationId IS NULL) OR s.organizationId = :orgId)")
    long countBoard(@Param("tenantId") String tenantId, @Param("orgId") String orgId);

    @Query("SELECT COALESCE(MAX(s.position), -1) FROM TaskStatusEntity s WHERE s.tenantId = :tenantId "
            + "AND ((:orgId IS NULL AND s.organizationId IS NULL) OR s.organizationId = :orgId)")
    int maxPosition(@Param("tenantId") String tenantId, @Param("orgId") String orgId);

    /** Scope-checked single fetch for mutations (rename / reorder / delete). */
    @Query("SELECT s FROM TaskStatusEntity s WHERE s.id = :id AND s.tenantId = :tenantId "
            + "AND ((:orgId IS NULL AND s.organizationId IS NULL) OR s.organizationId = :orgId)")
    Optional<TaskStatusEntity> findScoped(@Param("id") UUID id,
                                          @Param("tenantId") String tenantId,
                                          @Param("orgId") String orgId);
}

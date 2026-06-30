package com.apimarketplace.orchestrator.repository;

import com.apimarketplace.orchestrator.domain.ResourceFavoriteEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ResourceFavoriteRepository
        extends JpaRepository<ResourceFavoriteEntity, ResourceFavoriteEntity.PK> {

    /** Just the favorited resource ids of one type for a (user, workspace), newest first - for painting card stars. */
    @Query("SELECT f.resourceId FROM ResourceFavoriteEntity f "
         + "WHERE f.userId = :userId AND f.organizationId = :orgId AND f.resourceType = :resourceType "
         + "ORDER BY f.createdAt DESC")
    List<String> findResourceIds(@Param("userId") String userId,
                                 @Param("orgId") String orgId,
                                 @Param("resourceType") String resourceType);

    /** Idempotent remove - returns the number of rows deleted (0 when not favorited). */
    @Modifying
    @Query("DELETE FROM ResourceFavoriteEntity f "
         + "WHERE f.userId = :userId AND f.organizationId = :orgId "
         + "AND f.resourceType = :resourceType AND f.resourceId = :resourceId")
    int deleteFavorite(@Param("userId") String userId,
                       @Param("orgId") String orgId,
                       @Param("resourceType") String resourceType,
                       @Param("resourceId") String resourceId);
}

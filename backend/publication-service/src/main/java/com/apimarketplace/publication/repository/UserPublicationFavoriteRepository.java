package com.apimarketplace.publication.repository;

import com.apimarketplace.publication.domain.UserPublicationFavoriteEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface UserPublicationFavoriteRepository
        extends JpaRepository<UserPublicationFavoriteEntity, UserPublicationFavoriteEntity.PK> {

    List<UserPublicationFavoriteEntity>
        findByUserIdAndOrganizationIdOrderByCreatedAtDesc(String userId, String organizationId);

    /** Lightweight projection: just the favorited publication ids for a (user, workspace), newest first. */
    @Query("SELECT f.publicationId FROM UserPublicationFavoriteEntity f "
         + "WHERE f.userId = :userId AND f.organizationId = :orgId ORDER BY f.createdAt DESC")
    List<UUID> findPublicationIds(@Param("userId") String userId, @Param("orgId") String orgId);

    /** Idempotent remove - returns the number of rows deleted (0 when not favorited). */
    @Modifying
    @Query("DELETE FROM UserPublicationFavoriteEntity f "
         + "WHERE f.userId = :userId AND f.organizationId = :orgId AND f.publicationId = :pubId")
    int deleteFavorite(@Param("userId") String userId, @Param("orgId") String orgId,
                       @Param("pubId") UUID pubId);
}

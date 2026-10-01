package com.apimarketplace.publication.repository;

import com.apimarketplace.publication.domain.CreatorFollowEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CreatorFollowRepository extends JpaRepository<CreatorFollowEntity, CreatorFollowEntity.PK> {

    long countByCreatorId(String creatorId);

    /** Everyone to notify when the creator publishes: just the follower ids. */
    @Query("SELECT f.followerId FROM CreatorFollowEntity f WHERE f.creatorId = :creatorId")
    List<String> findFollowerIds(@Param("creatorId") String creatorId);

    /**
     * Idempotent follow in ONE statement: two concurrent follows (a second tab, a retried click)
     * cannot both insert and have the loser fail on the primary key. Returns 1 when inserted.
     */
    @Modifying
    @Query(value = "INSERT INTO publication.creator_follows (follower_id, creator_id, created_at) "
            + "VALUES (:followerId, :creatorId, NOW()) ON CONFLICT (follower_id, creator_id) DO NOTHING",
            nativeQuery = true)
    int insertIfAbsent(@Param("followerId") String followerId, @Param("creatorId") String creatorId);

    /** Idempotent unfollow - returns the number of rows deleted (0 when not following). */
    @Modifying
    @Query("DELETE FROM CreatorFollowEntity f WHERE f.followerId = :followerId AND f.creatorId = :creatorId")
    int deleteFollow(@Param("followerId") String followerId, @Param("creatorId") String creatorId);
}

package com.apimarketplace.orchestrator.repository;

import com.apimarketplace.orchestrator.domain.NotificationReadStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface NotificationReadStateRepository extends JpaRepository<NotificationReadStateEntity, String> {

    Optional<NotificationReadStateEntity> findByUserId(String userId);

    /**
     * Atomic upsert of the user's read cursor - UPDATE if exists, no-op
     * collision otherwise (caller's INSERT path handles fresh users).
     * Sets {@code last_seen_at} to {@code now} and bumps {@code updated_at}.
     */
    @Modifying
    @Query("UPDATE NotificationReadStateEntity n SET n.lastSeenAt = :now, n.updatedAt = :now " +
            "WHERE n.userId = :userId")
    int updateLastSeenAt(@Param("userId") String userId, @Param("now") Instant now);
}

package com.apimarketplace.conversation.repository;

import com.apimarketplace.conversation.entity.DmThread;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DmThreadRepository extends JpaRepository<DmThread, String> {

    /** Dedup lookup - callers MUST pass the normalised pair (lo<=hi). */
    Optional<DmThread> findByParticipantLoAndParticipantHi(String participantLo, String participantHi);

    /**
     * The user's inbox: every thread they participate in, most-recent activity first
     * (falling back to creation time for threads with no message yet). Identity-level -
     * NOT filtered by organization.
     */
    @Query("SELECT t FROM DmThread t WHERE t.participantLo = :userId OR t.participantHi = :userId "
            + "ORDER BY COALESCE(t.lastMessageAt, t.createdAt) DESC")
    List<DmThread> findThreadsForUser(@Param("userId") String userId);
}

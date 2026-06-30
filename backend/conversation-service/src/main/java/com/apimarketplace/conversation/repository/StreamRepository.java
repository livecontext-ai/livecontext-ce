package com.apimarketplace.conversation.repository;

import com.apimarketplace.conversation.entity.Stream;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface StreamRepository extends JpaRepository<Stream, String> {
    
    /**
     * Find active stream for a conversation
     */
    @Query("SELECT s FROM Stream s WHERE s.conversationId = :conversationId AND s.status = 'ACTIVE'")
    Optional<Stream> findActiveStreamByConversationId(@Param("conversationId") String conversationId);
    
    /**
     * Find stream by streamId
     */
    Optional<Stream> findByStreamId(String streamId);
    
    /**
     * Stop all active streams for a conversation
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Stream s SET s.status = 'STOPPED', s.completedAt = :completedAt WHERE s.conversationId = :conversationId AND s.status = 'ACTIVE'")
    int stopAllActiveStreamsForConversation(@Param("conversationId") String conversationId, @Param("completedAt") LocalDateTime completedAt);

    /**
     * Find active streams older than specified date (for TTL timeout)
     */
    @Query("SELECT s FROM Stream s WHERE s.status = 'ACTIVE' AND s.updatedAt < :threshold")
    List<Stream> findActiveStreamsOlderThan(@Param("threshold") LocalDateTime threshold);
}


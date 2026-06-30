package com.apimarketplace.conversation.repository;

import com.apimarketplace.conversation.entity.DmMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface DmMessageRepository extends JpaRepository<DmMessage, String> {

    /** Newest-first page of a thread's messages (the frontend reverses for display). */
    Page<DmMessage> findByThreadIdOrderByCreatedAtDesc(String threadId, Pageable pageable);

    /** Count messages in a thread that are unread FOR {@code userId} (i.e. sent by the other party). */
    @Query("SELECT COUNT(m) FROM DmMessage m WHERE m.threadId = :threadId "
            + "AND m.senderUserId <> :userId AND m.readAt IS NULL")
    long countUnreadForUser(@Param("threadId") String threadId, @Param("userId") String userId);

    /** Mark every message the OTHER party sent in this thread as read. Returns rows updated. */
    @Modifying
    @Query("UPDATE DmMessage m SET m.readAt = :now WHERE m.threadId = :threadId "
            + "AND m.senderUserId <> :userId AND m.readAt IS NULL")
    int markThreadReadFor(@Param("threadId") String threadId,
                          @Param("userId") String userId,
                          @Param("now") Instant now);

    /**
     * Resolve the SENDER of the message (in this thread) that references the given
     * attachment storageId. Drives the DM attachment download: the file is then loaded
     * from the chat attachment store under that sender's tenant, so a storageId that no
     * message of the thread references - or that belongs to another tenant - yields 404.
     */
    @Query(value = "SELECT m.sender_user_id FROM conversation.dm_messages m "
            + "WHERE m.thread_id = :threadId AND m.attachments IS NOT NULL "
            + "AND EXISTS (SELECT 1 FROM jsonb_array_elements(m.attachments) a "
            + "            WHERE a->>'storageId' = :storageId) "
            // Oldest first: if both participants ever reference the same storageId, the
            // ORIGINAL sender (whose tenant owns the file) wins deterministically.
            + "ORDER BY m.created_at ASC LIMIT 1", nativeQuery = true)
    Optional<String> findAttachmentSenderInThread(@Param("threadId") String threadId,
                                                  @Param("storageId") String storageId);
}

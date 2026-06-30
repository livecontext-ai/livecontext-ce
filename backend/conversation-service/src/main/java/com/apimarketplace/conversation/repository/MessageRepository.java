package com.apimarketplace.conversation.repository;

import com.apimarketplace.conversation.entity.Message;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for Message entities
 */
@Repository
public interface MessageRepository extends JpaRepository<Message, String> {

    /**
     * Batch-fetch the first user message content (truncated to 100 chars) for
     * a set of conversation IDs. Returns one row per conversation that has at
     * least one USER message. Used by the sidebar listing to provide a title
     * fallback when the conversation has no meaningful title.
     */
    @Query(value =
            "SELECT DISTINCT ON (m.conversation_id) m.conversation_id, LEFT(m.content, 100) " +
            "FROM conversation.messages m " +
            "WHERE m.conversation_id IN (:ids) AND LOWER(m.role) = 'user' AND m.content IS NOT NULL " +
            "ORDER BY m.conversation_id, m.created_at ASC",
            nativeQuery = true)
    List<Object[]> findFirstUserMessagePreviewBatch(@Param("ids") List<String> ids);

    
    /**
     * Find all messages for a specific conversation
     */
    List<Message> findByConversationIdOrderByCreatedAtAsc(String conversationId);
    
    /**
     * Find messages for a conversation with pagination (ASC order)
     */
    Page<Message> findByConversationIdOrderByCreatedAtAsc(String conversationId, Pageable pageable);
    
    /**
     * Find messages for a conversation with pagination (DESC order - most recent first)
     */
    Page<Message> findByConversationIdOrderByCreatedAtDesc(String conversationId, Pageable pageable);
    
    /**
     * Find messages for a conversation filtered by executionId (chronological order).
     */
    List<Message> findByConversationIdAndExecutionIdOrderByCreatedAtAsc(String conversationId, String executionId);

    /**
     * Find messages for a conversation filtered by executionId, paginated (DESC order - most recent first).
     */
    Page<Message> findByConversationIdAndExecutionIdOrderByCreatedAtDesc(
        String conversationId, String executionId, Pageable pageable);

    /**
     * Find the most recent executionId for a conversation.
     * Returns the executionId of the newest message that has one.
     */
    @org.springframework.data.jpa.repository.Query(
        "SELECT m.executionId FROM Message m " +
        "WHERE m.conversation.id = :conversationId AND m.executionId IS NOT NULL " +
        "ORDER BY m.createdAt DESC")
    List<String> findLatestExecutionIds(@org.springframework.data.repository.query.Param("conversationId") String conversationId, Pageable pageable);

    /**
     * Count messages in a conversation by role. Used by HelpSeenRegistry wiring
     * to derive the current turn number (count of USER messages) for the
     * {@code lastSeenTurn} field, which drives the HOT/WARM freshness gate.
     */
    long countByConversationIdAndRole(String conversationId, Message.MessageRole role);

    /**
     * Delete all messages for a conversation
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM Message m WHERE m.conversation.id = :conversationId")
    int deleteByConversationId(@Param("conversationId") String conversationId);

}

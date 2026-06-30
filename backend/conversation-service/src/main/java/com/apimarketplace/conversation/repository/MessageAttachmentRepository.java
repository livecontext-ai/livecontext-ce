package com.apimarketplace.conversation.repository;

import com.apimarketplace.conversation.entity.MessageAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for MessageAttachment entities.
 */
@Repository
public interface MessageAttachmentRepository extends JpaRepository<MessageAttachment, UUID> {

    /**
     * Find all attachments for multiple messages.
     */
    List<MessageAttachment> findByMessageIdIn(List<String> messageIds);
}

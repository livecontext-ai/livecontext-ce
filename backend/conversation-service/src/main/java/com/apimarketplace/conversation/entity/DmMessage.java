package com.apimarketplace.conversation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * One message in a {@link DmThread}. {@code readAt} is set on the recipient's side
 * when they open the thread (a message is "unread" for user X when
 * {@code senderUserId != X AND readAt IS NULL}).
 */
@Entity
@Table(name = "dm_messages", schema = "conversation")
public class DmMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "thread_id", nullable = false, updatable = false)
    private String threadId;

    @Column(name = "sender_user_id", nullable = false, updatable = false)
    private String senderUserId;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    /**
     * Optional JSONB array of attachment references ({storageId, type, fileName, mimeType}),
     * serialized by DmService. NULL for text-only messages. The files themselves live in the
     * chat attachment store under the SENDER's tenant.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attachments", columnDefinition = "jsonb")
    private String attachments;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DmMessage() {
    }

    public DmMessage(String threadId, String senderUserId, String content) {
        this.threadId = threadId;
        this.senderUserId = senderUserId;
        this.content = content;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public String getId() {
        return id;
    }

    public String getThreadId() {
        return threadId;
    }

    public String getSenderUserId() {
        return senderUserId;
    }

    public String getContent() {
        return content;
    }

    public String getAttachments() {
        return attachments;
    }

    public void setAttachments(String attachments) {
        this.attachments = attachments;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public void setReadAt(Instant readAt) {
        this.readAt = readAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

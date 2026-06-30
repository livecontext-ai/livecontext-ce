package com.apimarketplace.conversation.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "streams", schema = "conversation",
       indexes = {
           @Index(name = "idx_stream_conversation_status", columnList = "conversation_id, status"),
           @Index(name = "idx_stream_user_status", columnList = "user_id, status"),
           @Index(name = "idx_stream_status_updated", columnList = "status, updated_at")
       })
public class Stream {
    
    @Id
    @Column(name = "id", length = 36)
    private String id;
    
    @Column(name = "conversation_id", length = 36, nullable = false)
    private String conversationId;
    
    @Column(name = "stream_id", length = 255, nullable = false)
    private String streamId;
    
    @Column(name = "user_id", length = 36, nullable = false)
    private String userId;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private StreamStatus status = StreamStatus.ACTIVE;
    
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
    
    @Column(name = "completed_at")
    private LocalDateTime completedAt;
    
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;
    
    // Colonne supprimee - plus de sauvegarde par chunks
    // @Column(name = "stream_content", columnDefinition = "TEXT")
    // private String streamContent;
    
    // Relationship with Conversation (optional, for JPA queries)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id", insertable = false, updatable = false)
    private Conversation conversation;
    
    public enum StreamStatus {
        ACTIVE,      // Stream is currently active
        STOPPED,     // Stream was stopped by user
        COMPLETED,   // Stream completed naturally
        ERROR,       // Stream ended due to error
        INTERRUPTED  // Producer died (pod drain/shutdown, heartbeat lost) - partial content rescued
        // Column is @Enumerated(STRING) → adding a value requires no DB migration
    }
    
    // Helper methods
    public boolean isActive() {
        return StreamStatus.ACTIVE.equals(this.status);
    }
    
    public void markAsCompleted() {
        this.status = StreamStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
    }
    
    public void markAsStopped() {
        this.status = StreamStatus.STOPPED;
        this.completedAt = LocalDateTime.now();
    }
    
    public void markAsError(String errorMessage) {
        this.status = StreamStatus.ERROR;
        this.errorMessage = errorMessage;
        this.completedAt = LocalDateTime.now();
    }

    public void markAsInterrupted(String reason) {
        this.status = StreamStatus.INTERRUPTED;
        this.errorMessage = reason;
        this.completedAt = LocalDateTime.now();
    }
    
    // Constructors
    public Stream() {
    }
    
    public Stream(String id, String conversationId, String streamId, String userId, StreamStatus status) {
        this.id = id;
        this.conversationId = conversationId;
        this.streamId = streamId;
        this.userId = userId;
        this.status = status;
    }
    
    // Getters and Setters
    public String getId() {
        return id;
    }
    
    public void setId(String id) {
        this.id = id;
    }
    
    public String getConversationId() {
        return conversationId;
    }
    
    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }
    
    public String getStreamId() {
        return streamId;
    }
    
    public void setStreamId(String streamId) {
        this.streamId = streamId;
    }
    
    public String getUserId() {
        return userId;
    }
    
    public void setUserId(String userId) {
        this.userId = userId;
    }
    
    public StreamStatus getStatus() {
        return status;
    }
    
    public void setStatus(StreamStatus status) {
        this.status = status;
    }
    
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
    
    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
    
    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
    
    public LocalDateTime getCompletedAt() {
        return completedAt;
    }
    
    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }
    
    public String getErrorMessage() {
        return errorMessage;
    }
    
    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
    
    // Methodes supprimees - plus de sauvegarde par chunks
    // public String getStreamContent()
    // public void setStreamContent(String streamContent)
    
    public Conversation getConversation() {
        return conversation;
    }
    
    public void setConversation(Conversation conversation) {
        this.conversation = conversation;
    }
    
    // Equals and HashCode
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Stream stream = (Stream) o;
        return Objects.equals(id, stream.id);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
    
    @Override
    public String toString() {
        return "Stream{" +
                "id='" + id + '\'' +
                ", conversationId='" + conversationId + '\'' +
                ", streamId='" + streamId + '\'' +
                ", userId='" + userId + '\'' +
                ", status=" + status +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                ", completedAt=" + completedAt +
                ", errorMessage='" + errorMessage + '\'' +
                '}';
    }
}

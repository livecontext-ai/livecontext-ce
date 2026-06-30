package com.apimarketplace.trigger.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "chat_endpoint_access_logs", schema = "trigger")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class ChatEndpointAccessLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_endpoint_id", nullable = false)
    private UUID chatEndpointId;

    @Column(name = "session_id")
    private String sessionId;

    @Column(name = "conversation_id")
    private String conversationId;

    @Column(name = "action", nullable = false, length = 30)
    private String action;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "accessed_at", nullable = false)
    private Instant accessedAt;

    public ChatEndpointAccessLogEntity() {
        this.accessedAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public UUID getChatEndpointId() { return chatEndpointId; }
    public void setChatEndpointId(UUID chatEndpointId) { this.chatEndpointId = chatEndpointId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
    public Instant getAccessedAt() { return accessedAt; }
    public void setAccessedAt(Instant accessedAt) { this.accessedAt = accessedAt; }
}

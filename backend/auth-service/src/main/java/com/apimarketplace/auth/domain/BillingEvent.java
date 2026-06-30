package com.apimarketplace.auth.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "billing_event",
       indexes = {
               @Index(name = "idx_be_provider",   columnList = "provider"),
               @Index(name = "idx_be_type",       columnList = "type"),
               @Index(name = "idx_be_received_at",columnList = "received_at")
       })
public class BillingEvent {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank @Column(nullable = false)
    private String provider;

    @NotBlank @Column(name = "event_id", nullable = false, unique = true)
    private String eventId;

    @NotBlank @Column(nullable = false)
    private String type;

    @NotNull
    @JdbcTypeCode(SqlTypes.JSON)                 // ✅ Hibernate → jsonb
    @Column(columnDefinition = "jsonb", nullable = false)
    private JsonNode payload;                    // ✅ plus String

    @NotNull
    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    public BillingEvent() {}

    public BillingEvent(String provider, String eventId, String type, JsonNode payload) {
        this.provider = provider;
        this.eventId = eventId;
        this.type = type;
        this.payload = payload;
    }

    @PrePersist
    public void prePersist() { this.receivedAt = LocalDateTime.now(); }

    // Getters and Setters

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public JsonNode getPayload() { return payload; }
    public void setPayload(JsonNode payload) { this.payload = payload; }

    public LocalDateTime getReceivedAt() { return receivedAt; }
    public void setReceivedAt(LocalDateTime receivedAt) { this.receivedAt = receivedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BillingEvent that = (BillingEvent) o;
        return Objects.equals(id, that.id) && Objects.equals(eventId, that.eventId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, eventId);
    }

    @Override
    public String toString() {
        return "BillingEvent{" +
                "id=" + id +
                ", provider='" + provider + '\'' +
                ", eventId='" + eventId + '\'' +
                ", type='" + type + '\'' +
                ", receivedAt=" + receivedAt +
                '}';
    }
}

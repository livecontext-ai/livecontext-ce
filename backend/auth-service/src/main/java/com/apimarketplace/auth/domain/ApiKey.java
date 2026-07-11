package com.apimarketplace.auth.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A named lc_live_ API key owned by a user (V398, auth.api_keys).
 *
 * <p>Coexists with the legacy single key on {@link User} (api_key_hash/api_key_hint),
 * which stays a full-access key. Rows here can additionally carry {@code scopes}:
 * a comma-separated list of MCP tool names the key may call; {@code null} = full
 * access. Revocation is soft ({@code revokedAt}) so hints stay auditable.</p>
 */
@Entity
@Table(name = "api_keys")
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "key_hash", nullable = false, length = 64, unique = true)
    private String keyHash;

    @Column(name = "key_hint", nullable = false, length = 20)
    private String keyHint;

    /** Comma-separated, normalized (lowercase, trimmed) tool names. Null = full access. */
    @Column(name = "scopes")
    private String scopes;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    public ApiKey() {
        this.createdAt = LocalDateTime.now();
    }

    public ApiKey(Long userId, String name, String keyHash, String keyHint, String scopes) {
        this();
        this.userId = userId;
        this.name = name;
        this.keyHash = keyHash;
        this.keyHint = keyHint;
        this.scopes = scopes;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getKeyHash() { return keyHash; }
    public void setKeyHash(String keyHash) { this.keyHash = keyHash; }

    public String getKeyHint() { return keyHint; }
    public void setKeyHint(String keyHint) { this.keyHint = keyHint; }

    public String getScopes() { return scopes; }
    public void setScopes(String scopes) { this.scopes = scopes; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(LocalDateTime lastUsedAt) { this.lastUsedAt = lastUsedAt; }

    public LocalDateTime getRevokedAt() { return revokedAt; }
    public void setRevokedAt(LocalDateTime revokedAt) { this.revokedAt = revokedAt; }
}

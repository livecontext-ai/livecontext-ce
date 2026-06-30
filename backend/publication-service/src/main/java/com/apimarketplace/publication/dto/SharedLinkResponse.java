package com.apimarketplace.publication.dto;

import com.apimarketplace.publication.domain.SharedLinkEntity;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * DTO for shared link responses. Excludes tenantId and sensitive accessConfig fields.
 */
public record SharedLinkResponse(
        UUID id,
        String token,
        String resourceType,
        String resourceToken,
        UUID resourceId,
        String title,
        String description,
        boolean isActive,
        Map<String, Object> metadata,
        long accessCount,
        Instant lastAccessed,
        Instant createdAt,
        Instant updatedAt
) {
    public static SharedLinkResponse from(SharedLinkEntity entity) {
        return new SharedLinkResponse(
                entity.getId(),
                entity.getToken(),
                entity.getResourceType().name(),
                entity.getResourceToken(),
                entity.getResourceId(),
                entity.getTitle(),
                entity.getDescription(),
                entity.isActive(),
                entity.getMetadata(),
                entity.getAccessCount(),
                entity.getLastAccessed(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}

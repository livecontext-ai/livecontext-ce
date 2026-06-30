package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.repository.ApiRepository;
import com.apimarketplace.catalog.service.exception.AccessDeniedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Service for authorization checks.
 * Verifies that users have permission to access or modify resources.
 *
 * Authorization rules:
 * - Users can only modify APIs they created (createdBy field)
 * - Users can only modify Tools belonging to APIs they created
 * - Public/shared APIs can be read by anyone but only modified by owners
 * - Admin users (future) can modify any resource
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthorizationService {

    private final ApiRepository apiRepository;

    /**
     * Verify that the user owns the specified API.
     *
     * @param userId The user ID from X-User-Id header
     * @param apiId The API ID to check
     * @throws AccessDeniedException if user doesn't own the API
     */
    public void verifyApiOwnership(String userId, UUID apiId) {
        if (userId == null || userId.isBlank()) {
            log.warn("Authorization check failed: missing userId for API {}", apiId);
            throw AccessDeniedException.forApi("anonymous", apiId.toString());
        }

        Optional<ApiEntity> apiOpt = apiRepository.findById(apiId);
        if (apiOpt.isEmpty()) {
            log.debug("API not found: {}", apiId);
            return; // Let the controller handle not found
        }

        ApiEntity api = apiOpt.get();
        if (!isOwner(userId, api.getCreatedBy())) {
            log.warn("Access denied: user '{}' attempted to access API '{}' owned by '{}'",
                userId, apiId, api.getCreatedBy());
            throw AccessDeniedException.forApi(userId, apiId.toString());
        }

        log.debug("Authorization granted: user '{}' owns API '{}'", userId, apiId);
    }




    /**
     * Check if two user IDs represent the same user.
     * Handles null values and case-insensitive comparison.
     */
    private boolean isOwner(String requestUserId, String resourceOwnerId) {
        if (requestUserId == null || resourceOwnerId == null) {
            return false;
        }
        return requestUserId.equalsIgnoreCase(resourceOwnerId);
    }

}

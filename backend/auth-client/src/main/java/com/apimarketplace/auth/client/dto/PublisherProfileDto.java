package com.apimarketplace.auth.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Server-side snapshot of a publisher's public identity at publish time.
 *
 * <p>Returned by {@code GET /api/internal/auth/users/{userId}/publisher-profile}
 * and consumed by publication-service when a workflow / agent / resource is
 * (re)published. The publication freezes these three fields into its row so
 * the marketplace view stays stable even if the publisher later renames
 * themselves or changes their avatar.
 *
 * <p>Any field may be null when auth-service has no row for that input
 * (deleted user, onboarding not yet completed, no avatar uploaded). The
 * caller decides how to handle each null individually.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PublisherProfileDto(
        String userId,
        String displayName,
        String email,
        String avatarUrl
) {}

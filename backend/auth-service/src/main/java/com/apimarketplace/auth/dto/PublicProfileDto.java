package com.apimarketplace.auth.dto;

import java.time.LocalDateTime;

/**
 * Profile shown IN-APP on the user profile page. Contains only data safe to surface to
 * another signed-in user - the chosen <b>display name</b> and a public <b>@handle</b>
 * (never the real first/last name, never the raw OAuth account username), avatar, bio and
 * join date. The {@code handle} is the URL-safe public id used at {@code /app/u/{handle}};
 * the numeric {@code userId} stays for internal links (e.g. by-publisher) but is never the
 * URL identifier. No email, no roles.
 *
 * <p>Publication count is intentionally NOT included: publication-service owns that
 * data and the frontend reads it from {@code GET /api/publications/by-publisher/{userId}}
 * ({@code totalCount}). auth-service never queries the publication schema (strict
 * per-service schema isolation).
 */
public record PublicProfileDto(
        Long userId,
        String displayName,
        String handle,
        String avatarUrl,
        String bio,
        LocalDateTime joinedAt
) {
}

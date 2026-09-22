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
        LocalDateTime joinedAt,
        /**
         * Whether the owner opted this profile into search indexing (the PUBLIC
         * visibility state). False for UNLISTED, which is the default and means
         * "reachable by link, but not advertised to search engines".
         *
         * <p>Exposed because the page that renders this profile is the only place
         * that can emit the noindex directive, and it must not have to guess.
         */
        boolean searchIndexable,
        /**
         * Whether this account carries the verified badge (the blue check next to
         * the name). Resolved live by {@code VerifiedAccountService} from the ADMIN
         * role plus the manual grant, so a badge granted after a listing was
         * published shows up everywhere at once instead of only on new rows.
         *
         * <p>Always false on a self-hosted deployment: the badge is a managed-cloud
         * feature. Not to be confused with e-mail verification, which is never
         * exposed here.
         */
        boolean verified
) {
}

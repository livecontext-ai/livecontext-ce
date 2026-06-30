package com.apimarketplace.auth.web;

import com.apimarketplace.auth.dto.PublicProfileDto;
import com.apimarketplace.auth.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * In-app, authenticated read-only access to another user's profile. NOT on the gateway
 * public allowlist, so a JWT is required - logged-out visitors cannot read profiles.
 *
 * <p>Two lookups, neither of which can expose the real first/last name or the raw OAuth
 * account username:
 * <ul>
 *   <li>{@code by-handle/{handle}} - the canonical URL lookup ({@code /app/u/{handle}}). The
 *       handle is a chosen, URL-safe public slug derived from the display name (never the raw
 *       account username, never the numeric user/tenant id).</li>
 *   <li>{@code by-id/{userId}} - for internal links that already carry the numeric id (e.g. a
 *       DM thread or a publication card), which resolve the profile without a handle.</li>
 * </ul>
 *
 * <p>The returned {@link PublicProfileDto} exposes the display name + @handle, avatar, bio and
 * join date - no email, no roles. Returns 404 when the user does not exist, is disabled, or has
 * set their profile to PRIVATE (indistinguishable, so this can't be a user-existence oracle).
 */
@RestController
@RequestMapping("/api/users/public")
public class PublicProfileController {

    private final UserService userService;

    public PublicProfileController(UserService userService) {
        this.userService = userService;
    }

    /** Canonical URL lookup by the public @handle ({@code /app/u/{handle}}). */
    @GetMapping("/by-handle/{handle}")
    public ResponseEntity<PublicProfileDto> getByHandle(@PathVariable String handle) {
        return userService.findByHandle(handle)
                .flatMap(userService::getPublicProfile)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Lookup by numeric user id - for internal links (DM / publication cards carry the id). */
    @GetMapping("/by-id/{userId}")
    public ResponseEntity<PublicProfileDto> getById(@PathVariable Long userId) {
        return userService.findById(userId)
                .flatMap(userService::getPublicProfile)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}

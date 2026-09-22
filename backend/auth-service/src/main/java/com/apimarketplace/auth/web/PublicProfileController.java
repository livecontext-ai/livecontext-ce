package com.apimarketplace.auth.web;

import com.apimarketplace.auth.dto.PublicProfileDto;
import com.apimarketplace.auth.service.UserService;
import com.apimarketplace.auth.service.VerifiedAccountService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    private final VerifiedAccountService verifiedAccountService;

    public PublicProfileController(UserService userService,
                                   VerifiedAccountService verifiedAccountService) {
        this.userService = userService;
        this.verifiedAccountService = verifiedAccountService;
    }

    /** Canonical URL lookup by the public @handle ({@code /app/u/{handle}}). */
    @GetMapping("/by-handle/{handle}")
    public ResponseEntity<PublicProfileDto> getByHandle(@PathVariable String handle) {
        return userService.findByHandle(handle)
                .flatMap(userService::getPublicProfile)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Lookup by numeric user id - for in-app links (DM threads / publication
     * cards carry the id, not the handle).
     *
     * <p><b>Requires an authenticated caller.</b> The id is sequential, so an
     * anonymous by-id would let anyone walk 1..N and harvest every profile on
     * the platform: display name and handle for the whole user base. The
     * cloud gateway already keeps this path off its public allowlist (only
     * {@code /by-handle} is public), but CE has no gateway - its monolith
     * filter passes any request with no Authorization header straight through
     * and leaves the decision to this layer. Enforcing it here is what makes
     * the rule hold in BOTH editions instead of only on cloud.
     *
     * <p>Answers 404, not 401, so it stays indistinguishable from a missing or
     * private profile and cannot be used to probe which ids exist.
     */
    @GetMapping("/by-id/{userId}")
    public ResponseEntity<PublicProfileDto> getById(
            @PathVariable Long userId,
            @RequestHeader(value = "X-User-ID", required = false) String requesterId) {
        if (requesterId == null || requesterId.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        return userService.findById(userId)
                .flatMap(userService::getPublicProfile)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Which of these users carry the verified badge. One request per rendered list,
     * never one per name.
     *
     * <p>{@code GET /api/users/public/verified-badges?ids=1,2,3} answers
     * {@code {"verified":[1,3]}} - only the ids that qualify. Unknown, disabled and
     * unverified ids are indistinguishable in the answer.
     *
     * <p><b>Requires an authenticated caller</b>, for the same reason {@code /by-id}
     * does: the ids are sequential, so an anonymous version would let anyone walk
     * 1..N and page out the platform's verified (and therefore admin) accounts. The
     * server-rendered public pages use the handle-keyed sibling below instead.
     *
     * <p>The anonymous branch below answers an empty list rather than 401, but it is
     * defence in depth only: this path is NOT on the gateway allowlist, so on cloud the
     * gateway rejects an anonymous caller before the handler runs, and what actually
     * keeps a failed lookup from taking a page down is the client failing closed. The
     * branch exists because CE has no gateway. Always empty on a self-hosted
     * deployment, where the badge does not exist.
     */
    @GetMapping("/verified-badges")
    public ResponseEntity<Map<String, Object>> verifiedBadges(
            @RequestParam(value = "ids", required = false) String ids,
            @RequestHeader(value = "X-User-ID", required = false) String requesterId) {
        if (requesterId == null || requesterId.isBlank()) {
            return ResponseEntity.ok(Map.of("verified", List.of()));
        }
        List<String> raw = splitCsv(ids);
        if (raw.size() > VerifiedAccountService.MAX_BATCH_SIZE) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "too_many_ids",
                    "message", "At most " + VerifiedAccountService.MAX_BATCH_SIZE + " ids per request"));
        }
        List<Long> userIds = new ArrayList<>();
        for (String value : raw) {
            try {
                userIds.add(Long.parseLong(value));
            } catch (NumberFormatException ignored) {
                // A non-numeric id cannot match anything; dropping it keeps one bad
                // value from failing the whole page's badge lookup.
            }
        }
        return ResponseEntity.ok(Map.of("verified", new ArrayList<>(verifiedAccountService.verifiedAmong(userIds))));
    }

    /**
     * Same question keyed by public @handle, for the anonymous server-rendered
     * marketplace pages.
     *
     * <p>{@code GET /api/users/public/verified-handles?handles=ada,linus} answers
     * {@code {"verified":["ada"]}}, echoing back the spelling that was asked for.
     *
     * <p>This one IS on the gateway public allowlist, on two grounds. A handle is
     * user-chosen and not enumerable, so an anonymous caller can only ask about authors
     * a public page already named; and an account whose profile page is PRIVATE is
     * excluded from the answer, so this never discloses more than {@code /by-handle}
     * does (which answers 404 for exactly that state). Always empty on a self-hosted
     * deployment.
     */
    @GetMapping("/verified-handles")
    public ResponseEntity<Map<String, Object>> verifiedHandles(
            @RequestParam(value = "handles", required = false) String handles) {
        List<String> requested = splitCsv(handles);
        if (requested.size() > VerifiedAccountService.MAX_BATCH_SIZE) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "too_many_handles",
                    "message", "At most " + VerifiedAccountService.MAX_BATCH_SIZE + " handles per request"));
        }
        return ResponseEntity.ok(Map.of(
                "verified", new ArrayList<>(verifiedAccountService.verifiedHandlesAmong(requested))));
    }

    /** Split a comma-separated query parameter, dropping blanks. */
    private static List<String> splitCsv(String value) {
        List<String> out = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return out;
        }
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }
}

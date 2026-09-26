package com.apimarketplace.auth.web;

import com.apimarketplace.auth.service.ChangelogSeenService;
import com.apimarketplace.auth.service.ChangelogSeenService.ChangelogState;
import com.apimarketplace.auth.service.ChangelogSeenService.SeenOutcome;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Per-user state of the in-app "What's new" announcement.
 *
 * <ul>
 *   <li>{@code GET  /api/changelog/state?entry=<key>} - is the feature on here, which entry has
 *       this user already acknowledged, and should this one be sealed silently (an account
 *       created after the install started announcing that entry never lacked it).</li>
 *   <li>{@code POST /api/changelog/seen} - acknowledge the entry that was actually displayed.</li>
 * </ul>
 *
 * <p>The announcement CONTENT is not served here. It ships with the frontend build, so each
 * deployment announces exactly what it is running and an offline install renders the same thing as
 * a cloud tenant. This controller owns only the half that must follow the user across devices.
 *
 * <p>One controller serves both editions: in cloud the gateway routes {@code /api/changelog/**}
 * here and injects {@code X-User-ID} from the JWT; in CE the monolith component-scans this class
 * and its own security filter injects the same header.
 *
 * <p><strong>The success payloads are records, deliberately not maps.</strong> The CE monolith
 * component-scans catalog-service's {@code @Primary} ObjectMapper, which sets
 * {@code WRITE_NULL_MAP_VALUES=false}: a {@code Map} response silently loses every null-valued key
 * THERE and keeps it in cloud. That is the one shape this endpoint must never have, because a
 * missing {@code seenKey} is what the client reads as "never acknowledged" - the difference
 * between "stay quiet" and "announce again". A record serializes through the bean serializer, which
 * that feature does not touch, so both editions answer the same JSON. Verified live on CE, where
 * the map form answered {@code {"accountCreatedAt":...,"enabled":true}} with no {@code seenKey} at
 * all.
 */
@RestController
@RequestMapping("/api/changelog")
public class ChangelogController {

    private static final Logger log = LoggerFactory.getLogger(ChangelogController.class);

    private final ChangelogSeenService changelogSeenService;

    public ChangelogController(ChangelogSeenService changelogSeenService) {
        this.changelogSeenService = changelogSeenService;
    }

    /**
     * @param entryKey the entry the caller is about to announce. Optional: without it the answer
     *                 carries the acknowledgement alone and never a seal, which is what a build
     *                 shipping no entry needs.
     */
    @GetMapping("/state")
    public ResponseEntity<ChangelogStateResponse> getState(
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader,
            @RequestParam(value = "entry", required = false) String entryKey) {
        Long userId = parseUserId(userIdHeader);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        ChangelogState state = changelogSeenService.state(userId, entryKey);
        return ResponseEntity.ok(new ChangelogStateResponse(state.enabled(), state.seenKey(), state.seal()));
    }

    @PostMapping("/seen")
    public ResponseEntity<?> markSeen(
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader,
            @RequestBody(required = false) SeenRequest request) {
        Long userId = parseUserId(userIdHeader);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String key = request == null ? null : request.key();
        if (!ChangelogSeenService.isValidKey(key)) {
            // The rejected value is deliberately NOT echoed: it is unbounded caller input, and
            // its length is the only part that helps whoever is debugging a real key.
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "key must be 1-120 characters of letters, digits, dot, dash or underscore",
                    "receivedLength", key == null ? 0 : key.length()));
        }
        SeenOutcome outcome = changelogSeenService.markSeen(userId, key);
        return switch (outcome) {
            case RECORDED -> ResponseEntity.ok(new SeenResponse(true, key));
            case DISABLED -> {
                // 200, not an error: the client asked for something the deployment does not do.
                // It reads `enabled` and stops announcing, which is exactly what a disabled
                // feature should look like from the outside.
                log.debug("Changelog acknowledgement ignored - the feature is disabled on this deployment");
                yield ResponseEntity.ok(new SeenResponse(false, null));
            }
            case UNKNOWN_USER -> {
                // The header names an account that no longer exists: the gateway caches user
                // resolution for minutes, so a session outlives the deletion of its account. This
                // used to be a 500 on the foreign key.
                //
                // 404, deliberately NOT 401. The web client treats a 401 as a dead session: it
                // refreshes the token, retries, and on a second 401 redirects to login. A
                // background acknowledgement must not be what logs someone out; the requests that
                // decide the session do that. A 404 is dropped silently by the client (no retry,
                // the optimistic "seen" state is kept).
                log.info("Changelog acknowledgement ignored - no account has userId={}", userId);
                yield ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "user not found"));
            }
        };
    }

    /**
     * The gateway (cloud) and the monolith security filter (CE) both inject a numeric user id.
     * A missing or non-numeric header is an unauthenticated caller, never a user id of 0.
     */
    private static Long parseUserId(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(header.trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /** Body of an acknowledgement: the key of the entry that was actually displayed. */
    public record SeenRequest(String key) {
    }

    /**
     * What the client decides on.
     *
     * <p>{@code ALWAYS} is not the Jackson default everywhere in this codebase, and it is the whole
     * point here: {@code seenKey: null} must be written, because an ABSENT key and a null one are
     * the same value to a JavaScript client only by accident of the HTTP library.
     *
     * @param enabled whether this deployment surfaces the changelog at all
     * @param seenKey last acknowledged entry key, or null when the user acknowledged none
     * @param seal    acknowledge the requested entry WITHOUT showing it: this account is newer
     *                than the moment this install started announcing it
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ChangelogStateResponse(boolean enabled, String seenKey, boolean seal) {
    }

    /**
     * What was recorded. {@code seenKey} is null when the deployment has the feature off, in which
     * case nothing was written.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SeenResponse(boolean enabled, String seenKey) {
    }
}

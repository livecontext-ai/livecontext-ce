package com.apimarketplace.publication.controller;

import com.apimarketplace.publication.service.CreatorFollowService;
import com.apimarketplace.publication.service.CreatorFollowService.FollowStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Follow a creator from their profile, to be notified of each new marketplace listing.
 *
 * <p>Authenticated: the gateway injects {@code X-User-ID}. The paths live under
 * {@code /creators/...} on purpose, NOT under the public {@code /by-publisher/...} prefix,
 * which the gateway lets through without a user.
 */
@RestController
@RequestMapping("/api/publications/creators")
public class CreatorFollowController {

    private final CreatorFollowService followService;

    public CreatorFollowController(CreatorFollowService followService) {
        this.followService = followService;
    }

    @GetMapping("/{creatorId}/follow")
    public ResponseEntity<?> status(@RequestHeader("X-User-ID") String userId,
                                    @PathVariable String creatorId) {
        return respond(() -> followService.status(userId, creatorId));
    }

    @PostMapping("/{creatorId}/follow")
    public ResponseEntity<?> follow(@RequestHeader("X-User-ID") String userId,
                                    @PathVariable String creatorId) {
        return respond(() -> followService.follow(userId, creatorId));
    }

    @DeleteMapping("/{creatorId}/follow")
    public ResponseEntity<?> unfollow(@RequestHeader("X-User-ID") String userId,
                                      @PathVariable String creatorId) {
        return respond(() -> followService.unfollow(userId, creatorId));
    }

    private static ResponseEntity<?> respond(java.util.function.Supplier<FollowStatus> action) {
        try {
            FollowStatus status = action.get();
            return ResponseEntity.ok(Map.of(
                    "following", status.following(),
                    "followerCount", status.followerCount()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }
}

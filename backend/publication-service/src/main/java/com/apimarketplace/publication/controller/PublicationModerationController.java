package com.apimarketplace.publication.controller;

import com.apimarketplace.common.web.AdminRoleGuard;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.service.PublicationModerationService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/publications/admin")
public class PublicationModerationController {

    private final PublicationModerationService moderationService;

    public PublicationModerationController(PublicationModerationService moderationService) {
        this.moderationService = moderationService;
    }

    @GetMapping("/pending")
    public ResponseEntity<?> getPendingPublications(
            @RequestHeader("X-User-ID") String userId,
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        if (!isAdmin(roles)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Admin access required"));
        }

        Page<WorkflowPublicationEntity> pending = moderationService.getPendingPublications(PageRequest.of(page, size));
        return ResponseEntity.ok(Map.of(
                "publications", pending.getContent(),
                "totalElements", pending.getTotalElements(),
                "totalPages", pending.getTotalPages(),
                "page", pending.getNumber(),
                "size", pending.getSize()
        ));
    }

    @GetMapping("/{publicationId}")
    public ResponseEntity<?> getPublicationForReview(
            @RequestHeader("X-User-ID") String userId,
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @PathVariable UUID publicationId) {

        if (!isAdmin(roles)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Admin access required"));
        }

        WorkflowPublicationEntity publication = moderationService.getPublicationForReview(publicationId);
        return ResponseEntity.ok(publication);
    }

    @GetMapping("/{publicationId}/comparison")
    public ResponseEntity<?> getComparisonData(
            @RequestHeader("X-User-ID") String userId,
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @PathVariable UUID publicationId) {

        if (!isAdmin(roles)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Admin access required"));
        }

        try {
            Map<String, Object> comparison = moderationService.getComparisonData(publicationId);
            return ResponseEntity.ok(comparison);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{publicationId}/approve")
    public ResponseEntity<?> approvePublication(
            @RequestHeader("X-User-ID") String userId,
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @PathVariable UUID publicationId) {

        if (!isAdmin(roles)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Admin access required"));
        }

        try {
            WorkflowPublicationEntity approved = moderationService.approvePublication(publicationId, userId);
            return ResponseEntity.ok(approved);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{publicationId}/reject")
    public ResponseEntity<?> rejectPublication(
            @RequestHeader("X-User-ID") String userId,
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @PathVariable UUID publicationId,
            @RequestBody(required = false) Map<String, String> body) {

        if (!isAdmin(roles)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Admin access required"));
        }

        String reason = body != null ? body.get("reason") : null;

        try {
            WorkflowPublicationEntity rejected = moderationService.rejectPublication(publicationId, userId, reason);
            return ResponseEntity.ok(rejected);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/stats")
    public ResponseEntity<?> getStats(
            @RequestHeader("X-User-ID") String userId,
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles) {

        if (!isAdmin(roles)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Admin access required"));
        }

        return ResponseEntity.ok(moderationService.getStats());
    }

    private boolean isAdmin(String roles) {
        return AdminRoleGuard.isAdmin(roles);
    }
}

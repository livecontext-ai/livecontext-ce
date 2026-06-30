package com.apimarketplace.auth.web;

import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.dto.AvatarInfo;
import com.apimarketplace.auth.dto.UserProfile;
import com.apimarketplace.auth.dto.UserProfileUpdateRequest;
import com.apimarketplace.auth.service.OnboardingService;
import com.apimarketplace.auth.service.UserService;
import com.apimarketplace.auth.util.InitialsAvatarGenerator;
import com.apimarketplace.auth.util.ReservedUsernames;
import com.apimarketplace.common.storage.domain.StorageEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Contrôleur pour la gestion des utilisateurs
 */
@RestController
@RequestMapping("/api/users")
@CrossOrigin(origins = "*")
@Validated
public class UserController {

    @Autowired
    private UserService userService;

    @Autowired
    private OnboardingService onboardingService;

    /**
     * Recupere le profil de l'utilisateur connecte
     */
    @GetMapping("/profile")
    public ResponseEntity<UserProfile> getMyProfile(
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader,
            @RequestHeader(value = "X-Provider-ID", required = false) String providerIdHeader) {

        // Essayer d'abord les headers du gateway
        if (userIdHeader != null && providerIdHeader != null) {
            try {
                Long userId = Long.parseLong(userIdHeader);
                Optional<User> userOpt = userService.findById(userId);

                if (userOpt.isEmpty()) {
                    return ResponseEntity.status(404).body(null);
                }

                UserProfile profile = userService.mapToUserProfile(userOpt.get());
                return ResponseEntity.ok(profile);
            } catch (NumberFormatException e) {
                return ResponseEntity.badRequest().body(null);
            }
        }

        // Fallback sur SecurityContextHolder si pas de headers
        Optional<User> userOpt = getCurrentUser();
        if (userOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        UserProfile profile = userService.mapToUserProfile(userOpt.get());
        return ResponseEntity.ok(profile);
    }

    /**
     * Met a jour le profil de l'utilisateur connecte
     */
    @PutMapping("/profile")
    public ResponseEntity<?> updateMyProfile(
            @Valid @RequestBody UserProfileUpdateRequest request,
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader,
            @RequestHeader(value = "X-Provider-ID", required = false) String providerIdHeader) {

        // Essayer d'abord les headers du gateway
        if (userIdHeader != null && providerIdHeader != null) {
            try {
                Long userId = Long.parseLong(userIdHeader);
                Optional<User> userOpt = userService.findById(userId);

                if (userOpt.isEmpty()) {
                    return ResponseEntity.status(404).body(null);
                }

                User updatedUser = userService.updateProfile(userOpt.get(), request);
                UserProfile profile = userService.mapToUserProfile(updatedUser);
                return ResponseEntity.ok(profile);
            } catch (NumberFormatException e) {
                return ResponseEntity.badRequest().body(null);
            } catch (IllegalStateException e) {
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .body(java.util.Map.of("error", e.getMessage()));
            }
        }

        // Fallback sur SecurityContextHolder si pas de headers
        Optional<User> userOpt = getCurrentUser();
        if (userOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        try {
            User updatedUser = userService.updateProfile(userOpt.get(), request);
            UserProfile profile = userService.mapToUserProfile(updatedUser);
            return ResponseEntity.ok(profile);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(java.util.Map.of("error", e.getMessage()));
        }
    }

    /**
     * Desactive le compte de l'utilisateur connecte
     */
    @DeleteMapping("/profile")
    public ResponseEntity<Void> deactivateMyAccount(
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader,
            @RequestHeader(value = "X-Provider-ID", required = false) String providerIdHeader) {
        if (userIdHeader != null && providerIdHeader != null) {
            try {
                Long userId = Long.parseLong(userIdHeader);
                Optional<User> userOpt = userService.findById(userId);

                if (userOpt.isEmpty()) {
                    return ResponseEntity.notFound().build();
                }

                userService.deactivateUser(userOpt.get());
                return ResponseEntity.ok().build();
            } catch (NumberFormatException e) {
                return ResponseEntity.badRequest().build();
            }
        }

        Optional<User> userOpt = getCurrentUser();
        if (userOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        userService.deactivateUser(userOpt.get());
        return ResponseEntity.ok().build();
    }

    /**
     * Recupere le statut de premier login et profil de l'utilisateur connecte
     */
    @GetMapping("/status")
    public ResponseEntity<java.util.Map<String, Object>> getUserStatus(
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader,
            @RequestHeader(value = "X-Provider-ID", required = false) String providerIdHeader) {

        // Essayer d'abord les headers du gateway
        if (userIdHeader != null && providerIdHeader != null) {
            try {
                Long userId = Long.parseLong(userIdHeader);
                Optional<User> userOpt = userService.findById(userId);

                if (userOpt.isEmpty()) {
                    return ResponseEntity.status(404).body(java.util.Map.of("error", "Utilisateur non trouve"));
                }

                User user = userOpt.get();

                // Check onboarding status using the new onboarding service
                boolean needsOnboarding = onboardingService.needsOnboarding(providerIdHeader);

                // Verifier si l'utilisateur a le rôle admin
                boolean isAdmin = user.getRoles() != null && user.getRoles().contains("ADMIN");

                java.util.Map<String, Object> response = new java.util.HashMap<>();
                response.put("userId", user.getId());
                response.put("needsOnboarding", needsOnboarding);
                response.put("firstLogin", needsOnboarding);
                response.put("profileIncomplete", needsOnboarding);
                response.put("email", user.getEmail());
                response.put("isAdmin", isAdmin);
                response.put("roles", user.getRoles());
                response.put("avatarUrl", isStorageBackedAvatar(user.getAvatarUrl())
                        ? "/api/users/" + user.getId() + "/avatar" : null);

                return ResponseEntity.ok(response);
            } catch (NumberFormatException e) {
                return ResponseEntity.badRequest().body(java.util.Map.of("error", "Format utilisateur invalide"));
            }
        }

        // Fallback sur SecurityContextHolder si pas de headers
        Optional<User> userOpt = getCurrentUser();
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(401).body(java.util.Map.of("error", "Utilisateur non authentifie"));
        }

        User user = userOpt.get();

        // Default - no provider ID, assume onboarding needed if user exists
        boolean needsOnboarding = true;

        // Verifier si l'utilisateur a le rôle admin
        boolean isAdmin = user.getRoles() != null && user.getRoles().contains("ADMIN");

        java.util.Map<String, Object> response = new java.util.HashMap<>();
        response.put("userId", user.getId());
        response.put("needsOnboarding", needsOnboarding);
        response.put("firstLogin", needsOnboarding);
        response.put("profileIncomplete", needsOnboarding);
        response.put("email", user.getEmail());
        response.put("isAdmin", isAdmin);
        response.put("roles", user.getRoles());

        return ResponseEntity.ok(response);
    }

    /**
     * Verifie la disponibilite d'un username
     */
    @GetMapping("/check-username")
    public ResponseEntity<java.util.Map<String, Object>> checkUsernameAvailability(
            @RequestParam("username") String username,
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader) {

        if (username == null || username.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "Username cannot be empty"));
        }

        String trimmedUsername = username.trim();

        // Validation de la longueur (3-20 caracteres)
        if (trimmedUsername.length() < 3 || trimmedUsername.length() > 20) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "Username must be between 3 and 20 characters"));
        }
        
        // Validation du format (alphanumerique, tirets, underscores uniquement)
        if (!trimmedUsername.matches("^[a-zA-Z0-9_-]+$")) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "Username can only contain letters, numbers, hyphens, and underscores"));
        }
        
        // Verifier si le username est dans la liste des noms reserves
        if (ReservedUsernames.isReserved(trimmedUsername)) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "This username is reserved and cannot be used"));
        }
        
        Optional<User> existingUser = userService.findByUsername(trimmedUsername);

        boolean available = true;
        if (existingUser.isPresent()) {
            // Si on a un userIdHeader, verifier si c'est le meme utilisateur
            if (userIdHeader != null) {
                try {
                    Long currentUserId = Long.parseLong(userIdHeader);
                    available = existingUser.get().getId().equals(currentUserId);
                } catch (NumberFormatException e) {
                    available = false;
                }
            } else {
                available = false;
            }
        }

        java.util.Map<String, Object> response = new java.util.HashMap<>();
        response.put("username", trimmedUsername);
        response.put("available", available);
        response.put("message", available ? "Username is available" : "This username is already taken");

        return ResponseEntity.ok(response);
    }

    /**
     * Recupere la liste des noms d'utilisateur reserves
     */
    @GetMapping("/reserved-usernames")
    public ResponseEntity<java.util.Map<String, Object>> getReservedUsernames() {
        java.util.Map<String, Object> response = new java.util.HashMap<>();
        response.put("reservedUsernames", ReservedUsernames.getReservedUsernames());
        response.put("count", ReservedUsernames.getReservedUsernamesCount());
        response.put("message", "List of reserved usernames");

        return ResponseEntity.ok(response);
    }

    /**
     * Returns the display name change status (cooldown info).
     */
    @GetMapping("/display-name-status")
    public ResponseEntity<?> getDisplayNameStatus(
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader) {

        Optional<User> userOpt = resolveUser(userIdHeader);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(401).body(java.util.Map.of("error", "User not authenticated"));
        }

        java.util.Map<String, Object> status = userService.getDisplayNameStatus(userOpt.get());
        return ResponseEntity.ok(status);
    }

    /**
     * Returns the @handle change status (cooldown info), mirroring display-name-status.
     */
    @GetMapping("/handle-status")
    public ResponseEntity<?> getHandleStatus(
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader) {

        Optional<User> userOpt = resolveUser(userIdHeader);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(401).body(java.util.Map.of("error", "User not authenticated"));
        }

        java.util.Map<String, Object> status = userService.getHandleStatus(userOpt.get());
        return ResponseEntity.ok(status);
    }

    /**
     * Check if a display name is available (excludes current user).
     */
    @GetMapping("/check-display-name")
    public ResponseEntity<?> checkDisplayName(
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader,
            @RequestParam String displayName) {

        Optional<User> userOpt = resolveUser(userIdHeader);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(401).body(java.util.Map.of("error", "User not authenticated"));
        }

        boolean available = onboardingService.isDisplayNameAvailableForUser(displayName, userOpt.get().getId());
        return ResponseEntity.ok(java.util.Map.of(
                "displayName", displayName,
                "available", available
        ));
    }

    // ===== AVATAR ENDPOINTS =====

    /**
     * Upload avatar image for the authenticated user.
     * Stores binary permanently in storage.storage table.
     */
    @PostMapping(value = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadAvatar(
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader) {

        Optional<User> userOpt = resolveUser(userIdHeader);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(401).body(java.util.Map.of("error", "User not authenticated"));
        }

        try {
            String storageId = userService.uploadAvatar(
                    userOpt.get(),
                    file.getBytes(),
                    file.getContentType(),
                    file.getOriginalFilename()
            );

            return ResponseEntity.ok(java.util.Map.of(
                    "storageId", storageId,
                    "avatarUrl", "/api/users/" + userOpt.get().getId() + "/avatar"
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(java.util.Map.of("error", "Failed to upload avatar"));
        }
    }

    /**
     * Serve avatar image for a user. Public endpoint (no auth required).
     *
     * Resolution order:
     *  1. Uploaded avatar bytes from {@code storage.storage} (covers OAuth-imported
     *     and Settings-uploaded photos).
     *  2. Else: deterministic Gmail-style "initials + color" SVG generated from
     *     the user's name/email. If the userId does not resolve to an actual user,
     *     a {@code "?"}-initial SVG with a color seeded on the id is returned so
     *     this endpoint cannot be used as a user-existence oracle.
     *
     * Cache: {@code no-cache} (cacheable but always revalidated) + a short ETag
     * derived from the name/avatar fields. The browser sends {@code If-None-Match}
     * on every use → a cheap 304 when unchanged, fresh bytes the instant a rename or
     * uploaded-photo change flips the ETag. (A bare {@code max-age} pinned a stale
     * avatar for up to a day because the conditional request was never sent.)
     *
     * This endpoint NEVER returns 404 - keeps the frontend code-path single.
     */
    @GetMapping("/{userId}/avatar")
    public ResponseEntity<byte[]> getAvatar(
            @PathVariable Long userId,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {

        Optional<StorageEntity> entityOpt = userService.getAvatarEntity(userId);
        Optional<User> userOpt = userService.findById(userId);

        String eTag = computeAvatarETag(userId, userOpt.orElse(null), entityOpt.orElse(null));
        if (eTag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(eTag)
                    .cacheControl(CacheControl.noCache())
                    .build();
        }

        if (entityOpt.isPresent() && entityOpt.get().getDataBinary() != null) {
            StorageEntity entity = entityOpt.get();
            byte[] data = entity.getDataBinary();

            // Guard: old stored SVGs may have mime=null or "image/jpeg" - detect and
            // correct so the browser can render them (SVG served as JPEG breaks <img>).
            String mimeType = entity.getMimeType();
            if (mimeType == null || !mimeType.startsWith("image/svg")) {
                if (looksLikeSvg(data)) {
                    mimeType = "image/svg+xml";
                } else if (mimeType == null) {
                    mimeType = "image/jpeg";
                }
            }

            return ResponseEntity.ok()
                    .eTag(eTag)
                    .contentType(MediaType.parseMediaType(mimeType))
                    // Mutable resource on a stable URL: this avatar changes when the user
                    // re-uploads a photo. no-cache keeps the response cacheable but forces
                    // ETag revalidation on every use (cheap 304 when unchanged), so a new
                    // avatar shows immediately instead of being pinned for a day by a bare
                    // max-age (which suppresses the conditional request entirely).
                    .cacheControl(CacheControl.noCache())
                    .body(data);
        }

        byte[] svg;
        if (userOpt.isPresent()) {
            User u = userOpt.get();
            svg = InitialsAvatarGenerator.generateSvg(
                    u.getFirstName(), u.getLastName(), u.getUsername(), u.getEmail());
        } else {
            // Unknown userId - return "?" initials + a per-id color so the response
            // shape is indistinguishable from a real user without a name.
            svg = InitialsAvatarGenerator.generateUnknownSvg("uid:" + userId);
        }
        return ResponseEntity.ok()
                .eTag(eTag)
                .contentType(MediaType.parseMediaType("image/svg+xml"))
                // Same as the photo branch: the initials SVG mutates (name change, or this
                // URL flips from SVG to a photo on first upload) - revalidate via the ETag,
                // never pin for a day.
                .cacheControl(CacheControl.noCache())
                .body(svg);
    }

    /**
     * ETag is a short SHA-256 prefix over the identity inputs the avatar depends on.
     * Stable across calls; changes the instant a name or uploaded photo changes.
     */
    private static String computeAvatarETag(Long userId, User user, StorageEntity entity) {
        StringBuilder seed = new StringBuilder();
        seed.append(userId);
        if (entity != null && entity.getId() != null) {
            seed.append('|').append(entity.getId());
        } else {
            seed.append("|nostorage");
        }
        if (user != null) {
            seed.append('|').append(user.getFirstName())
                .append('|').append(user.getLastName())
                .append('|').append(user.getUsername())
                .append('|').append(user.getEmail());
        } else {
            seed.append("|nouser");
        }
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(seed.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("\"");
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", h[i]));
            sb.append('"');
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            return "\"" + Integer.toHexString(seed.toString().hashCode()) + "\"";
        }
    }

    /**
     * Import the user's OAuth provider avatar (Google/GitHub) server-side.
     * Downloads the external image and stores it in the avatar gallery.
     */
    @PostMapping("/avatar/import-provider")
    public ResponseEntity<?> importProviderAvatar(
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader) {

        Optional<User> userOpt = resolveUser(userIdHeader);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(401).body(java.util.Map.of("error", "User not authenticated"));
        }

        Optional<String> storageId = userService.importProviderAvatar(userOpt.get());
        if (storageId.isEmpty()) {
            return ResponseEntity.ok(java.util.Map.of("imported", false, "message", "No provider avatar to import"));
        }

        return ResponseEntity.ok(java.util.Map.of(
                "imported", true,
                "storageId", storageId.get(),
                "avatarUrl", "/api/users/" + userOpt.get().getId() + "/avatar"
        ));
    }

    // ===== AVATAR GALLERY ENDPOINTS =====

    /**
     * List all avatars for the authenticated user (gallery).
     */
    @GetMapping("/avatars")
    public ResponseEntity<List<AvatarInfo>> listAvatars(
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader) {

        Optional<User> userOpt = resolveUser(userIdHeader);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(401).build();
        }

        List<AvatarInfo> avatars = userService.listAvatars(userOpt.get());
        return ResponseEntity.ok(avatars);
    }

    /**
     * Select an avatar from the gallery as active.
     */
    @PutMapping("/avatars/{storageId}/select")
    public ResponseEntity<?> selectAvatar(
            @PathVariable String storageId,
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader) {

        Optional<User> userOpt = resolveUser(userIdHeader);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(401).body(java.util.Map.of("error", "User not authenticated"));
        }

        try {
            userService.selectAvatar(userOpt.get(), storageId);
            return ResponseEntity.ok(java.util.Map.of(
                    "message", "Avatar selected",
                    "storageId", storageId
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", e.getMessage()));
        }
    }

    /**
     * Delete an avatar from the gallery.
     */
    @DeleteMapping("/avatars/{storageId}")
    public ResponseEntity<?> deleteAvatar(
            @PathVariable String storageId,
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader) {

        Optional<User> userOpt = resolveUser(userIdHeader);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(401).body(java.util.Map.of("error", "User not authenticated"));
        }

        try {
            userService.deleteAvatar(userOpt.get(), storageId);
            return ResponseEntity.ok(java.util.Map.of("message", "Avatar deleted"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", e.getMessage()));
        }
    }

    /**
     * Serve a specific avatar image by storage ID. Public endpoint (no auth required).
     */
    @GetMapping("/{userId}/avatar/{storageId}")
    public ResponseEntity<byte[]> getAvatarById(@PathVariable Long userId, @PathVariable String storageId) {
        try {
            UUID uuid = UUID.fromString(storageId);
            String tenantId = String.valueOf(userId);
            Optional<StorageEntity> entityOpt = userService.getAvatarEntityById(uuid, tenantId);
            if (entityOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            StorageEntity entity = entityOpt.get();
            byte[] data = entity.getDataBinary();
            if (data == null) {
                return ResponseEntity.notFound().build();
            }

            String mimeType = entity.getMimeType() != null ? entity.getMimeType() : "image/jpeg";

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(mimeType))
                    .cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS))
                    .body(data);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    // ===== PRIVATE UTILITY METHODS =====

    /**
     * Resolves user from X-User-ID header (gateway) or SecurityContext fallback.
     */
    private Optional<User> resolveUser(String userIdHeader) {
        if (userIdHeader != null) {
            try {
                Long userId = Long.parseLong(userIdHeader);
                return userService.findById(userId);
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
        return getCurrentUser();
    }

    /** Quick heuristic: starts with '&lt;svg' or '&lt;?xml'. */
    private static boolean looksLikeSvg(byte[] data) {
        if (data == null || data.length < 4) return false;
        String head = new String(data, 0, Math.min(data.length, 64), java.nio.charset.StandardCharsets.UTF_8).trim();
        return head.startsWith("<svg") || head.startsWith("<?xml");
    }

    private static boolean isStorageBackedAvatar(String avatarUrl) {
        if (avatarUrl == null) return false;
        try {
            java.util.UUID.fromString(avatarUrl);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Recupere l'utilisateur connecte actuellement
     */
    private Optional<User> getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }

        String username = authentication.getName();
        return userService.findByUsername(username);
    }
}

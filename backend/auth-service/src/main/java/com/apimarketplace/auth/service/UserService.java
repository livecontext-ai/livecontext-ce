package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.domain.UserOnboarding;
import com.apimarketplace.auth.domain.UserProfileEntity;
import com.apimarketplace.auth.dto.AvatarInfo;
import com.apimarketplace.auth.dto.PublicProfileDto;
import com.apimarketplace.auth.dto.UserProfile;
import com.apimarketplace.auth.dto.UserProfileUpdateRequest;
import com.apimarketplace.auth.repository.UserOnboardingRepository;
import com.apimarketplace.auth.repository.UserProfileRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.validation.AgeValidator;
import com.apimarketplace.auth.validation.UsernameValidator;
import com.apimarketplace.common.storage.domain.StorageEntity;
import com.apimarketplace.common.storage.service.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Service pour la gestion des utilisateurs.
 * Utilise les validateurs injectes pour appliquer les regles metier (SRP).
 */
@Service
@Transactional
public class UserService {

    private static final Logger logger = LoggerFactory.getLogger(UserService.class);
    private static final Set<String> ALLOWED_AVATAR_TYPES = Set.of(
            "image/jpeg", "image/jpg", "image/png", "image/gif", "image/webp"
    );
    private static final int MAX_AVATAR_SIZE = 5 * 1024 * 1024; // 5MB
    private static final int MAX_AVATARS = 10;

    private final UserRepository userRepository;
    private final UserOnboardingRepository onboardingRepository;
    private final UserProfileRepository userProfileRepository;
    private final UsernameValidator usernameValidator;
    private final AgeValidator ageValidator;
    private final StorageService storageService;
    private final AccountDeactivationMailer deactivationMailer;

    public UserService(UserRepository userRepository,
                       UserOnboardingRepository onboardingRepository,
                       UserProfileRepository userProfileRepository,
                       UsernameValidator usernameValidator,
                       AgeValidator ageValidator,
                       StorageService storageService,
                       AccountDeactivationMailer deactivationMailer) {
        this.userRepository = userRepository;
        this.onboardingRepository = onboardingRepository;
        this.userProfileRepository = userProfileRepository;
        this.usernameValidator = usernameValidator;
        this.ageValidator = ageValidator;
        this.storageService = storageService;
        this.deactivationMailer = deactivationMailer;
    }

    /**
     * Recupere un utilisateur par son username
     */
    public Optional<User> findByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    /**
     * Recupere un utilisateur par son ID
     */
    public Optional<User> findById(Long id) {
        return userRepository.findById(id);
    }

    /**
     * Sauvegarde un utilisateur
     */
    public User save(User user) {
        return userRepository.save(user);
    }

    /**
     * Met a jour le profil d'un utilisateur.
     * Utilise les validateurs injectes pour une meilleure separation des responsabilites.
     */
    public User updateProfile(User user, UserProfileUpdateRequest request) {
        updateBasicInfo(user, request);
        updateUsername(user, request);
        updateAge(user, request);
        updateProfileData(user, request);
        updateDisplayName(user, request);
        updatePublicProfile(user, request);

        return userRepository.save(user);
    }

    // ===== In-app profile (bio / visibility) =====

    /**
     * Updates the public-profile fields, creating the {@code user_profiles} row
     * on first edit. Fully defensive - normalises/sanitises every input and never
     * throws on odd values (so a malformed URL or stray link can't 500 the whole
     * profile save). A field left {@code null} on the request is left untouched.
     */
    private void updatePublicProfile(User user, UserProfileUpdateRequest request) {
        boolean touched = request.getBio() != null
                || request.getProfileVisibility() != null
                || request.getHandle() != null;
        if (!touched) {
            return;
        }

        UserProfileEntity profile = userProfileRepository.findByUserId(user.getId())
                .orElseGet(() -> new UserProfileEntity(user.getId()));

        if (request.getHandle() != null) {
            // Slugify what the user typed, then accept it only if it's a well-formed handle and
            // free (or already theirs). A taken/invalid handle is ignored - the current one is
            // kept - so a bad value never 500s the whole profile save.
            String h = usernameValidator.normalize(request.getHandle());
            if (h != null && HANDLE_PATTERN.matcher(h).matches()) {
                Optional<UserProfileEntity> taken = userProfileRepository.findByHandle(h);
                if ((taken.isEmpty() || taken.get().getUserId().equals(user.getId()))
                        && !h.equals(profile.getHandle())) {
                    // Same 1-change-per-week rule as the display name. Only an ACTUAL change
                    // consumes it - re-sending the current handle (the settings card sends all
                    // fields on every save) and lazy generation never start the cooldown.
                    if (profile.getHandleChangedAt() != null) {
                        long daysSinceLastChange =
                                ChronoUnit.DAYS.between(profile.getHandleChangedAt(), LocalDateTime.now());
                        if (daysSinceLastChange < HANDLE_COOLDOWN_DAYS) {
                            throw new IllegalStateException("Handle can only be changed once per week");
                        }
                    }
                    profile.setHandle(h);
                    profile.setHandleChangedAt(LocalDateTime.now());
                }
            }
        }
        if (request.getBio() != null) {
            String bio = request.getBio().trim();
            profile.setBio(bio.isEmpty() ? null : clamp(bio, 500));
        }
        if (request.getProfileVisibility() != null) {
            String v = request.getProfileVisibility().trim().toUpperCase();
            if (UserProfileEntity.VISIBILITY_PUBLIC.equals(v) || UserProfileEntity.VISIBILITY_PRIVATE.equals(v)) {
                profile.setProfileVisibility(v);
            }
        }
        userProfileRepository.save(profile);
    }

    private static String clamp(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static final int HANDLE_MAX = 32;
    /** Same 1-change-per-week rule as {@code DISPLAY_NAME_COOLDOWN_DAYS}. */
    private static final int HANDLE_COOLDOWN_DAYS = 7;
    private static final java.util.regex.Pattern HANDLE_PATTERN =
            java.util.regex.Pattern.compile("^[a-z0-9._-]{2,32}$");

    /**
     * Returns the @handle change status (cooldown info), mirroring
     * {@link #getDisplayNameStatus(User)}: {@code canChange} + {@code nextChangeDate}.
     */
    @Transactional(readOnly = true)
    public java.util.Map<String, Object> getHandleStatus(User user) {
        java.util.Map<String, Object> result = new java.util.HashMap<>();

        Optional<UserProfileEntity> profileOpt = userProfileRepository.findByUserId(user.getId());
        if (profileOpt.isEmpty() || profileOpt.get().getHandleChangedAt() == null) {
            result.put("canChange", true);
            result.put("nextChangeDate", null);
            return result;
        }

        LocalDateTime nextChangeDate = profileOpt.get().getHandleChangedAt().plusDays(HANDLE_COOLDOWN_DAYS);
        boolean canChange = LocalDateTime.now().isAfter(nextChangeDate);

        result.put("canChange", canChange);
        result.put("nextChangeDate", canChange ? null : nextChangeDate.toString());
        return result;
    }

    /**
     * Ensure this profile carries a public @handle, lazily generating a unique URL-safe slug
     * from the given base (the display name) the first time it is needed and persisting it.
     * Idempotent: a handle that is already set is returned unchanged.
     */
    private String ensureHandle(UserProfileEntity profile, String base) {
        if (profile.getHandle() != null && !profile.getHandle().isBlank()) {
            return profile.getHandle();
        }
        profile.setHandle(generateUniqueHandle(base));
        userProfileRepository.save(profile);
        return profile.getHandle();
    }

    /**
     * Slugify the base (display name) via {@link UsernameValidator#normalize} and append a
     * numeric suffix until the handle is unique among profiles. Never the raw OAuth account
     * username - only the chosen display name feeds this.
     */
    private String generateUniqueHandle(String base) {
        String normalized = usernameValidator.normalize(base);
        if (normalized == null || normalized.isBlank() || "_".equals(normalized)) {
            normalized = "user";
        }
        if (normalized.length() > HANDLE_MAX) {
            normalized = normalized.substring(0, HANDLE_MAX);
        }
        String candidate = normalized;
        int counter = 1;
        while (userProfileRepository.existsByHandle(candidate)) {
            String suffix = "_" + counter++;
            String head = normalized.length() + suffix.length() > HANDLE_MAX
                    ? normalized.substring(0, HANDLE_MAX - suffix.length())
                    : normalized;
            candidate = head + suffix;
            if (counter > 50) {
                candidate = head + "_" + System.nanoTime();
                break;
            }
        }
        return candidate;
    }

    /** Resolve a user by their public @handle, for the {@code /app/u/{handle}} profile lookup. */
    @Transactional(readOnly = true)
    public Optional<User> findByHandle(String handle) {
        if (handle == null || handle.isBlank()) {
            return Optional.empty();
        }
        return userProfileRepository.findByHandle(handle.trim().toLowerCase(java.util.Locale.ROOT))
                .flatMap(p -> userRepository.findById(p.getUserId()));
    }

    /**
     * Builds the public profile for a user, or empty when it must not be shown:
     * the profile is PRIVATE, or the account is disabled/deactivated. The public
     * controller maps empty → 404. No email / roles / onboarding internals leak.
     */
    // Writable (not readOnly): the public @handle is generated lazily + persisted on first view.
    @Transactional
    public Optional<PublicProfileDto> getPublicProfile(User user) {
        if (!user.isEnabled()) {
            return Optional.empty();
        }
        Optional<UserProfileEntity> profileOpt = userProfileRepository.findByUserId(user.getId());
        if (profileOpt.isPresent() && !profileOpt.get().isPublic()) {
            return Optional.empty();
        }

        Optional<UserOnboarding> onboardingOpt = onboardingRepository.findByUserId(user.getId());
        String displayName = onboardingOpt.map(UserOnboarding::getDisplayName).orElse(null);

        // Mirror mapToUserProfile: a Keycloak auto-generated username (kc_xxxx)
        // is replaced by the display name for presentation.
        String effectiveUsername = user.getUsername();
        if (effectiveUsername != null && effectiveUsername.matches("^kc_[0-9a-fA-F]+$") && displayName != null) {
            effectiveUsername = displayName;
        }

        // Public name: the chosen display name, falling back to the (kc-swapped) handle only when
        // unset - never null, and the raw kc_* handle is already swapped to the display name above.
        String publicDisplayName = (displayName != null && !displayName.isBlank()) ? displayName : effectiveUsername;

        // Ensure a public @handle exists (lazily slugified from the display name) so the profile is
        // addressable by /app/u/{handle} instead of the numeric user/tenant id.
        UserProfileEntity p = profileOpt.orElseGet(() -> new UserProfileEntity(user.getId()));
        String handle = ensureHandle(p, publicDisplayName);
        return Optional.of(new PublicProfileDto(
                user.getId(),
                publicDisplayName,
                handle,
                storageBackedAvatarUrl(user),
                p.getBio(),
                user.getCreatedAt()
        ));
    }

    /**
     * The avatar URL the frontend should render, or null when the user's avatar
     * is not storage-backed (e.g. a raw OAuth provider URL). Shared by
     * {@link #mapToUserProfile} and {@link #getPublicProfile}.
     */
    private String storageBackedAvatarUrl(User user) {
        if (user.getAvatarUrl() == null) {
            return null;
        }
        try {
            UUID.fromString(user.getAvatarUrl());
            return "/api/users/" + user.getId() + "/avatar";
        } catch (IllegalArgumentException ignored) {
            return null; // HTTP URL from OAuth - not storage-backed
        }
    }

    private static final int DISPLAY_NAME_COOLDOWN_DAYS = 7;

    /**
     * Updates display name with a 1-change-per-week rate limit.
     * @throws IllegalStateException if the cooldown period has not elapsed
     */
    private void updateDisplayName(User user, UserProfileUpdateRequest request) {
        if (request.getDisplayName() == null) {
            return;
        }

        String newName = request.getDisplayName().trim();
        if (newName.isEmpty()) {
            return;
        }

        Optional<UserOnboarding> onboardingOpt = onboardingRepository.findByUserId(user.getId());
        if (onboardingOpt.isEmpty()) {
            return;
        }

        UserOnboarding onboarding = onboardingOpt.get();

        // Skip if same name
        if (newName.equals(onboarding.getDisplayName())) {
            return;
        }

        // Check cooldown
        if (onboarding.getDisplayNameChangedAt() != null) {
            long daysSinceLastChange = ChronoUnit.DAYS.between(onboarding.getDisplayNameChangedAt(), LocalDateTime.now());
            if (daysSinceLastChange < DISPLAY_NAME_COOLDOWN_DAYS) {
                throw new IllegalStateException("Display name can only be changed once per week");
            }
        }

        onboarding.setDisplayName(newName);
        onboarding.setDisplayNameChangedAt(LocalDateTime.now());
        onboardingRepository.save(onboarding);
    }

    private void updateBasicInfo(User user, UserProfileUpdateRequest request) {
        if (request.getFirstName() != null) {
            user.setFirstName(request.getFirstName());
        }
        if (request.getLastName() != null) {
            user.setLastName(request.getLastName());
        }
        if (request.getAvatarUrl() != null) {
            user.setAvatarUrl(request.getAvatarUrl());
        }
    }

    private void updateUsername(User user, UserProfileUpdateRequest request) {
        if (request.getUsername() == null || request.getUsername().isBlank()) {
            return;
        }

        String newUsername = request.getUsername().trim();

        // Pas de changement si meme username
        if (newUsername.equals(user.getUsername())) {
            return;
        }

        // Validation via le composant dedie
        usernameValidator.validate(newUsername, user.getId())
                .ifPresent(error -> { throw new IllegalArgumentException(error); });

        user.setUsername(newUsername);
    }

    private void updateAge(User user, UserProfileUpdateRequest request) {
        if (request.getAge() == null) {
            return;
        }

        // Validation via le composant dedie
        ageValidator.validate(request.getAge())
                .ifPresent(error -> { throw new IllegalArgumentException(error); });

        user.setAge(request.getAge());
    }

    private void updateProfileData(User user, UserProfileUpdateRequest request) {
        if (request.getEmail() != null && !request.getEmail().trim().isEmpty()) {
            user.setEmail(request.getEmail().trim());
        }

        if (request.getPicture() != null && !request.getPicture().trim().isEmpty()) {
            user.setAvatarUrl(request.getPicture().trim());
        }

        if (request.getGivenName() != null && !request.getGivenName().trim().isEmpty()) {
            user.setFirstName(request.getGivenName().trim());
        }

        if (request.getFamilyName() != null && !request.getFamilyName().trim().isEmpty()) {
            user.setLastName(request.getFamilyName().trim());
        }

        if (request.getEmailVerified() != null) {
            user.setEmailVerified(request.getEmailVerified());
        }
    }

    /**
     * Uploads an avatar image for the user (gallery mode - preserves old avatars).
     * Stores binary in storage.storage table with no expiry (permanent).
     * Sets the newly uploaded avatar as active. Max 10 avatars per user.
     *
     * @return the storageId UUID string
     */
    public String uploadAvatar(User user, byte[] data, String mimeType, String fileName) {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Avatar file is empty");
        }
        if (data.length > MAX_AVATAR_SIZE) {
            throw new IllegalArgumentException("Avatar exceeds maximum size of 5MB");
        }
        if (!ALLOWED_AVATAR_TYPES.contains(mimeType)) {
            throw new IllegalArgumentException("Unsupported image type: " + mimeType);
        }

        String tenantId = String.valueOf(user.getId());

        // Check gallery limit
        List<StorageEntity> existing = storageService.listByTenantAndSourceType(tenantId, "USER_AVATAR");
        if (existing.size() >= MAX_AVATARS) {
            throw new IllegalArgumentException("Maximum number of avatars reached (" + MAX_AVATARS + ")");
        }

        // Store with no expiry (null) - permanent
        UUID storageId = storageService.saveBinary(tenantId, data, fileName, mimeType, null, "USER_AVATAR");

        // Only set as active if user has no avatar yet (first upload)
        // Otherwise, user explicitly selects via selectAvatar()
        if (user.getAvatarUrl() == null) {
            user.setAvatarUrl(storageId.toString());
            userRepository.save(user);
        }

        logger.info("Avatar uploaded for user {}: storageId={}, size={} bytes", user.getId(), storageId, data.length);
        return storageId.toString();
    }

    /**
     * Downloads the user's OAuth provider avatar and stores it in the gallery.
     * The provider URL is taken from user.avatarUrl (set during OAuth login).
     * After import, user.avatarUrl is updated to the storage UUID.
     *
     * <p>Audit-C round-12 fix (2026-05-20): post-V263 + fail-loud listener, this
     * method MUST NOT be called from a thread that has no organization binding.
     * The OAuth signup callback (OAuthUserProcessor.upsertUser) runs on Spring
     * Security's user-loading thread, bypasses the gateway, has no X-Organization-ID
     * header → just-signed-up user has no org yet. Before round-12 the listener
     * silently left org NULL and the DB then rejected the insert with an ERROR
     * log per OAuth signup (avatar dropped). Now we skip cleanly with an INFO log
     * when no org context is bindable; users can re-import via REST after login
     * (UserController.importProviderAvatar handles a request-thread call).
     *
     * @return the storageId UUID string, or empty if no provider avatar, already
     *         imported, or no org context bindable at call time
     */
    public Optional<String> importProviderAvatar(User user) {
        String providerUrl = user.getAvatarUrl();
        if (providerUrl == null || providerUrl.isBlank()) {
            return Optional.empty();
        }

        // Audit-C #1 - defer when no org context is bindable (OAuth signup pre-org).
        String currentOrg = com.apimarketplace.common.web.TenantResolver.currentRequestOrganizationId();
        if (currentOrg == null || currentOrg.isBlank()) {
            logger.info("Skipping provider avatar import for user {} - no org context (likely OAuth signup pre-org-provision). User can re-import via /api/users/avatar/import-provider once logged in with an active workspace.",
                    user.getId());
            return Optional.empty();
        }

        // If avatarUrl is already a UUID (storage), avatar was already imported
        try {
            UUID.fromString(providerUrl);
            return Optional.empty();
        } catch (IllegalArgumentException ignored) {
            // Not a UUID - it's an external URL, proceed with import
        }

        // Check if user already has stored avatars
        String tenantId = String.valueOf(user.getId());
        List<StorageEntity> existing = storageService.listByTenantAndSourceType(tenantId, "USER_AVATAR");
        if (!existing.isEmpty()) {
            // Self-heal: avatarUrl is an HTTP URL but storage has photos - restore the link
            StorageEntity latest = existing.get(0);
            user.setAvatarUrl(latest.getId().toString());
            userRepository.save(user);
            logger.info("Self-healed avatarUrl for user {}: restored UUID {} (was HTTP URL in importProviderAvatar)", user.getId(), latest.getId());
            return Optional.of(latest.getId().toString());
        }

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(providerUrl))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                logger.warn("Failed to download provider avatar for user {}: HTTP {}", user.getId(), response.statusCode());
                return Optional.empty();
            }

            byte[] data = response.body();
            String contentType = response.headers().firstValue("Content-Type").orElse("image/jpeg");
            // Normalize content type
            if (contentType.contains(";")) {
                contentType = contentType.substring(0, contentType.indexOf(";")).trim();
            }
            if (!ALLOWED_AVATAR_TYPES.contains(contentType)) {
                contentType = "image/jpeg";
            }

            String ext = contentType.contains("png") ? "avatar.png" : "avatar.jpg";

            UUID storageId = storageService.saveBinary(tenantId, data, ext, contentType, null, "USER_AVATAR");

            // Set as active avatar
            user.setAvatarUrl(storageId.toString());
            userRepository.save(user);

            logger.info("Provider avatar imported for user {}: storageId={}, size={} bytes", user.getId(), storageId, data.length);
            return Optional.of(storageId.toString());
        } catch (Exception e) {
            logger.warn("Failed to import provider avatar for user {}: {}", user.getId(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Gets the avatar storage entity for a user.
     * Returns the StorageEntity with binary data, or empty if no avatar.
     */
    public Optional<StorageEntity> getAvatarEntity(Long userId) {
        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty() || userOpt.get().getAvatarUrl() == null) {
            return Optional.empty();
        }

        String tenantId = String.valueOf(userId);
        String avatarUrl = userOpt.get().getAvatarUrl();

        // Happy path: avatarUrl is a storage UUID
        try {
            UUID storageId = UUID.fromString(avatarUrl);
            return storageService.getEntityById(storageId, tenantId);
        } catch (IllegalArgumentException e) {
            // avatarUrl is an HTTP URL (OAuth provider), not a storage UUID.
            // This happens when a re-login overwrote the UUID before the fix
            // in OAuthUserProcessor. Self-heal by finding existing stored avatars
            // and restoring the link.
        }

        // Self-heal: avatarUrl is an HTTP URL but user may have stored avatars
        List<StorageEntity> stored = storageService.listByTenantAndSourceType(tenantId, "USER_AVATAR");
        if (!stored.isEmpty()) {
            // Pick the most recent stored avatar and restore the link
            StorageEntity latest = stored.get(0);
            User user = userOpt.get();
            user.setAvatarUrl(latest.getId().toString());
            userRepository.save(user);
            logger.info("Self-healed avatarUrl for user {}: restored UUID {} (was HTTP URL)", userId, latest.getId());
            return Optional.of(latest);
        }

        return Optional.empty();
    }

    /**
     * Lists all avatars for a user (gallery).
     */
    @Transactional(readOnly = true)
    public List<AvatarInfo> listAvatars(User user) {
        String tenantId = String.valueOf(user.getId());
        List<StorageEntity> entities = storageService.listByTenantAndSourceType(tenantId, "USER_AVATAR");
        String activeId = user.getAvatarUrl();
        return entities.stream()
                .map(e -> new AvatarInfo(
                        e.getId().toString(),
                        "/api/users/" + user.getId() + "/avatar/" + e.getId(),
                        e.getMimeType(),
                        e.getCreatedAt(),
                        e.getId().toString().equals(activeId)
                )).toList();
    }

    /**
     * Selects an avatar from the gallery as the active avatar.
     */
    public void selectAvatar(User user, String storageId) {
        String tenantId = String.valueOf(user.getId());
        UUID uuid = UUID.fromString(storageId);
        storageService.getEntityById(uuid, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Avatar not found"));
        user.setAvatarUrl(storageId);
        userRepository.save(user);
    }

    /**
     * Deletes an avatar from the gallery. If the deleted avatar was active, selects the next one.
     */
    public void deleteAvatar(User user, String storageId) {
        String tenantId = String.valueOf(user.getId());
        UUID uuid = UUID.fromString(storageId);
        if (!storageService.deleteById(uuid, tenantId)) {
            throw new IllegalArgumentException("Avatar not found");
        }
        // If deleted avatar was active, select next or clear
        if (storageId.equals(user.getAvatarUrl())) {
            List<StorageEntity> remaining = storageService.listByTenantAndSourceType(tenantId, "USER_AVATAR");
            user.setAvatarUrl(remaining.isEmpty() ? null : remaining.get(0).getId().toString());
            userRepository.save(user);
        }
    }

    /**
     * Gets a specific avatar storage entity by its storage ID.
     */
    public Optional<StorageEntity> getAvatarEntityById(UUID storageId, String tenantId) {
        return storageService.getEntityById(storageId, tenantId);
    }

    /**
     * Returns the display name change status for a user.
     */
    @Transactional(readOnly = true)
    public java.util.Map<String, Object> getDisplayNameStatus(User user) {
        java.util.Map<String, Object> result = new java.util.HashMap<>();

        Optional<UserOnboarding> onboardingOpt = onboardingRepository.findByUserId(user.getId());
        if (onboardingOpt.isEmpty() || onboardingOpt.get().getDisplayNameChangedAt() == null) {
            result.put("canChange", true);
            result.put("nextChangeDate", null);
            return result;
        }

        LocalDateTime lastChanged = onboardingOpt.get().getDisplayNameChangedAt();
        LocalDateTime nextChangeDate = lastChanged.plusDays(DISPLAY_NAME_COOLDOWN_DAYS);
        boolean canChange = LocalDateTime.now().isAfter(nextChangeDate);

        result.put("canChange", canChange);
        result.put("nextChangeDate", canChange ? null : nextChangeDate.toString());
        return result;
    }

    /**
     * Deactivates a user account with a 30-day grace period before hard-delete.
     * Sets enabled=false + deactivatedAt=now, then sends a confirmation email.
     * The AccountPurgeScheduler will hard-delete all data after 30 days.
     */
    public User deactivateUser(User user) {
        user.setEnabled(false);
        user.setDeactivatedAt(LocalDateTime.now());
        User saved = userRepository.save(user);

        String displayName = onboardingRepository.findByUserId(user.getId())
                .map(UserOnboarding::getDisplayName)
                .orElse(user.getFirstName());
        deactivationMailer.sendDeactivationEmail(user.getEmail(), displayName);

        logger.info("Account deactivated for user {} ({}), 30-day grace period started",
                user.getId(), user.getEmail());
        return saved;
    }

    /**
     * Mappe une entite User vers un DTO UserProfile
     */
    public UserProfile mapToUserProfile(User user) {
        // Get onboarding data
        Optional<UserOnboarding> onboardingOpt = onboardingRepository.findByUserId(user.getId());
        String displayName = onboardingOpt.map(UserOnboarding::getDisplayName).orElse(null);
        LocalDateTime displayNameChangedAt = onboardingOpt.map(UserOnboarding::getDisplayNameChangedAt).orElse(null);

        // Use displayName as username if current username is a Keycloak auto-generated ID (e.g. "kc_2d4cc1b2")
        String effectiveUsername = user.getUsername();
        if (effectiveUsername != null && effectiveUsername.matches("^kc_[0-9a-fA-F]+$") && displayName != null) {
            effectiveUsername = displayName;
        }

        UserProfile profile = new UserProfile(
                user.getId(),
                effectiveUsername,
                displayName,
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                storageBackedAvatarUrl(user),
                user.getAuthProvider() != null ? user.getAuthProvider().getProvider() : null,
                user.isEmailVerified(),
                user.isEnabled(),
                user.getCreatedAt(),
                user.getLastLoginAt(),
                user.getAge(),
                displayNameChangedAt
        );

        // Public-profile fields so the account settings page reads them back. Ensure a public
        // @handle exists (lazily generated from the display name) so the settings page can link
        // to /app/u/{handle} and show @handle.
        String handleBase = (displayName != null && !displayName.isBlank()) ? displayName : effectiveUsername;
        UserProfileEntity profileEntity = userProfileRepository.findByUserId(user.getId())
                .orElseGet(() -> new UserProfileEntity(user.getId()));
        profile.setHandle(ensureHandle(profileEntity, handleBase));
        profile.setBio(profileEntity.getBio());
        profile.setProfileVisibility(profileEntity.getProfileVisibility());

        return profile;
    }
}

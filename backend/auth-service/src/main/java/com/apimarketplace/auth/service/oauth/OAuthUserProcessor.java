package com.apimarketplace.auth.service.oauth;

import com.apimarketplace.auth.audit.AuthEventRecorder;
import com.apimarketplace.auth.bootstrap.FirstAdminBootstrap;
import com.apimarketplace.auth.domain.AuthProvider;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Centralized processor for creating/updating OAuth users.
 * Eliminates duplication between OAuth2UserService and OidcUserService.
 * Applies DRY and SRP principles.
 */
@Component
public class OAuthUserProcessor {

    private static final Logger log = LoggerFactory.getLogger(OAuthUserProcessor.class);

    private final UserRepository userRepository;
    private final UserService userService;
    private final FirstAdminBootstrap firstAdminBootstrap;

    @Autowired(required = false)
    private AuthEventRecorder authEventRecorder;

    public OAuthUserProcessor(UserRepository userRepository,
                              @Lazy UserService userService,
                              FirstAdminBootstrap firstAdminBootstrap) {
        this.userRepository = userRepository;
        this.userService = userService;
        this.firstAdminBootstrap = firstAdminBootstrap;
    }

    /**
     * Creates or updates a user from a normalized OAuth profile.
     * @param profile The normalized OAuth profile
     * @return The created or updated user
     */
    @Transactional
    public User upsertUser(OAuthProfile profile) {
        Optional<User> existing = findExistingUser(profile);
        boolean isNew = existing.isEmpty();
        User user = existing.orElseGet(() -> createNewUser(profile));

        updateUserFromProfile(user, profile);

        User saved = userRepository.save(user);
        log.info("OAuth user upserted: id={}, provider={}, providerId={}, email={}",
                saved.getId(), profile.provider(), profile.providerId(), profile.email());

        // Server-side download of provider avatar into our storage. Without this,
        // the user would have to click "download" in the avatar gallery to persist
        // the OAuth picture. Failure here must NOT block signup/login - log & move on.
        if (saved.getAvatarUrl() != null && saved.getAvatarUrl().startsWith("http")) {
            try {
                userService.importProviderAvatar(saved);
            } catch (Exception e) {
                log.warn("Failed to import OAuth avatar for user {}: {}", saved.getId(), e.getMessage());
            }
        }

        // Metrics + signed audit (signup + login) - both go through the central
        // recorder so OAuth events get the same audit trail as Keycloak/embedded
        // logins. Provider tag is bounded enum.
        if (authEventRecorder != null) {
            String tag = authEventRecorder.providerTag(saved.getAuthProvider());
            if (isNew) {
                authEventRecorder.recordSignupAndLogin(saved.getId(), tag, false);
            } else {
                authEventRecorder.recordLoginSuccess(saved.getId(), tag);
            }
        }

        return saved;
    }

    private Optional<User> findExistingUser(OAuthProfile profile) {
        // First search by providerId
        Optional<User> byProviderId = userRepository.findByProviderId(profile.providerId());
        if (byProviderId.isPresent()) {
            return byProviderId;
        }

        // Otherwise link an existing account by email - but ONLY when it was
        // created by the SAME provider (handles a provider-side id change). Linking
        // by email ALONE would let one provider silently take over an account that
        // belongs to another sign-in method (cross-provider account merge).
        if (profile.email() != null) {
            return userRepository.findByEmailAndProvider(
                    profile.email(), resolveAuthProvider(profile.provider()));
        }

        return Optional.empty();
    }

    private User createNewUser(OAuthProfile profile) {
        User user = new User();
        user.setCreatedAt(LocalDateTime.now());
        user.setEnabled(true);
        // First OAuth user on the platform becomes ADMIN - but ONLY in CE, ONLY
        // before the install is bootstrapped, AND atomically w.r.t. parallel
        // registrants. See FirstAdminBootstrap for the contract; Cloud always
        // returns false here so DB-wipe scenarios cannot grant ADMIN.
        boolean isFirstAdmin = firstAdminBootstrap.claimFirstAdminSlot();
        user.setRoles(isFirstAdmin ? java.util.Set.of("USER", "ADMIN") : java.util.Set.of("USER"));
        // Do not set username automatically - left to the first login modal
        return user;
    }

    private void updateUserFromProfile(User user, OAuthProfile profile) {
        // Always update the provider and providerId
        user.setAuthProvider(resolveAuthProvider(profile.provider()));
        user.setProviderId(profile.providerId());

        // Update info if available
        if (profile.email() != null) {
            user.setEmail(profile.email());
        }
        user.setEmailVerified(profile.emailVerified());

        if (profile.firstName() != null) {
            user.setFirstName(profile.firstName());
        }
        if (profile.lastName() != null) {
            user.setLastName(profile.lastName());
        }
        if (profile.avatarUrl() != null) {
            // Only set the provider URL if the user doesn't already have a
            // storage-backed avatar (UUID). Without this guard, every OAuth
            // re-login overwrites the UUID with the provider HTTP URL, and
            // getAvatarEntity() then fails to parse it → falls back to SVG
            // initials even though the uploaded photo still exists in storage.
            String current = user.getAvatarUrl();
            boolean hasStorageAvatar = false;
            if (current != null) {
                try {
                    java.util.UUID.fromString(current);
                    hasStorageAvatar = true;
                } catch (IllegalArgumentException ignored) {
                    // Not a UUID - external URL or garbage, safe to overwrite
                }
            }
            if (!hasStorageAvatar) {
                user.setAvatarUrl(profile.avatarUrl());
            }
        }

        // If no first/last name but a displayName, attempt to extract
        if (user.getFirstName() == null && profile.displayName() != null) {
            extractNameFromDisplayName(user, profile.displayName());
        }

        user.setLastLoginAt(LocalDateTime.now());
    }

    private void extractNameFromDisplayName(User user, String displayName) {
        int lastSpace = displayName.lastIndexOf(' ');
        if (lastSpace > 0) {
            user.setFirstName(displayName.substring(0, lastSpace));
            user.setLastName(displayName.substring(lastSpace + 1));
        }
    }

    private AuthProvider resolveAuthProvider(String provider) {
        return switch (provider.toLowerCase()) {
            case "google" -> AuthProvider.GOOGLE;
            case "github" -> AuthProvider.GITHUB;
            default -> AuthProvider.KEYCLOAK;
        };
    }
}

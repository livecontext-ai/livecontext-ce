package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.domain.UserOnboarding;
import com.apimarketplace.auth.dto.OnboardingRequest;
import com.apimarketplace.auth.dto.OnboardingResponse;
import com.apimarketplace.auth.repository.UserOnboardingRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.common.auth.UserSummaryDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Service for managing user onboarding flow.
 */
@Service
@Transactional
public class OnboardingService {

    private static final Logger log = LoggerFactory.getLogger(OnboardingService.class);

    private final UserOnboardingRepository onboardingRepository;
    private final UserRepository userRepository;
    private final OrganizationService organizationService;
    private final GatewayCacheClient gatewayCacheClient;

    public OnboardingService(UserOnboardingRepository onboardingRepository,
                             UserRepository userRepository,
                             OrganizationService organizationService,
                             GatewayCacheClient gatewayCacheClient) {
        this.onboardingRepository = onboardingRepository;
        this.userRepository = userRepository;
        this.organizationService = organizationService;
        this.gatewayCacheClient = gatewayCacheClient;
    }

    /**
     * Resolve a batch of user ids to their display names - the SAME name shown
     * in the sidebar / members table ({@code user_onboarding.display_name}).
     * Falls back to the user's full name → username → email when no onboarding
     * display name is set. Ids with no user row are simply absent from the map
     * (caller renders a fallback like "user #id").
     *
     * <p>Used by the org audit log so events read "… by ada lovelace" instead
     * of "… by user #1".
     */
    @Transactional(readOnly = true)
    public Map<Long, String> resolveDisplayNames(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        Set<Long> ids = new HashSet<>(userIds);
        Map<Long, String> result = new LinkedHashMap<>();
        Set<Long> missing = new HashSet<>(ids);
        for (UserOnboarding uo : onboardingRepository.findAllByUserIdIn(ids)) {
            Long uid = uo.getUser().getId();
            String dn = uo.getDisplayName();
            if (dn != null && !dn.isBlank()) {
                result.put(uid, dn.trim());
                missing.remove(uid);
            }
        }
        if (!missing.isEmpty()) {
            for (User u : userRepository.findAllById(missing)) {
                result.put(u.getId(), fallbackDisplayName(u));
            }
        }
        return result;
    }

    /**
     * Resolve a SINGLE user's display name with the same fallback chain as
     * {@link #resolveDisplayNames(Collection)}: onboarding {@code display_name}
     * → full name → username → email. Returns {@code null} only when
     * {@code userId} is null or no user row exists for it (the caller picks the
     * final placeholder).
     *
     * <p>CE has no onboarding step, so embedded users never get a
     * {@code user_onboarding.display_name}. Several call sites read that column
     * alone and fell straight back to a hardcoded "Unknown"; this fallback chain
     * recovers the real name from the {@link User} row (full name → username →
     * email). It is a no-op in cloud, where onboarding always sets a display
     * name.
     */
    @Transactional(readOnly = true)
    public String resolveDisplayName(Long userId) {
        if (userId == null) {
            return null;
        }
        return resolveDisplayNames(List.of(userId)).get(userId);
    }

    /**
     * Resolve a mixed batch of user-id strings (numeric {@code user.id} AND/OR
     * provider-id {@code sub}) to {@link UserSummaryDto}. The display name comes
     * from {@code user_onboarding.display_name} when present, else falls back to
     * the {@code users} row identity (full name → username → email).
     *
     * <p><b>Why the fallback matters - CE vs Cloud:</b> the display name has two
     * different sources depending on edition, and only the first is guaranteed:
     * <ul>
     *   <li><b>Cloud</b>: {@code UserOnboarding.display_name}, written during the
     *       onboarding flow from the OIDC profile. Onboarded cloud users always
     *       have it, so the fallback is a pure no-op for them.</li>
     *   <li><b>CE embedded</b>: {@code PasswordAuthService.register} creates the
     *       {@code users} row (first/last name, username, email) but <b>never</b>
     *       a {@code user_onboarding} row. Without the fallback every CE actor
     *       resolves to "unknown" in the recent-activity feed.</li>
     * </ul>
     * The fallback reads the same {@code users} columns the cloud OIDC upsert
     * populates ({@code OAuthUserProcessor}), so it degrades gracefully in both
     * editions and never overrides a real onboarding display name.
     *
     * <p>Keys in the returned map are the EXACT input strings (so "42" stays a
     * String, a UUID stays a UUID). Ids with neither an onboarding name nor a
     * {@code users} row are simply absent - the caller decides the placeholder.
     * {@code avatarUrl} is intentionally left null here to preserve the existing
     * name-only wire contract of the batch resolve endpoint.
     */
    @Transactional(readOnly = true)
    public Map<String, UserSummaryDto> resolveUserSummaries(Collection<String> rawIds) {
        if (rawIds == null || rawIds.isEmpty()) {
            return Map.of();
        }
        // Partition by form: pure-digit → numeric user.id, else provider_id.
        // Mirrors InternalAuthController#getDisplayName so a caller mixing forms
        // gets consistent routing regardless of which id it holds.
        Set<Long> numericIds = new HashSet<>();
        Map<Long, String> numericInputBy = new HashMap<>();
        Set<String> providerIds = new HashSet<>();
        for (String raw : rawIds) {
            if (raw == null || raw.isBlank()) continue;
            if (raw.matches("\\d+")) {
                try {
                    Long n = Long.parseLong(raw);
                    numericIds.add(n);
                    numericInputBy.put(n, raw);
                } catch (NumberFormatException ignored) {
                    // overflow → treat as unresolvable, falls through to absent
                }
            } else {
                providerIds.add(raw);
            }
        }

        Map<String, UserSummaryDto> result = new HashMap<>();
        // Ids still needing the users-row fallback (onboarding gave no name).
        Set<Long> numericNeedingFallback = new HashSet<>(numericIds);
        Set<String> providerNeedingFallback = new HashSet<>(providerIds);

        if (!numericIds.isEmpty()) {
            for (UserOnboarding uo : onboardingRepository.findAllByUserIdIn(numericIds)) {
                Long uid = uo.getUser().getId();
                String inputKey = numericInputBy.get(uid);
                if (inputKey == null) continue;
                String dn = uo.getDisplayName();
                if (dn != null && !dn.isBlank()) {
                    result.put(inputKey, UserSummaryDto.displayNameOnly(inputKey, dn.trim()));
                    numericNeedingFallback.remove(uid);
                }
            }
        }
        if (!providerIds.isEmpty()) {
            for (UserOnboarding uo : onboardingRepository.findAllByUserProviderIdIn(providerIds)) {
                String pid = uo.getUser().getProviderId();
                if (pid == null || !providerIds.contains(pid)) continue;
                String dn = uo.getDisplayName();
                if (dn != null && !dn.isBlank()) {
                    result.put(pid, UserSummaryDto.displayNameOnly(pid, dn.trim()));
                    providerNeedingFallback.remove(pid);
                }
            }
        }

        // Fallback to the users-row identity for ids with no onboarding name.
        if (!numericNeedingFallback.isEmpty()) {
            for (User u : userRepository.findAllById(numericNeedingFallback)) {
                String inputKey = numericInputBy.get(u.getId());
                if (inputKey == null) continue;
                result.put(inputKey, UserSummaryDto.displayNameOnly(inputKey, fallbackDisplayName(u)));
            }
        }
        if (!providerNeedingFallback.isEmpty()) {
            for (User u : userRepository.findByProviderIdIn(providerNeedingFallback)) {
                String pid = u.getProviderId();
                if (pid == null || !providerNeedingFallback.contains(pid)) continue;
                result.put(pid, UserSummaryDto.displayNameOnly(pid, fallbackDisplayName(u)));
            }
        }
        return result;
    }

    /** Full name → username → email, for users without an onboarding display name. */
    private static String fallbackDisplayName(User u) {
        String full = ((u.getFirstName() != null ? u.getFirstName() : "") + " "
                + (u.getLastName() != null ? u.getLastName() : "")).trim();
        if (!full.isEmpty()) {
            return full;
        }
        if (u.getUsername() != null && !u.getUsername().isBlank()) {
            return u.getUsername();
        }
        return u.getEmail();
    }

    /**
     * Get onboarding status for a user by provider ID.
     *
     * @param providerId Keycloak sub (UUID)
     * @return OnboardingResponse with status and data
     */
    @Transactional(readOnly = true)
    public OnboardingResponse getOnboardingStatus(String providerId) {
        log.info("Getting onboarding status for providerId: {}", providerId);

        // Look up user to get email verification status
        Optional<User> userOpt = userRepository.findByProviderId(providerId);
        boolean emailVerified = userOpt.map(User::isEmailVerified).orElse(false);

        Optional<UserOnboarding> onboardingOpt = onboardingRepository.findByUserProviderId(providerId);

        OnboardingResponse response;
        if (onboardingOpt.isEmpty()) {
            log.info("No onboarding found for providerId: {}, needs onboarding", providerId);
            response = OnboardingResponse.needsOnboarding();
        } else {
            UserOnboarding onboarding = onboardingOpt.get();
            log.info("Onboarding status for providerId {}: completed={}, skipped={}",
                    providerId, onboarding.isOnboardingCompleted(), onboarding.isOnboardingSkipped());
            response = OnboardingResponse.fromEntity(onboarding);
        }

        response.setEmailVerified(emailVerified);

        // If email is not verified, force needsOnboarding to true
        // so the frontend guard redirects to the onboarding flow
        if (!emailVerified) {
            response.setNeedsOnboarding(true);
        }

        return response;
    }

    /**
     * Check if a user needs onboarding.
     *
     * @param providerId Keycloak sub (UUID)
     * @return true if onboarding is needed
     */
    @Transactional(readOnly = true)
    public boolean needsOnboarding(String providerId) {
        // Email must be verified before onboarding can be considered complete
        Optional<User> userOpt = userRepository.findByProviderId(providerId);
        if (userOpt.isPresent() && !userOpt.get().isEmailVerified()) {
            return true;
        }

        Optional<UserOnboarding> onboardingOpt = onboardingRepository.findByUserProviderId(providerId);
        return onboardingOpt.isEmpty() || onboardingOpt.get().needsOnboarding();
    }

    /**
     * Save or update onboarding data.
     *
     * @param providerId Keycloak sub (UUID)
     * @param request    Onboarding data
     * @return OnboardingResponse with updated data
     */
    public OnboardingResponse saveOnboarding(String providerId, OnboardingRequest request) {
        log.info("💾 Saving onboarding for providerId: {}, request: {}", providerId, request);

        // Find user
        User user = userRepository.findByProviderId(providerId)
                .orElseThrow(() -> new IllegalArgumentException("User not found for providerId: " + providerId));

        // Find or create onboarding
        UserOnboarding onboarding = onboardingRepository.findByUserId(user.getId())
                .orElseGet(() -> new UserOnboarding(user, request.getDisplayName()));

        // Check display name uniqueness (always check for new onboardings, check on name change for existing)
        if (request.getDisplayName() != null && (onboarding.getId() == null || !request.getDisplayName().equals(onboarding.getDisplayName()))) {
            boolean displayNameTaken = onboarding.getId() != null
                    ? onboardingRepository.existsByDisplayNameIgnoreCaseAndUserIdNot(request.getDisplayName(), user.getId())
                    : onboardingRepository.existsByDisplayNameIgnoreCase(request.getDisplayName());

            if (displayNameTaken) {
                throw new IllegalArgumentException("Display name is already taken");
            }
        }

        // Update onboarding data (convert empty strings to null for DB constraints)
        if (request.getDisplayName() != null && !request.getDisplayName().trim().isEmpty()) {
            onboarding.setDisplayName(request.getDisplayName().trim());
        }
        if (request.getProfession() != null) {
            onboarding.setProfession(nullIfEmpty(request.getProfession()));
        }
        if (request.getCompanySize() != null) {
            onboarding.setCompanySize(nullIfEmpty(request.getCompanySize()));
        }
        if (request.getInterests() != null) {
            onboarding.setInterests(request.getInterests());
        }
        if (request.getUseCases() != null) {
            onboarding.setUseCases(request.getUseCases());
        }
        if (request.getExperienceLevel() != null) {
            onboarding.setExperienceLevel(nullIfEmpty(request.getExperienceLevel()));
        }

        // Update step progress
        onboarding.setOnboardingStep(request.getCurrentStep());

        // Save
        UserOnboarding saved = onboardingRepository.save(onboarding);
        log.info("✅ Onboarding saved for userId: {}, step: {}", user.getId(), saved.getOnboardingStep());

        OnboardingResponse response = OnboardingResponse.fromEntity(saved);
        response.setEmailVerified(user.isEmailVerified());
        return response;
    }

    /**
     * Complete onboarding for a user.
     *
     * @param providerId Keycloak sub (UUID)
     * @param request    Final onboarding data
     * @return OnboardingResponse with completed status
     */
    public OnboardingResponse completeOnboarding(String providerId, OnboardingRequest request) {
        log.info("Completing onboarding for providerId: {}", providerId);

        // Require email verification before completing onboarding
        User verifiedUser = userRepository.findByProviderId(providerId)
                .orElseThrow(() -> new IllegalArgumentException("User not found for providerId: " + providerId));
        if (!verifiedUser.isEmailVerified()) {
            throw new IllegalArgumentException("Email must be verified before completing onboarding");
        }

        // Save the onboarding data first
        OnboardingResponse response = saveOnboarding(providerId, request);

        // Mark as completed
        UserOnboarding onboarding = onboardingRepository.findByUserProviderId(providerId)
                .orElseThrow(() -> new IllegalStateException("Onboarding not found after save"));

        onboarding.markCompleted();
        UserOnboarding saved = onboardingRepository.save(onboarding);

        // Update User.username with the display name so it reflects across the app
        User user = onboarding.getUser();
        updateUserUsername(user, saved.getDisplayName());

        // Create personal organization (silent, no UI)
        try {
            organizationService.createPersonalOrganization(user, saved.getDisplayName());
            bustGatewayCacheAfterCommit(user);
            log.info("🏢 Personal organization created for user: {}", user.getId());
        } catch (Exception e) {
            // Log but don't fail onboarding - org creation is non-critical
            log.error("⚠️ Failed to create personal organization for providerId: {}", providerId, e);
        }

        log.info("✅ Onboarding completed for providerId: {}", providerId);
        OnboardingResponse completed = OnboardingResponse.completed(saved);
        completed.setEmailVerified(user.isEmailVerified());
        return completed;
    }

    /**
     * Skip onboarding for a user (requires display name).
     *
     * @param providerId  Keycloak sub (UUID)
     * @param displayName Mandatory display name
     * @return OnboardingResponse with skipped status
     */
    public OnboardingResponse skipOnboarding(String providerId, String displayName) {
        log.info("Skipping onboarding for providerId: {}", providerId);

        if (displayName == null || displayName.trim().isEmpty()) {
            throw new IllegalArgumentException("Display name is required even when skipping onboarding");
        }

        // Find user
        User user = userRepository.findByProviderId(providerId)
                .orElseThrow(() -> new IllegalArgumentException("User not found for providerId: " + providerId));

        // Require email verification before skipping onboarding
        if (!user.isEmailVerified()) {
            throw new IllegalArgumentException("Email must be verified before skipping onboarding");
        }

        // Find or create onboarding
        UserOnboarding onboarding = onboardingRepository.findByUserId(user.getId())
                .orElseGet(() -> new UserOnboarding(user, displayName));

        // Check display name uniqueness
        boolean displayNameTaken = onboarding.getId() != null
                ? onboardingRepository.existsByDisplayNameIgnoreCaseAndUserIdNot(displayName, user.getId())
                : onboardingRepository.existsByDisplayNameIgnoreCase(displayName);

        if (displayNameTaken) {
            throw new IllegalArgumentException("Display name is already taken");
        }

        onboarding.setDisplayName(displayName);
        onboarding.markSkipped();

        UserOnboarding saved = onboardingRepository.save(onboarding);
        log.info("⏭️ Onboarding skipped for providerId: {}, displayName: {}", providerId, displayName);

        // Update User.username with the display name so it reflects across the app
        updateUserUsername(user, displayName);

        // Create personal organization (silent, no UI)
        try {
            organizationService.createPersonalOrganization(user, displayName);
            bustGatewayCacheAfterCommit(user);
            log.info("🏢 Personal organization created for user: {}", user.getId());
        } catch (Exception e) {
            // Log but don't fail skip - org creation is non-critical
            log.error("⚠️ Failed to create personal organization for providerId: {}", providerId, e);
        }

        OnboardingResponse response = OnboardingResponse.fromEntity(saved);
        response.setEmailVerified(user.isEmailVerified());
        return response;
    }

    /**
     * Creating the personal org changes the user's default workspace and role
     * headers. The gateway may already have cached this user during the
     * onboarding status call, so evict after commit before the next app request.
     */
    private void bustGatewayCacheAfterCommit(User user) {
        if (user == null || user.getProviderId() == null || user.getProviderId().isBlank()) {
            return;
        }
        String providerId = user.getProviderId();
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            gatewayCacheClient.invalidateUserCache(providerId);
                        }
                    });
        } else {
            gatewayCacheClient.invalidateUserCache(providerId);
        }
    }

    /**
     * Check if display name is available.
     *
     * @param displayName Display name to check
     * @param providerId  Current user's provider ID (to exclude from check)
     * @return true if available
     */
    @Transactional(readOnly = true)
    public boolean isDisplayNameAvailable(String displayName, String providerId) {
        if (displayName == null || displayName.trim().isEmpty()) {
            return false;
        }

        Optional<User> userOpt = userRepository.findByProviderId(providerId);
        if (userOpt.isEmpty()) {
            return !onboardingRepository.existsByDisplayNameIgnoreCase(displayName);
        }

        return !onboardingRepository.existsByDisplayNameIgnoreCaseAndUserIdNot(displayName, userOpt.get().getId());
    }

    /**
     * Check if display name is available, excluding a specific user by ID.
     */
    @Transactional(readOnly = true)
    public boolean isDisplayNameAvailableForUser(String displayName, Long userId) {
        if (displayName == null || displayName.trim().isEmpty()) {
            return false;
        }
        return !onboardingRepository.existsByDisplayNameIgnoreCaseAndUserIdNot(displayName, userId);
    }

    /**
     * Get onboarding data for a user by user ID.
     *
     * @param userId User ID
     * @return Optional onboarding data
     */
    @Transactional(readOnly = true)
    public Optional<UserOnboarding> getOnboardingByUserId(Long userId) {
        return onboardingRepository.findByUserId(userId);
    }

    /**
     * Update User.username with the display name from onboarding.
     * This ensures the username field reflects the chosen display name
     * instead of a Keycloak auto-generated ID (e.g. "kc_2d4cc1b2").
     */
    private void updateUserUsername(User user, String displayName) {
        if (displayName == null || displayName.trim().isEmpty()) return;
        try {
            // Truncate to 50 chars to respect @Size(max = 50) on User.username
            String username = displayName.trim();
            if (username.length() > 50) {
                username = username.substring(0, 50);
            }
            user.setUsername(username);
            userRepository.save(user);
            log.info("✅ Updated User.username to '{}' for userId: {}", username, user.getId());
        } catch (Exception e) {
            // Non-critical: displayName in UserOnboarding is the primary source
            log.warn("⚠️ Failed to update User.username for userId {}: {}", user.getId(), e.getMessage());
        }
    }

    /**
     * Convert empty strings to null for database constraints.
     */
    private String nullIfEmpty(String value) {
        return (value == null || value.trim().isEmpty()) ? null : value.trim();
    }
}

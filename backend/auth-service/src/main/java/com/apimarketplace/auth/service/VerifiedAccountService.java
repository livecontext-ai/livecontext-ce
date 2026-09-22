package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.domain.UserProfileEntity;
import com.apimarketplace.auth.repository.UserProfileRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.common.web.AppEditionProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Single source of truth for the verified badge (the blue check shown next to a
 * public name, Instagram / X style).
 *
 * <p>Nothing else in the codebase may decide that a user is verified. Two sources
 * feed one answer here:
 * <ol>
 *   <li>the platform <b>ADMIN</b> role - implicit, stored nowhere, so a newly
 *       promoted admin is verified with no backfill and a demoted one loses it;</li>
 *   <li>the manual grant on {@link UserProfileEntity#isVerified()} - what an admin
 *       flips on for everyone else.</li>
 * </ol>
 *
 * <p><b>Managed cloud only.</b> Every read short-circuits to "not verified" on a
 * self-hosted deployment, and the write refuses. A self-hosted install promotes its
 * very first user to ADMIN ({@code FirstAdminBootstrap}), so source (1) would hand a
 * blue check to every solo install owner, which says nothing to anyone. Keeping the
 * gate in this class (rather than at each call site) is what makes the rule hold for
 * every surface at once, including any added later.
 *
 * <p>Both sources are overridden by two conditions, because the badge is a PUBLIC
 * claim: the account must still be enabled, and its profile page must not have been
 * withdrawn (visibility PRIVATE). A user who took their public page down does not get
 * a public claim made about them anyway.
 *
 * <p>Unrelated to {@code User.emailVerified}: that is a mail-ownership check on the
 * account, this is a public identity claim about who the account belongs to.
 */
@Service
public class VerifiedAccountService {

    /**
     * Ceiling on one batch lookup. Callers page the marketplace in tens, so this is
     * far above any real screen while keeping a single request from turning into an
     * unbounded {@code IN (...)}.
     */
    public static final int MAX_BATCH_SIZE = 100;

    private static final String ADMIN_ROLE = "ADMIN";

    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;
    private final AppEditionProvider editionProvider;

    public VerifiedAccountService(UserRepository userRepository,
                                  UserProfileRepository userProfileRepository,
                                  AppEditionProvider editionProvider) {
        this.userRepository = userRepository;
        this.userProfileRepository = userProfileRepository;
        this.editionProvider = editionProvider;
    }

    /**
     * Whether the badge exists at all on this deployment. False on every self-hosted
     * edition, which makes all reads empty and the admin write a 503.
     */
    public boolean isFeatureEnabled() {
        return editionProvider.isManagedCloud();
    }

    /**
     * Whether this user carries the badge.
     *
     * <p>Loads the profile row once and hands off to {@link #isVerified(User,
     * UserProfileEntity)}. A caller that already holds the row should use that overload
     * instead: {@code UserService.getPublicProfile} does, and would otherwise re-read a
     * row it is standing on.
     */
    @Transactional(readOnly = true)
    public boolean isVerified(User user) {
        if (!isFeatureEnabled() || user == null || !user.isEnabled()) {
            return false;
        }
        return isVerified(user, userProfileRepository.findByUserId(user.getId()).orElse(null));
    }

    /**
     * Same answer, for a caller that already has the profile row (or knows there is
     * none, in which case pass {@code null} - an account that never edited its profile
     * has no row and is treated as visible, which is the default state).
     *
     * <p>Three conditions, all required: the account is live, its profile page has not
     * been withdrawn, and either the ADMIN role or the manual grant applies. The
     * visibility condition is what keeps the badge from being a public claim about
     * someone who removed their public page - see
     * {@code UserProfileRepository.findPrivateProfileIdsIn}.
     */
    @Transactional(readOnly = true)
    public boolean isVerified(User user, UserProfileEntity profile) {
        if (!isFeatureEnabled() || user == null || !user.isEnabled()) {
            return false;
        }
        if (profile != null && !profile.isPageVisible()) {
            return false;
        }
        return hasAdminRole(user) || (profile != null && profile.isVerified());
    }

    /**
     * The subset of {@code userIds} that carry the badge. Three indexed lookups, never
     * one per id: this runs once per rendered list.
     *
     * <p>Unknown, disabled and unverified ids are all simply absent from the result,
     * so the answer can never be used to probe which ids exist.
     */
    @Transactional(readOnly = true)
    public Set<Long> verifiedAmong(Collection<Long> userIds) {
        if (!isFeatureEnabled() || userIds == null || userIds.isEmpty()) {
            return Set.of();
        }
        Set<Long> distinct = new HashSet<>(userIds);
        distinct.remove(null);
        if (distinct.isEmpty()) {
            return Set.of();
        }
        Set<Long> verified = new HashSet<>(userRepository.findAdminIdsIn(distinct));
        verified.addAll(userProfileRepository.findManuallyVerifiedIdsIn(distinct));
        // A withdrawn profile page withdraws the public badge with it, whichever source
        // granted it. Subtracted rather than filtered inside the two lookups above,
        // because an admin with no profile row at all must still count as verified.
        verified.removeAll(userProfileRepository.findPrivateProfileIdsIn(distinct));
        return verified;
    }

    /**
     * The subset of {@code handles} whose owner carries the badge, returned with the
     * exact spelling the caller used (the match itself is case-insensitive) so a
     * server-rendered page can look each author up without ever holding a numeric
     * user id.
     */
    @Transactional(readOnly = true)
    public Set<String> verifiedHandlesAmong(Collection<String> handles) {
        if (!isFeatureEnabled() || handles == null || handles.isEmpty()) {
            return Set.of();
        }
        Map<String, String> byLowercase = new HashMap<>();
        for (String handle : handles) {
            if (handle != null && !handle.isBlank()) {
                byLowercase.putIfAbsent(handle.trim().toLowerCase(Locale.ROOT), handle);
            }
        }
        if (byLowercase.isEmpty()) {
            return Set.of();
        }
        // Already lowercased above, which is what findIdsByHandles requires: it matches
        // the indexed column exactly rather than LOWER()-ing it into a sequential scan.
        List<Object[]> rows = userProfileRepository.findIdsByHandles(byLowercase.keySet());
        Map<Long, String> handleByUserId = new HashMap<>();
        for (Object[] row : rows) {
            String storedHandle = (String) row[0];
            Long userId = (Long) row[1];
            if (storedHandle == null || userId == null) {
                continue;
            }
            String asRequested = byLowercase.get(storedHandle.toLowerCase(Locale.ROOT));
            if (asRequested != null) {
                handleByUserId.put(userId, asRequested);
            }
        }
        Set<Long> verifiedIds = verifiedAmong(handleByUserId.keySet());
        Set<String> result = new HashSet<>();
        for (Long userId : verifiedIds) {
            result.add(handleByUserId.get(userId));
        }
        return result;
    }

    /**
     * Grant or revoke the MANUAL badge for one user. Admin-only operation; the caller
     * is responsible for the role check and the audit entry.
     *
     * <p>Creates the profile row when the user has never edited their profile, so a
     * brand-new account can be verified without having to touch its profile first.
     *
     * @return the resolved state, or empty when the deployment is self-hosted or the
     *         user does not exist.
     */
    @Transactional
    public Optional<VerificationState> setManualVerified(Long targetUserId, boolean verified, Long adminUserId) {
        if (!isFeatureEnabled() || targetUserId == null) {
            return Optional.empty();
        }
        Optional<User> userOpt = userRepository.findById(targetUserId);
        if (userOpt.isEmpty()) {
            return Optional.empty();
        }
        User user = userOpt.get();
        UserProfileEntity profile = userProfileRepository.findByUserId(targetUserId)
                .orElseGet(() -> new UserProfileEntity(targetUserId));
        profile.setVerified(verified);
        if (verified) {
            profile.setVerifiedAt(LocalDateTime.now());
            profile.setVerifiedBy(adminUserId);
        } else {
            // Cleared rather than kept: the audit log is the history, and a stale
            // "verified on <date>" on a revoked badge reads as still granted.
            profile.setVerifiedAt(null);
            profile.setVerifiedBy(null);
        }
        UserProfileEntity saved = userProfileRepository.save(profile);
        // The public answer, computed by the same rule every read uses, rather than
        // derived from the two grant sources: a withdrawn profile page shows no badge
        // however it was granted, and an admin screen that claimed otherwise would be
        // telling the operator something no visitor can see.
        return Optional.of(new VerificationState(
                user.getId(), user.getEmail(), verified, hasAdminRole(user),
                isVerified(user, saved), !saved.isPageVisible()));
    }

    private boolean hasAdminRole(User user) {
        return user.getRoles() != null && user.getRoles().contains(ADMIN_ROLE);
    }

    /**
     * Outcome of an admin write.
     *
     * @param manuallyVerified    the stored flag after the write
     * @param verifiedByRole      whether the ADMIN role ALSO grants the badge, in which
     *                            case revoking the manual flag leaves the badge showing.
     *                            The admin UI says so rather than letting the operator
     *                            believe a revoke that did not visibly happen.
     * @param effectivelyVerified what a reader actually sees after the write - NOT
     *                            {@code manuallyVerified || verifiedByRole}, because a
     *                            withdrawn profile page suppresses both.
     * @param profileWithdrawn    the target set their profile to PRIVATE, so the grant is
     *                            stored and dormant. The admin UI explains that rather
     *                            than reporting a badge nobody can see.
     */
    public record VerificationState(Long userId, String email, boolean manuallyVerified,
                                    boolean verifiedByRole, boolean effectivelyVerified,
                                    boolean profileWithdrawn) {
    }
}

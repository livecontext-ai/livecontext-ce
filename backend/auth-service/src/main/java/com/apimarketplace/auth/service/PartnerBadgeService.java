package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.RewardCode;
import com.apimarketplace.auth.domain.RewardProgram;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.domain.UserProfileEntity;
import com.apimarketplace.auth.repository.RewardCodeRepository;
import com.apimarketplace.auth.repository.UserProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Single source of truth for the official-partner badge (the gold seal shown next to a
 * public name, beside the blue verified check).
 *
 * <p>A user is an official partner when they own a live PARTNER code: active and inside
 * its validity window. Nothing is stored for the badge itself, so disabling a partner's
 * code in the admin removes the badge everywhere at once, and approving an application
 * grants it with no second step.
 *
 * <p>Same public-claim rules as {@link VerifiedAccountService}, and the same edition gate:
 * managed cloud only, account enabled, profile page not withdrawn (PRIVATE).
 */
@Service
public class PartnerBadgeService {

    private final RewardCodeRepository rewardCodeRepository;
    private final UserProfileRepository userProfileRepository;
    private final VerifiedAccountService verifiedAccountService;
    private final PartnerTierService tierService;

    public PartnerBadgeService(RewardCodeRepository rewardCodeRepository,
                               UserProfileRepository userProfileRepository,
                               VerifiedAccountService verifiedAccountService,
                               PartnerTierService tierService) {
        this.rewardCodeRepository = rewardCodeRepository;
        this.userProfileRepository = userProfileRepository;
        this.verifiedAccountService = verifiedAccountService;
        this.tierService = tierService;
    }

    /** For a caller already holding the profile row ({@code null} = no row, visible by default). */
    @Transactional(readOnly = true)
    public boolean isPartner(User user, UserProfileEntity profile) {
        if (!verifiedAccountService.isFeatureEnabled() || user == null || !user.isEnabled()) {
            return false;
        }
        if (profile != null && !profile.isPageVisible()) {
            return false;
        }
        return rewardCodeRepository.findByOwnerUserIdAndProgram(user.getId(), RewardProgram.PARTNER)
                .map(this::isLive)
                .orElse(false);
    }

    /**
     * The tier a badge-carrying partner has reached, lower case ({@code silver}, {@code gold},
     * {@code platinum}), for their public profile; {@code null} when the account carries no
     * badge. The badge itself is the same for every tier: only the profile names the tier.
     */
    @Transactional(readOnly = true)
    public String partnerTier(User user, UserProfileEntity profile) {
        if (!isPartner(user, profile)) return null;
        return tierService.tierOf(user.getId()).name().toLowerCase(java.util.Locale.ROOT);
    }

    /** The subset of {@code userIds} carrying the badge; absent ids reveal nothing. */
    @Transactional(readOnly = true)
    public Set<Long> partnersAmong(Collection<Long> userIds) {
        if (!verifiedAccountService.isFeatureEnabled() || userIds == null || userIds.isEmpty()) {
            return Set.of();
        }
        Set<Long> distinct = new HashSet<>(userIds);
        distinct.remove(null);
        if (distinct.isEmpty()) {
            return Set.of();
        }
        Set<Long> partners = new HashSet<>(rewardCodeRepository.findLivePartnerOwnerIdsIn(distinct, Instant.now()));
        if (partners.isEmpty()) {
            return partners;
        }
        partners.removeAll(userProfileRepository.findPrivateProfileIdsIn(partners));
        return partners;
    }

    /** Handle-keyed twin for the anonymous server-rendered pages, echoing the caller's spelling. */
    @Transactional(readOnly = true)
    public Set<String> partnerHandlesAmong(Collection<String> handles) {
        if (!verifiedAccountService.isFeatureEnabled() || handles == null || handles.isEmpty()) {
            return Set.of();
        }
        Map<Long, String> handleByUserId = verifiedAccountService.handleOwners(handles);
        Set<String> result = new HashSet<>();
        for (Long userId : partnersAmong(handleByUserId.keySet())) {
            result.add(handleByUserId.get(userId));
        }
        return result;
    }

    private boolean isLive(RewardCode code) {
        return code.isRedeemableAt(Instant.now());
    }
}

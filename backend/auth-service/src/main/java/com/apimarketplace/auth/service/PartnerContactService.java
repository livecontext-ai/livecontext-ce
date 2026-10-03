package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.RewardProgram;
import com.apimarketplace.auth.domain.RewardRedemption;
import com.apimarketplace.auth.dto.PublicProfileDto;
import com.apimarketplace.auth.repository.RewardRedemptionRepository;
import com.apimarketplace.auth.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * The partner a client came through, so the client can reach them: the owner of the partner
 * code the client redeemed (one PARTNER redemption per account, the one commissions follow too).
 *
 * <p>The client already knows this partner (they followed their link), so the contact is given
 * even when the partner's profile is private; only the public name, handle and tier are read
 * from the profile, and they are left empty when it is private.
 */
@Service
public class PartnerContactService {

    /** The partner as the client's messages show them. */
    public record MyPartner(Long userId, String name, String handle, String partnerTier) {}

    private final RewardRedemptionRepository redemptions;
    private final UserRepository users;
    private final UserService userService;

    public PartnerContactService(RewardRedemptionRepository redemptions, UserRepository users, UserService userService) {
        this.redemptions = redemptions;
        this.users = users;
        this.userService = userService;
    }

    /**
     * The client's partner, or empty when the client came through none (or the account is gone).
     * Writable, not read-only: the public profile generates its @handle lazily on first read.
     */
    @Transactional
    public Optional<MyPartner> myPartner(Long clientUserId) {
        if (clientUserId == null) return Optional.empty();
        return redemptions.findByRedeemerUserIdAndProgram(clientUserId, RewardProgram.PARTNER)
                .map(RewardRedemption::getOwnerUserId)
                // Never the client themselves (a partner cannot redeem their own code, kept as a guard).
                .filter(owner -> owner != null && !owner.equals(clientUserId))
                .flatMap(users::findById)
                .map(partner -> {
                    PublicProfileDto profile = userService.getPublicProfile(partner).orElse(null);
                    return new MyPartner(partner.getId(),
                            profile == null ? null : profile.displayName(),
                            profile == null ? null : profile.handle(),
                            profile == null ? null : profile.partnerTier());
                });
    }
}

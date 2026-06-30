package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.CeLink;
import com.apimarketplace.auth.repository.CeLinkRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Resolves the referral code and invite stats of the cloud account a CE install
 * is bound to, so a self-hosted install can show its owner's referral progress
 * over the ce-link channel.
 *
 * <p>Mirrors {@link CeLinkEntitlementsService}: ownership-scoped (a caller only
 * ever reads their own install) and fail-closed (an unknown, revoked, or foreign
 * install yields empty stats, never another tenant's data). The reward state
 * itself lives on the cloud account, so a CE_FREE or self-hosted install earns
 * and spends on its bound cloud wallet, not locally.
 */
@Service
public class CeLinkRewardReadService {

    private final CeLinkRepository ceLinkRepository;
    private final RewardService rewardService;

    public CeLinkRewardReadService(CeLinkRepository ceLinkRepository, RewardService rewardService) {
        this.ceLinkRepository = ceLinkRepository;
        this.rewardService = rewardService;
    }

    /**
     * Invite stats for the cloud account bound to {@code installId}, scoped to
     * {@code callerUserId}. Lazily mints the owner's referral code on first read.
     * Returns empty stats when the install is unknown, revoked, or not owned by
     * the caller.
     */
    @Transactional
    public RewardService.InviteStats statsForCaller(Long callerUserId, UUID installId) {
        if (callerUserId == null || installId == null) {
            return emptyStats();
        }
        return ceLinkRepository.findById(installId)
                .filter(CeLink::isActive)
                .filter(link -> callerUserId.equals(link.getUserId()))
                .map(link -> rewardService.getInviteStats(link.getUserId()))
                .orElseGet(this::emptyStats);
    }

    private RewardService.InviteStats emptyStats() {
        return new RewardService.InviteStats(null, 0, 0, 0, 0, 0, null, 0);
    }
}

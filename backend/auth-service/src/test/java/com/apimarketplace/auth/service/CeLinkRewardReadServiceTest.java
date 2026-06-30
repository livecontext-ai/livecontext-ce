package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.CeLink;
import com.apimarketplace.auth.repository.CeLinkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Ownership scoping for the CE reward-stats read: only the install owner gets
 * their stats; an unknown, revoked, or foreign install yields empty stats and
 * never touches another tenant's data.
 */
class CeLinkRewardReadServiceTest {

    private static final UUID INSTALL = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private CeLinkRepository ceLinkRepository;
    private RewardService rewardService;
    private CeLinkRewardReadService service;

    @BeforeEach
    void setUp() {
        ceLinkRepository = mock(CeLinkRepository.class);
        rewardService = mock(RewardService.class);
        service = new CeLinkRewardReadService(ceLinkRepository, rewardService);
    }

    @Test
    @DisplayName("owner: returns the bound account's invite stats")
    void ownerGetsStats() {
        CeLink link = mock(CeLink.class);
        when(link.isActive()).thenReturn(true);
        when(link.getUserId()).thenReturn(42L);
        when(ceLinkRepository.findById(INSTALL)).thenReturn(Optional.of(link));
        when(rewardService.getInviteStats(42L))
                .thenReturn(new RewardService.InviteStats("ABCD2345", 3, 1, 1, 1, 8000, null, 8000));

        RewardService.InviteStats stats = service.statsForCaller(42L, INSTALL);

        assertThat(stats.code()).isEqualTo("ABCD2345");
        assertThat(stats.rewardedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("foreign caller: returns empty stats, never reads the owner's data")
    void foreignCallerEmpty() {
        CeLink link = mock(CeLink.class);
        when(link.isActive()).thenReturn(true);
        when(link.getUserId()).thenReturn(42L);
        when(ceLinkRepository.findById(INSTALL)).thenReturn(Optional.of(link));

        RewardService.InviteStats stats = service.statsForCaller(99L, INSTALL);

        assertThat(stats.code()).isNull();
        verify(rewardService, never()).getInviteStats(anyLong());
    }

    @Test
    @DisplayName("unknown install: returns empty stats")
    void unknownInstallEmpty() {
        when(ceLinkRepository.findById(INSTALL)).thenReturn(Optional.empty());
        RewardService.InviteStats stats = service.statsForCaller(42L, INSTALL);
        assertThat(stats.code()).isNull();
        verify(rewardService, never()).getInviteStats(anyLong());
    }

    @Test
    @DisplayName("null args: returns empty stats")
    void nullArgsEmpty() {
        assertThat(service.statsForCaller(null, INSTALL).code()).isNull();
        assertThat(service.statsForCaller(42L, null).code()).isNull();
        verifyNoInteractions(rewardService);
    }
}

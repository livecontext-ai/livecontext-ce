package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.RewardCode;
import com.apimarketplace.auth.domain.RewardProgram;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.domain.UserProfileEntity;
import com.apimarketplace.auth.repository.RewardCodeRepository;
import com.apimarketplace.auth.repository.UserProfileRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.common.web.AppEditionProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

/**
 * The official-partner badge: derived from a live PARTNER code, under the same public-claim
 * rules as the verified badge (managed cloud only, enabled account, profile not withdrawn).
 */
@DisplayName("PartnerBadgeService")
class PartnerBadgeServiceTest {

    private RewardCodeRepository codeRepository;
    private UserProfileRepository profileRepository;
    private UserRepository userRepository;
    private AppEditionProvider edition;
    private PartnerTierService tierService;
    private PartnerBadgeService service;

    @BeforeEach
    void setUp() {
        codeRepository = mock(RewardCodeRepository.class);
        profileRepository = mock(UserProfileRepository.class);
        userRepository = mock(UserRepository.class);
        edition = mock(AppEditionProvider.class);
        when(edition.isManagedCloud()).thenReturn(true);
        VerifiedAccountService verified = new VerifiedAccountService(userRepository, profileRepository, edition);
        tierService = mock(PartnerTierService.class);
        service = new PartnerBadgeService(codeRepository, profileRepository, verified, tierService);
    }

    private static User user(long id, boolean enabled) {
        User u = new User();
        u.setId(id);
        u.setEnabled(enabled);
        return u;
    }

    private static RewardCode code(boolean active, Instant validUntil) {
        RewardCode c = new RewardCode();
        c.setProgram(RewardProgram.PARTNER);
        c.setActive(active);
        c.setValidFrom(Instant.now().minus(10, ChronoUnit.DAYS));
        c.setValidUntil(validUntil);
        return c;
    }

    @Test
    @DisplayName("isPartner: a live PARTNER code grants the badge")
    void liveCodeGrantsBadge() {
        when(codeRepository.findByOwnerUserIdAndProgram(3L, RewardProgram.PARTNER))
                .thenReturn(Optional.of(code(true, null)));

        assertThat(service.isPartner(user(3, true), null)).isTrue();
    }

    @Test
    @DisplayName("V556 partnerTier: a badge-carrying partner shows their tier in lower case; no badge, no tier (never looked up)")
    void partnerTierOnlyForBadgeCarriers() {
        when(codeRepository.findByOwnerUserIdAndProgram(3L, RewardProgram.PARTNER))
                .thenReturn(Optional.of(code(true, null)));
        when(tierService.tierOf(3L)).thenReturn(com.apimarketplace.auth.domain.PartnerTier.GOLD);
        assertThat(service.partnerTier(user(3, true), null)).isEqualTo("gold");

        when(codeRepository.findByOwnerUserIdAndProgram(4L, RewardProgram.PARTNER))
                .thenReturn(Optional.of(code(false, null)));
        assertThat(service.partnerTier(user(4, true), null)).isNull();
        verify(tierService, never()).tierOf(4L);
    }

    @Test
    @DisplayName("isPartner: a disabled or expired code, a disabled account or no code at all grants nothing")
    void deadCodesGrantNothing() {
        when(codeRepository.findByOwnerUserIdAndProgram(3L, RewardProgram.PARTNER))
                .thenReturn(Optional.of(code(false, null)));
        assertThat(service.isPartner(user(3, true), null)).isFalse();

        when(codeRepository.findByOwnerUserIdAndProgram(3L, RewardProgram.PARTNER))
                .thenReturn(Optional.of(code(true, Instant.now().minus(1, ChronoUnit.DAYS))));
        assertThat(service.isPartner(user(3, true), null)).isFalse();

        when(codeRepository.findByOwnerUserIdAndProgram(3L, RewardProgram.PARTNER))
                .thenReturn(Optional.of(code(true, null)));
        assertThat(service.isPartner(user(3, false), null)).isFalse();

        when(codeRepository.findByOwnerUserIdAndProgram(4L, RewardProgram.PARTNER)).thenReturn(Optional.empty());
        assertThat(service.isPartner(user(4, true), null)).isFalse();
    }

    @Test
    @DisplayName("isPartner: a withdrawn (PRIVATE) profile carries no public badge")
    void privateProfileHidesBadge() {
        UserProfileEntity profile = new UserProfileEntity(3L);
        profile.setProfileVisibility("PRIVATE");

        assertThat(service.isPartner(user(3, true), profile)).isFalse();
        verifyNoInteractions(codeRepository);
    }

    @Test
    @DisplayName("self-hosted: no badge anywhere and no lookup at all")
    void selfHostedIsEmpty() {
        when(edition.isManagedCloud()).thenReturn(false);

        assertThat(service.isPartner(user(3, true), null)).isFalse();
        assertThat(service.partnersAmong(List.of(1L, 2L))).isEmpty();
        assertThat(service.partnerHandlesAmong(List.of("ada"))).isEmpty();
        verifyNoInteractions(codeRepository);
    }

    @Test
    @DisplayName("partnersAmong: live owners minus withdrawn profiles, one query each")
    void batchSubtractsPrivateProfiles() {
        when(codeRepository.findLivePartnerOwnerIdsIn(anyCollection(), any())).thenReturn(List.of(1L, 2L));
        when(profileRepository.findPrivateProfileIdsIn(anyCollection())).thenReturn(List.of(2L));

        assertThat(service.partnersAmong(List.of(1L, 2L, 3L))).containsExactly(1L);
    }

    @Test
    @DisplayName("partnersAmong: empty or null-only input answers empty without a query")
    void emptyInput() {
        assertThat(service.partnersAmong(List.of())).isEmpty();
        assertThat(service.partnersAmong(java.util.Arrays.asList((Long) null))).isEmpty();
        verifyNoInteractions(codeRepository);
    }

    @Test
    @DisplayName("partnerHandlesAmong: answers with the caller's spelling, only for partner owners")
    void handlesEchoSpelling() {
        when(profileRepository.findIdsByHandles(anyCollection())).thenReturn(List.<Object[]>of(
                new Object[]{"ada", 1L}, new Object[]{"linus", 2L}));
        when(codeRepository.findLivePartnerOwnerIdsIn(anyCollection(), any())).thenReturn(List.of(1L));
        when(profileRepository.findPrivateProfileIdsIn(anyCollection())).thenReturn(List.of());

        assertThat(service.partnerHandlesAmong(List.of("Ada", "linus"))).isEqualTo(Set.of("Ada"));
    }
}

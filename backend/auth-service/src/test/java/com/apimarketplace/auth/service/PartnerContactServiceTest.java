package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.RewardProgram;
import com.apimarketplace.auth.domain.RewardRedemption;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.dto.PublicProfileDto;
import com.apimarketplace.auth.repository.RewardRedemptionRepository;
import com.apimarketplace.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PartnerContactServiceTest {

    private static final long CLIENT = 7L;
    private static final long PARTNER = 42L;

    private RewardRedemptionRepository redemptions;
    private UserRepository users;
    private UserService userService;
    private PartnerContactService service;
    private User partner;

    @BeforeEach
    void setUp() {
        redemptions = mock(RewardRedemptionRepository.class);
        users = mock(UserRepository.class);
        userService = mock(UserService.class);
        service = new PartnerContactService(redemptions, users, userService);
        partner = new User();
        partner.setId(PARTNER);
        when(users.findById(PARTNER)).thenReturn(Optional.of(partner));
    }

    private void cameThrough(Long owner) {
        RewardRedemption r = new RewardRedemption();
        r.setRedeemerUserId(CLIENT);
        r.setProgram(RewardProgram.PARTNER);
        r.setOwnerUserId(owner);
        when(redemptions.findByRedeemerUserIdAndProgram(CLIENT, RewardProgram.PARTNER)).thenReturn(Optional.of(r));
    }

    @Test
    @DisplayName("the client's partner is the owner of the partner code they redeemed, with their public name, handle and tier")
    void partnerOfTheRedeemedCode() {
        cameThrough(PARTNER);
        when(userService.getPublicProfile(partner)).thenReturn(Optional.of(new PublicProfileDto(
                PARTNER, "Northwind Studio", "northwind", "/a.png", "bio", LocalDateTime.now(), false, false, true, "gold")));

        assertThat(service.myPartner(CLIENT)).contains(new PartnerContactService.MyPartner(PARTNER, "Northwind Studio", "northwind", "gold"));
    }

    @Test
    @DisplayName("a partner whose profile is private is still the client's contact, unnamed")
    void privateProfileKeepsTheContact() {
        cameThrough(PARTNER);
        when(userService.getPublicProfile(partner)).thenReturn(Optional.empty());

        assertThat(service.myPartner(CLIENT)).contains(new PartnerContactService.MyPartner(PARTNER, null, null, null));
    }

    @Test
    @DisplayName("no partner for a client who came through none, whose partner account is gone, or with no caller")
    void noPartner() {
        when(redemptions.findByRedeemerUserIdAndProgram(CLIENT, RewardProgram.PARTNER)).thenReturn(Optional.empty());
        assertThat(service.myPartner(CLIENT)).isEmpty();

        cameThrough(99L);
        when(users.findById(99L)).thenReturn(Optional.empty());
        assertThat(service.myPartner(CLIENT)).isEmpty();

        assertThat(service.myPartner(null)).isEmpty();
        verify(userService, never()).getPublicProfile(any());
    }

    @Test
    @DisplayName("never the client as their own partner, nor a redemption without an owner")
    void neverThemselves() {
        cameThrough(CLIENT);
        assertThat(service.myPartner(CLIENT)).isEmpty();

        cameThrough(null);
        assertThat(service.myPartner(CLIENT)).isEmpty();
    }
}

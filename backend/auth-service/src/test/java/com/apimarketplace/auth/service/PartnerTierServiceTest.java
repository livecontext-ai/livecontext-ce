package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.PartnerStanding;
import com.apimarketplace.auth.domain.PartnerTier;
import com.apimarketplace.auth.repository.PartnerCommissionRepository;
import com.apimarketplace.auth.repository.PartnerStandingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** V556 partner tiers: thresholds, the rate a line earns, the founder window, and "never down". */
@DisplayName("PartnerTierService")
class PartnerTierServiceTest {

    private static final long PARTNER = 7L;
    private static final Instant FOUNDER_UNTIL = Instant.parse("2027-01-01T00:00:00Z");

    private PartnerStandingRepository standingRepository;
    private PartnerCommissionRepository commissionRepository;

    private PartnerTierService at(Instant now) {
        return new PartnerTierService(standingRepository, commissionRepository, 3000, 4000, 5000,
                500_000L, 2_500_000L, "USD", 60, FOUNDER_UNTIL, Clock.fixed(now, ZoneOffset.UTC));
    }

    @BeforeEach
    void setUp() {
        standingRepository = mock(PartnerStandingRepository.class);
        commissionRepository = mock(PartnerCommissionRepository.class);
    }

    private void stored(PartnerTier tier, boolean founder) {
        PartnerStanding s = new PartnerStanding();
        s.setUserId(PARTNER);
        s.setTier(tier);
        s.setFounder(founder);
        when(standingRepository.findById(PARTNER)).thenReturn(Optional.of(s));
    }

    @Test
    @DisplayName("thresholds are inclusive: 4,999.99 is Silver, 5,000 is Gold, 25,000 is Platinum")
    void thresholdsAreInclusive() {
        PartnerTierService service = at(Instant.parse("2026-10-01T00:00:00Z"));

        assertThat(service.earnedTier(499_999L)).isEqualTo(PartnerTier.SILVER);
        assertThat(service.earnedTier(500_000L)).isEqualTo(PartnerTier.GOLD);
        assertThat(service.earnedTier(2_499_999L)).isEqualTo(PartnerTier.GOLD);
        assertThat(service.earnedTier(2_500_000L)).isEqualTo(PartnerTier.PLATINUM);
    }

    @Test
    @DisplayName("refresh counts revenue in the configured currency, lower-cased, on invoices older than the settle window")
    void refreshReadsTheConfiguredCurrencyAndSettleWindow() {
        PartnerTierService service = at(Instant.parse("2026-10-01T00:00:00Z"));

        service.refresh(PARTNER);

        // 60 days before now: an invoice counts only once a late refund or dispute is unlikely.
        verify(commissionRepository).sumSettledRevenue(eq(PARTNER), eq("usd"), eq(Instant.parse("2026-08-02T00:00:00Z")));
        assertThat(service.currency()).isEqualTo("usd");
        assertThat(service.settleDays()).isEqualTo(60);
    }

    @Test
    @DisplayName("refreshAll: one refresh per distinct partner, null ids skipped")
    void refreshAllSkipsDuplicatesAndNulls() {
        PartnerTierService service = at(Instant.parse("2026-10-01T00:00:00Z"));

        var out = service.refreshAll(java.util.Arrays.asList(PARTNER, null, PARTNER, 8L));

        assertThat(out).containsOnlyKeys(PARTNER, 8L);
        verify(commissionRepository, times(1)).sumSettledRevenue(eq(PARTNER), any(), any());
    }

    @Test
    @DisplayName("forCodes: partner codes only, each with its owner's tier and the higher of the two rates")
    void forCodesCoversPartnerCodesOnly() {
        PartnerTierService service = at(Instant.parse("2026-10-01T00:00:00Z"));
        stored(PartnerTier.GOLD, false);
        com.apimarketplace.auth.domain.RewardCode partner = new com.apimarketplace.auth.domain.RewardCode();
        partner.setId(1L);
        partner.setProgram(com.apimarketplace.auth.domain.RewardProgram.PARTNER);
        partner.setOwnerUserId(PARTNER);
        partner.setPayoutBps(5000);
        com.apimarketplace.auth.domain.RewardCode creator = new com.apimarketplace.auth.domain.RewardCode();
        creator.setId(2L);
        creator.setProgram(com.apimarketplace.auth.domain.RewardProgram.PROMO);

        var out = service.forCodes(List.of(partner, creator));

        assertThat(out).containsOnlyKeys(1L);
        assertThat(out.get(1L).standing().tier()).isEqualTo(PartnerTier.GOLD);
        // An old 50% code stays at 50%, above the Gold rate.
        assertThat(out.get(1L).effectiveRateBps()).isEqualTo(5000);
        assertThat(service.forCodes(List.of())).isEmpty();
    }

    @Test
    @DisplayName("tierOf: no id or no row reads as Silver")
    void tierOfDefaultsToSilver() {
        PartnerTierService service = at(Instant.parse("2026-10-01T00:00:00Z"));

        assertThat(service.tierOf(null)).isEqualTo(PartnerTier.SILVER);
        assertThat(service.tierOf(99L)).isEqualTo(PartnerTier.SILVER);
    }

    @Test
    @DisplayName("refresh writes only when a HIGHER tier was earned, and reports the next step")
    void refreshWritesOnlyUpward() {
        PartnerTierService service = at(Instant.parse("2026-10-01T00:00:00Z"));
        when(commissionRepository.sumSettledRevenue(eq(PARTNER), any(), any())).thenReturn(600_000L);
        stored(PartnerTier.GOLD, false);

        PartnerTierService.Standing s = service.refresh(PARTNER);

        assertThat(s.tier()).isEqualTo(PartnerTier.GOLD);
        assertThat(s.nextTier()).isEqualTo(PartnerTier.PLATINUM);
        assertThat(s.nextThresholdMinor()).isEqualTo(2_500_000L);
        assertThat(s.rateBps()).isEqualTo(4000);
        verify(standingRepository, never()).raise(any(), any(), anyBoolean(), any(), any());
    }

    @Test
    @DisplayName("a stored tier above what revenue earns is kept: a tier never goes down")
    void storedTierAboveRevenueIsKept() {
        PartnerTierService service = at(Instant.parse("2027-06-01T00:00:00Z"));
        stored(PartnerTier.PLATINUM, true);

        PartnerTierService.Standing s = service.refresh(PARTNER);

        assertThat(s.tier()).isEqualTo(PartnerTier.PLATINUM);
        assertThat(s.founder()).isTrue();
        assertThat(s.nextTier()).isNull();
        assertThat(s.nextThresholdMinor()).isNull();
        verify(standingRepository, never()).raise(any(), any(), anyBoolean(), any(), any());
    }

    @Test
    @DisplayName("the founder window closes at the deadline exactly: open one second before, closed at it")
    void founderWindowEdges() {
        assertThat(at(FOUNDER_UNTIL.minusSeconds(1)).founderOpen()).isTrue();
        assertThat(at(FOUNDER_UNTIL).founderOpen()).isFalse();
    }

    @Test
    @DisplayName("grantFounder: Platinum + founder while open, naming the admin; refused and writes nothing once closed")
    void grantFounder() {
        PartnerTierService open = at(Instant.parse("2026-12-31T23:00:00Z"));
        assertThat(open.grantFounder(PARTNER, 1L).success()).isTrue();
        verify(standingRepository).raise(eq(PARTNER), eq("PLATINUM"), eq(true), eq(1L), any());

        clearInvocations(standingRepository);
        PartnerTierService closed = at(FOUNDER_UNTIL);
        assertThat(closed.grantFounder(PARTNER, 1L).error()).isEqualTo("founder_closed");
        verify(standingRepository, never()).raise(any(), any(), anyBoolean(), any(), any());

        assertThat(open.grantFounder(null, 1L).error()).isEqualTo("missing_partner");
    }

    @Test
    @DisplayName("V557 endFounder: the founder returns to the tier the settled revenue earned (Gold here), at any time")
    void endFounderLowersToTheEarnedTier() {
        // After the founder window: ending is not limited by it, only naming is.
        PartnerTierService service = at(FOUNDER_UNTIL.plusSeconds(86_400));
        when(commissionRepository.sumSettledRevenue(eq(PARTNER), any(), any())).thenReturn(600_000L);
        when(standingRepository.endFounder(eq(PARTNER), eq("GOLD"), eq(1L), any())).thenReturn(1);

        PartnerTierService.Result result = service.endFounder(PARTNER, 1L);

        assertThat(result.success()).isTrue();
        assertThat(result.standing().tier()).isEqualTo(PartnerTier.GOLD);
        assertThat(result.standing().founder()).isFalse();
        verify(standingRepository).endFounder(eq(PARTNER), eq("GOLD"), eq(1L), any());
    }

    @Test
    @DisplayName("V557 endFounder: a partner who is not a founder is refused (not_founder); no partner is missing_partner")
    void endFounderRefusals() {
        PartnerTierService service = at(Instant.parse("2026-10-01T00:00:00Z"));
        when(standingRepository.endFounder(eq(PARTNER), any(), any(), any())).thenReturn(0);

        assertThat(service.endFounder(PARTNER, 1L).error()).isEqualTo("not_founder");
        assertThat(service.endFounder(null, 1L).error()).isEqualTo("missing_partner");
    }

    @Test
    @DisplayName("the rate a line earns is the higher of the code's rate and the tier's; no code rate means the tier's")
    void effectiveRate() {
        PartnerTierService service = at(Instant.parse("2026-10-01T00:00:00Z"));

        assertThat(service.effectiveRateBps(3000, PartnerTier.GOLD)).isEqualTo(4000);
        assertThat(service.effectiveRateBps(6000, PartnerTier.GOLD)).isEqualTo(6000);
        assertThat(service.effectiveRateBps(null, PartnerTier.PLATINUM)).isEqualTo(5000);
        assertThat(service.effectiveRateBps(0, null)).isEqualTo(3000);
    }

    @Test
    @DisplayName("tiers(): Silver, Gold, Platinum in order with their rate and threshold")
    void tiersInOrder() {
        List<PartnerTierService.TierTerm> tiers = at(Instant.parse("2026-10-01T00:00:00Z")).tiers();

        assertThat(tiers).extracting(PartnerTierService.TierTerm::tier)
                .containsExactly(PartnerTier.SILVER, PartnerTier.GOLD, PartnerTier.PLATINUM);
        assertThat(tiers).extracting(PartnerTierService.TierTerm::rateBps).containsExactly(3000, 4000, 5000);
        assertThat(tiers).extracting(PartnerTierService.TierTerm::thresholdMinor).containsExactly(0L, 500_000L, 2_500_000L);
    }

    @Test
    @DisplayName("a misconfiguration that would shortchange partners refuses to start")
    void invalidConfigurationRefused() {
        Clock clock = Clock.systemUTC();
        // Gold paying less than Silver.
        assertThatThrownBy(() -> new PartnerTierService(standingRepository, commissionRepository, 3000, 2000, 5000,
                500_000L, 2_500_000L, "usd", 60, FOUNDER_UNTIL, clock)).isInstanceOf(IllegalArgumentException.class);
        // Platinum reached before Gold.
        assertThatThrownBy(() -> new PartnerTierService(standingRepository, commissionRepository, 3000, 4000, 5000,
                500_000L, 400_000L, "usd", 60, FOUNDER_UNTIL, clock)).isInstanceOf(IllegalArgumentException.class);
        // A rate above 100%.
        assertThatThrownBy(() -> new PartnerTierService(standingRepository, commissionRepository, 3000, 4000, 10_001,
                500_000L, 2_500_000L, "usd", 60, FOUNDER_UNTIL, clock)).isInstanceOf(IllegalArgumentException.class);
        // A negative Silver rate.
        assertThatThrownBy(() -> new PartnerTierService(standingRepository, commissionRepository, -1, 4000, 5000,
                500_000L, 2_500_000L, "usd", 60, FOUNDER_UNTIL, clock)).isInstanceOf(IllegalArgumentException.class);
        // Gold reached with no revenue at all.
        assertThatThrownBy(() -> new PartnerTierService(standingRepository, commissionRepository, 3000, 4000, 5000,
                0L, 2_500_000L, "usd", 60, FOUNDER_UNTIL, clock)).isInstanceOf(IllegalArgumentException.class);
        // A negative settle window.
        assertThatThrownBy(() -> new PartnerTierService(standingRepository, commissionRepository, 3000, 4000, 5000,
                500_000L, 2_500_000L, "usd", -1, FOUNDER_UNTIL, clock)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("PartnerTier: ascending rank and the next tier up")
    void tierOrder() {
        assertThat(PartnerTier.GOLD.isAbove(PartnerTier.SILVER)).isTrue();
        assertThat(PartnerTier.GOLD.isAbove(PartnerTier.GOLD)).isFalse();
        assertThat(PartnerTier.SILVER.isAbove(null)).isTrue();
        assertThat(PartnerTier.SILVER.next()).isEqualTo(PartnerTier.GOLD);
        assertThat(PartnerTier.PLATINUM.next()).isNull();
    }
}

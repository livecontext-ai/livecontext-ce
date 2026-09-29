package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.*;
import com.apimarketplace.auth.repository.PartnerCommissionRepository;
import com.apimarketplace.auth.repository.RewardCodeRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.service.PartnerProgramAdminService.CreatorCodeRequest;
import com.apimarketplace.auth.service.PartnerProgramAdminService.PartnerCodeRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** V549 admin side: creating creator / partner codes from the configured defaults, and settling. */
class PartnerProgramAdminServiceTest {

    private RewardCodeRepository codeRepository;
    private PartnerCommissionRepository commissionRepository;
    private UserRepository userRepository;
    private PartnerProgramAdminService service;

    @BeforeEach
    void setUp() {
        codeRepository = mock(RewardCodeRepository.class);
        commissionRepository = mock(PartnerCommissionRepository.class);
        userRepository = mock(UserRepository.class);
        service = new PartnerProgramAdminService(codeRepository, commissionRepository, userRepository,
                "PRO", 90, 50_000, 1, 60, 10_000, 3000, 12, 14);
        when(codeRepository.findByCodeIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(codeRepository.saveAndFlush(any(RewardCode.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("creator code from defaults: single-use PROMO, PRO for 90 days + 50,000 credits at redeem time, valid 60 days")
    void creatorCodeUsesDefaults() {
        var result = service.createCreatorCode(new CreatorCodeRequest(null, "Techdox", null, null, null, null, null));

        assertThat(result.success()).isTrue();
        RewardCode c = result.code();
        assertThat(c.getProgram()).isEqualTo(RewardProgram.PROMO);
        assertThat(c.getOwnerUserId()).isNull();
        assertThat(c.getBenefitKind()).isEqualTo(BenefitKind.CREDIT_GRANT);
        assertThat(c.getBenefitTrigger()).isEqualTo(BenefitTrigger.REDEEM_TIME);
        assertThat(c.getBenefitAmount()).isEqualTo(50_000);
        assertThat(c.getBenefitPlanCode()).isEqualTo("PRO");
        assertThat(c.getBenefitPlanDays()).isEqualTo(90);
        assertThat(c.getCapScope()).isEqualTo(CapScope.GLOBAL);
        assertThat(c.getCapLimit()).isEqualTo(1);
        assertThat(c.getValidUntil()).isCloseTo(Instant.now().plus(60, ChronoUnit.DAYS), within(1, ChronoUnit.MINUTES));
        assertThat(c.getCode()).startsWith("LC-").hasSize(11);
        assertThat(c.getLabel()).isEqualTo("Techdox");
    }

    @Test
    @DisplayName("creator code: every default can be overridden per code, including 'no plan, credits only'")
    void creatorCodeOverrides() {
        var result = service.createCreatorCode(new CreatorCodeRequest("launch-week", null, "none", null, 20_000, 50, 0));

        RewardCode c = result.code();
        assertThat(c.getCode()).isEqualTo("LAUNCH-WEEK");
        assertThat(c.getBenefitPlanCode()).isNull();
        assertThat(c.getBenefitPlanDays()).isZero();
        assertThat(c.getBenefitAmount()).isEqualTo(20_000);
        assertThat(c.getCapLimit()).isEqualTo(50);
        assertThat(c.getValidUntil()).isNull();
    }

    @Test
    @DisplayName("creator code: an unsupported plan, an empty benefit or a malformed code is refused")
    void creatorCodeValidation() {
        assertThat(service.createCreatorCode(new CreatorCodeRequest(null, null, "ENTERPRISE", null, null, null, null)).error())
                .isEqualTo("unsupported_plan");
        assertThat(service.createCreatorCode(new CreatorCodeRequest(null, null, "NONE", null, 0, null, null)).error())
                .isEqualTo("empty_benefit");
        assertThat(service.createCreatorCode(new CreatorCodeRequest("a b", null, null, null, null, null, null)).error())
                .isEqualTo("invalid_code_format");
        verify(codeRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("a code already in use (any case) is refused with code_taken")
    void codeTaken() {
        when(codeRepository.findByCodeIgnoreCase("TECHDOX")).thenReturn(Optional.of(new RewardCode()));

        assertThat(service.createCreatorCode(new CreatorCodeRequest("techdox", null, null, null, null, null, null)).error())
                .isEqualTo("code_taken");
    }

    @Test
    @DisplayName("partner code from defaults: owned PARTNER code, 10,000 credits for the audience, 30% for 12 months, 14-day hold")
    void partnerCodeUsesDefaults() {
        when(userRepository.findById(99L)).thenReturn(Optional.of(new User()));
        when(codeRepository.findByOwnerUserIdAndProgram(99L, RewardProgram.PARTNER)).thenReturn(Optional.empty());

        var result = service.createPartnerCode(new PartnerCodeRequest(99L, "TECHDOX", null, null, null, null, null, null, null));

        RewardCode c = result.code();
        assertThat(c.getProgram()).isEqualTo(RewardProgram.PARTNER);
        assertThat(c.getOwnerUserId()).isEqualTo(99L);
        assertThat(c.getBenefitTrigger()).isEqualTo(BenefitTrigger.REDEEM_TIME);
        assertThat(c.getBenefitAmount()).isEqualTo(10_000);
        assertThat(c.getOwnerRewardKind()).isEqualTo(OwnerRewardKind.PARTNER_PAYOUT);
        assertThat(c.getPayoutBps()).isEqualTo(3000);
        assertThat(c.getPayoutMonths()).isEqualTo(12);
        assertThat(c.getHoldDays()).isEqualTo(14);
        assertThat(c.getCapScope()).isEqualTo(CapScope.NONE);
        assertThat(c.getValidUntil()).isNull();
    }

    @Test
    @DisplayName("partner code: unknown user, a second code for the same partner, or a share above 100% is refused")
    void partnerCodeValidation() {
        assertThat(service.createPartnerCode(new PartnerCodeRequest(1L, null, null, null, null, null, null, null, null)).error())
                .isEqualTo("user_not_found");

        when(userRepository.findById(99L)).thenReturn(Optional.of(new User()));
        when(codeRepository.findByOwnerUserIdAndProgram(99L, RewardProgram.PARTNER)).thenReturn(Optional.of(new RewardCode()));
        assertThat(service.createPartnerCode(new PartnerCodeRequest(99L, null, null, null, null, null, null, null, null)).error())
                .isEqualTo("partner_already_has_code");

        when(codeRepository.findByOwnerUserIdAndProgram(99L, RewardProgram.PARTNER)).thenReturn(Optional.empty());
        assertThat(service.createPartnerCode(new PartnerCodeRequest(99L, null, null, null, 10_001, null, null, null, null)).error())
                .isEqualTo("invalid_values");
        verify(codeRepository, never()).saveAndFlush(any());
    }

    private PartnerCommission line(PartnerCommission.Status status, Instant dueAt, long amount) {
        PartnerCommission c = new PartnerCommission();
        c.setRewardCodeId(400L);
        c.setStatus(status);
        c.setDueAt(dueAt);
        c.setCurrency("usd");
        c.setCommissionMinor(amount);
        return c;
    }

    @Test
    @DisplayName("totals split lines into on-hold / payable / paid / voided per currency")
    void totalsBuckets() {
        Instant now = Instant.now();
        var totals = PartnerProgramAdminService.totals(List.of(
                line(PartnerCommission.Status.HOLD, now.plusSeconds(3600), 100),
                line(PartnerCommission.Status.HOLD, now.minusSeconds(3600), 200),
                line(PartnerCommission.Status.PAID, now.minusSeconds(9999), 300),
                line(PartnerCommission.Status.VOID, now.minusSeconds(9999), 400)), now);

        assertThat(totals.onHold()).isEqualTo(Map.of("usd", 100L));
        assertThat(totals.payable()).isEqualTo(Map.of("usd", 200L));
        assertThat(totals.paid()).isEqualTo(Map.of("usd", 300L));
        assertThat(totals.voided()).isEqualTo(Map.of("usd", 400L));
    }

    @Test
    @DisplayName("mark-paid settles only lines past their refund window; lines still on hold stay owed later")
    void markPaidSettlesOnlyPayable() {
        Instant now = Instant.now();
        PartnerCommission payable = line(PartnerCommission.Status.HOLD, now.minusSeconds(60), 200);
        PartnerCommission onHold = line(PartnerCommission.Status.HOLD, now.plusSeconds(3600), 100);
        PartnerCommission voided = line(PartnerCommission.Status.VOID, now.minusSeconds(60), 50);
        payable.setId(1L);
        when(commissionRepository.findByRewardCodeIdIn(List.of(400L))).thenReturn(List.of(payable, onHold, voided));
        when(commissionRepository.markPaidIfOnHold(eq(1L), any(), eq(42L))).thenReturn(1);

        var settled = service.markPayablePaid(400L, 42L);

        assertThat(settled).containsExactly(payable);
        assertThat(payable.getStatus()).isEqualTo(PartnerCommission.Status.PAID);
        assertThat(payable.getPaidAt()).isNotNull();
        assertThat(payable.getPaidByUserId()).isEqualTo(42L);
        assertThat(onHold.getStatus()).isEqualTo(PartnerCommission.Status.HOLD);
        assertThat(voided.getStatus()).isEqualTo(PartnerCommission.Status.VOID);
    }

    @Test
    @DisplayName("partner code: an optional use cap becomes a hard GLOBAL cap (bounds a sign-up farm on a public link)")
    void partnerCodeUseCap() {
        when(userRepository.findById(99L)).thenReturn(Optional.of(new User()));
        when(codeRepository.findByOwnerUserIdAndProgram(99L, RewardProgram.PARTNER)).thenReturn(Optional.empty());

        RewardCode c = service.createPartnerCode(new PartnerCodeRequest(99L, null, null, null, null, null, null, null, 200)).code();

        assertThat(c.getCapScope()).isEqualTo(CapScope.GLOBAL);
        assertThat(c.getCapLimit()).isEqualTo(200);
        assertThat(service.createPartnerCode(new PartnerCodeRequest(99L, null, null, null, null, null, null, null, 0)).error())
                .isEqualTo("invalid_values");
    }

    @Test
    @DisplayName("report: reads only the program's codes, with the partner's email and its commission totals")
    void reportReadsProgramCodesOnly() {
        RewardCode partner = new RewardCode();
        partner.setId(400L);
        partner.setProgram(RewardProgram.PARTNER);
        partner.setOwnerUserId(99L);
        partner.setCurrentRedemptions(3);
        when(codeRepository.findPartnerProgramCodes()).thenReturn(List.of(partner));
        User owner = new User();
        owner.setEmail("techdox@example.com");
        when(userRepository.findById(99L)).thenReturn(Optional.of(owner));
        PartnerCommission paid = line(PartnerCommission.Status.PAID, Instant.now().minusSeconds(60), 720);
        paid.setCustomerUserId(7L);
        when(commissionRepository.findByRewardCodeIdIn(List.of(400L))).thenReturn(List.of(paid));

        var report = service.report();

        assertThat(report).hasSize(1);
        assertThat(report.get(0).ownerEmail()).isEqualTo("techdox@example.com");
        assertThat(report.get(0).redemptions()).isEqualTo(3);
        assertThat(report.get(0).payingCustomers()).isEqualTo(1);
        assertThat(report.get(0).commissions().paid()).isEqualTo(Map.of("usd", 720L));
        verify(codeRepository, never()).findAll();
    }

    @Test
    @DisplayName("setActive: disables an existing code, and reports an unknown one")
    void setActive() {
        RewardCode c = new RewardCode();
        c.setActive(true);
        when(codeRepository.findById(400L)).thenReturn(Optional.of(c));
        when(codeRepository.findById(401L)).thenReturn(Optional.empty());

        assertThat(service.setActive(400L, false)).isTrue();
        assertThat(c.isActive()).isFalse();
        assertThat(service.setActive(401L, false)).isFalse();
    }

    @Test
    @DisplayName("mark-paid never pays a line a refund voided in between (the conditional update wins, 0 rows)")
    void markPaidLosesToConcurrentVoid() {
        PartnerCommission payable = line(PartnerCommission.Status.HOLD, Instant.now().minusSeconds(60), 200);
        payable.setId(1L);
        when(commissionRepository.findByRewardCodeIdIn(List.of(400L))).thenReturn(List.of(payable));
        when(commissionRepository.markPaidIfOnHold(eq(1L), any(), eq(42L))).thenReturn(0);

        assertThat(service.markPayablePaid(400L, 42L)).isEmpty();
        assertThat(payable.getStatus()).isEqualTo(PartnerCommission.Status.HOLD);
    }
}

package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.PartnerApplication;
import com.apimarketplace.auth.domain.PartnerCommission;
import com.apimarketplace.auth.domain.RewardCode;
import com.apimarketplace.auth.domain.RewardProgram;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.PartnerApplicationRepository;
import com.apimarketplace.auth.repository.PartnerCommissionRepository;
import com.apimarketplace.auth.repository.PartnerTermsAcceptanceRepository;
import com.apimarketplace.auth.repository.PartnerStandingRepository;
import com.apimarketplace.auth.domain.PartnerTier;
import com.apimarketplace.auth.repository.RewardCodeRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.service.PartnerProgramService.ApplicationForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Parameter;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** V553 partner-facing side: public terms, the partner dashboard, and the application flow. */
class PartnerProgramServiceTest {

    private static final long USER = 7L;
    private static final long ADMIN = 1L;
    private static final String TERMS_VERSION = "2026-10-01";
    private static final PartnerTermsService.Evidence EVIDENCE = new PartnerTermsService.Evidence("203.0.113.7", "Mozilla/5.0");

    private PartnerProgramAdminService adminService;
    private PartnerApplicationRepository applicationRepository;
    private RewardCodeRepository codeRepository;
    private PartnerCommissionRepository commissionRepository;
    private UserRepository userRepository;
    private PartnerStandingRepository standingRepository;
    private PartnerTermsAcceptanceRepository termsRepository;
    private PartnerTermsService termsService;
    private PartnerProgramService service;

    /** Founder window: open until 2027-01-01, the clock is set before or after it. */
    private static final Instant FOUNDER_UNTIL = Instant.parse("2027-01-01T00:00:00Z");

    private PartnerTierService tierService(Instant now) {
        return new PartnerTierService(standingRepository, commissionRepository, 3000, 4000, 5000,
                500_000L, 2_500_000L, "usd", 60, FOUNDER_UNTIL, java.time.Clock.fixed(now, java.time.ZoneOffset.UTC));
    }

    @BeforeEach
    void setUp() {
        codeRepository = mock(RewardCodeRepository.class);
        commissionRepository = mock(PartnerCommissionRepository.class);
        userRepository = mock(UserRepository.class);
        applicationRepository = mock(PartnerApplicationRepository.class);
        standingRepository = mock(PartnerStandingRepository.class);
        // A REAL admin service, so approval goes through the actual V549 code creation.
        termsRepository = mock(PartnerTermsAcceptanceRepository.class);
        termsService = new PartnerTermsService(termsRepository, TERMS_VERSION);
        adminService = new PartnerProgramAdminService(codeRepository, commissionRepository, userRepository, termsService,
                "PRO", 90, 50_000, 1, 60, 10_000, 3000, 12, 14);
        service = new PartnerProgramService(adminService, applicationRepository, codeRepository,
                commissionRepository, userRepository, tierService(Instant.parse("2026-10-01T00:00:00Z")), termsService);
        when(codeRepository.findByCodeIgnoreCase(any())).thenReturn(Optional.empty());
        when(codeRepository.findByOwnerUserIdAndProgram(any(), any())).thenReturn(Optional.empty());
        when(codeRepository.saveAndFlush(any(RewardCode.class))).thenAnswer(inv -> {
            RewardCode c = inv.getArgument(0);
            if (c.getId() == null) c.setId(99L);
            return c;
        });
        when(applicationRepository.saveAndFlush(any(PartnerApplication.class))).thenAnswer(inv -> inv.getArgument(0));
        when(applicationRepository.save(any(PartnerApplication.class))).thenAnswer(inv -> inv.getArgument(0));
        when(applicationRepository.findFirstByUserIdOrderByCreatedAtDescIdDesc(any())).thenReturn(Optional.empty());
    }

    private static RewardCode liveCode(int bps) {
        RewardCode c = new RewardCode();
        c.setId(99L);
        c.setCode("AGENCY-X");
        c.setProgram(RewardProgram.PARTNER);
        c.setOwnerUserId(USER);
        c.setPayoutBps(bps);
        c.setPayoutMonths(12);
        c.setHoldDays(14);
        c.setBenefitAmount(10_000);
        c.setActive(true);
        c.setValidFrom(Instant.now().minus(1, ChronoUnit.DAYS));
        return c;
    }

    private static PartnerCommission line(long customer, long commission, PartnerCommission.Status status,
                                          Instant paidAt, Instant dueAt) {
        PartnerCommission l = new PartnerCommission();
        l.setRewardCodeId(99L);
        l.setPartnerUserId(USER);
        l.setCustomerUserId(customer);
        l.setCurrency("eur");
        l.setBaseAmountMinor(commission * 2);
        l.setCommissionMinor(commission);
        l.setStatus(status);
        l.setInvoicePaidAt(paidAt);
        l.setDueAt(dueAt);
        return l;
    }

    private static PartnerApplication application(PartnerApplication.Status status) {
        PartnerApplication a = new PartnerApplication();
        a.setId(5L);
        a.setUserId(USER);
        a.setStatus(status);
        a.setCompanyName("Acme Automation");
        return a;
    }

    @Test
    @DisplayName("the public terms are the reward.partner.* defaults new codes start from, in percent")
    void termsComeFromDefaults() {
        var terms = service.terms();

        assertThat(terms.commissionPercent()).isEqualTo(30.0);
        assertThat(terms.commissionMonths()).isEqualTo(12);
        assertThat(terms.holdDays()).isEqualTo(14);
        assertThat(terms.audienceCredits()).isEqualTo(10_000);
    }

    @Test
    @DisplayName("V556: the terms list the three tiers with their rate and threshold, and the founder window")
    void termsListTheTiers() {
        var terms = service.terms();

        assertThat(terms.tiers()).extracting(PartnerTierService.TierTerm::tier)
                .containsExactly(PartnerTier.SILVER, PartnerTier.GOLD, PartnerTier.PLATINUM);
        assertThat(terms.tiers()).extracting(PartnerTierService.TierTerm::rateBps).containsExactly(3000, 4000, 5000);
        assertThat(terms.tiers()).extracting(PartnerTierService.TierTerm::thresholdMinor)
                .containsExactly(0L, 500_000L, 2_500_000L);
        assertThat(terms.tierCurrency()).isEqualTo("usd");
        assertThat(terms.founderUntil()).isEqualTo(FOUNDER_UNTIL);
        assertThat(terms.founderOpen()).isTrue();
    }

    @Test
    @DisplayName("regression V556: a new partner code starts on the Silver rate (30%), and both services agree on it")
    void defaultCommissionIsTheSilverRate() {
        assertThat(valueDefault(PartnerProgramAdminService.class, "${reward.partner.commission-bps:"))
                .isEqualTo("${reward.partner.commission-bps:3000}");
        // The Silver rate of the tiers IS the rate a code is created with: one property, one default.
        assertThat(valueDefault(PartnerTierService.class, "${reward.partner.commission-bps:"))
                .isEqualTo("${reward.partner.commission-bps:3000}");
    }

    private static String valueDefault(Class<?> type, String prefix) {
        for (var ctor : type.getConstructors()) {
            for (Parameter p : ctor.getParameters()) {
                var v = p.getAnnotation(org.springframework.beans.factory.annotation.Value.class);
                if (v != null && v.value().startsWith(prefix)) return v.value();
            }
        }
        throw new AssertionError("no " + prefix + " on " + type.getSimpleName());
    }

    @Test
    @DisplayName("months: the last 12 calendar months oldest first, current one included, voided lines and older months left out")
    void monthlyEarnings() {
        Instant now = Instant.parse("2026-10-15T12:00:00Z");
        PartnerCommission october = line(1, 300, PartnerCommission.Status.HOLD, Instant.parse("2026-10-02T00:00:00Z"), null);
        PartnerCommission octoberPaid = line(2, 200, PartnerCommission.Status.PAID, Instant.parse("2026-10-14T23:59:59Z"), null);
        PartnerCommission octoberUsd = line(3, 50, PartnerCommission.Status.HOLD, Instant.parse("2026-10-03T00:00:00Z"), null);
        octoberUsd.setCurrency("usd");
        PartnerCommission voided = line(4, 999, PartnerCommission.Status.VOID, Instant.parse("2026-10-05T00:00:00Z"), null);
        PartnerCommission november2025 = line(5, 70, PartnerCommission.Status.PAID, Instant.parse("2025-11-01T00:00:00Z"), null);
        PartnerCommission tooOld = line(6, 40, PartnerCommission.Status.PAID, Instant.parse("2025-10-31T23:59:59Z"), null);

        List<PartnerProgramService.Month> months = PartnerProgramService.months(
                List.of(october, octoberPaid, octoberUsd, voided, november2025, tooOld), now);

        assertThat(months).hasSize(12);
        assertThat(months.get(0).month()).isEqualTo("2025-11");
        assertThat(months.get(11).month()).isEqualTo("2026-10");
        // Every state counts (on hold, paid), per currency; the voided line does not.
        assertThat(months.get(11).commissions()).containsExactlyInAnyOrderEntriesOf(Map.of("eur", 500L, "usd", 50L));
        assertThat(months.get(0).commissions()).containsExactlyEntriesOf(Map.of("eur", 70L));
        // A month with nothing earned is present, empty: the chart has no holes.
        assertThat(months.get(5).commissions()).isEmpty();
        // October 2025 is outside the 12-month window.
        assertThat(months).noneMatch(m -> m.month().equals("2025-10"));
    }

    @Test
    @DisplayName("months: a line with no invoice date, or dated after the current month, is not charted")
    void monthlyEarningsSkipsUndatedAndFutureLines() {
        Instant now = Instant.parse("2026-10-15T12:00:00Z");
        PartnerCommission undated = line(1, 300, PartnerCommission.Status.HOLD, null, null);
        PartnerCommission nextMonth = line(2, 200, PartnerCommission.Status.HOLD, Instant.parse("2026-11-01T00:00:00Z"), null);
        PartnerCommission october = line(3, 50, PartnerCommission.Status.PAID, Instant.parse("2026-10-01T00:00:00Z"), null);

        List<PartnerProgramService.Month> months = PartnerProgramService.months(List.of(undated, nextMonth, october), now);

        assertThat(months).hasSize(12);
        assertThat(months.get(11).commissions()).containsExactlyEntriesOf(Map.of("eur", 50L));
        assertThat(months).noneMatch(m -> m.month().equals("2026-11"));
        assertThat(months.stream().mapToLong(m -> m.commissions().getOrDefault("eur", 0L)).sum()).isEqualTo(50L);
    }

    @Test
    @DisplayName("code offer: a live partner code tells a visitor its credits, never its owner")
    void codeOfferOfALivePartnerCode() {
        when(codeRepository.findByCodeIgnoreCase("agency-x")).thenReturn(Optional.of(liveCode(3000)));

        var offer = service.codeOffer(" agency-x ");

        assertThat(offer).contains(new PartnerProgramService.CodeOffer("AGENCY-X", 10_000));
    }

    @Test
    @DisplayName("code offer: nothing for a creator code, a disabled, expired or used-up partner code, or junk input")
    void codeOfferOnlyForARedeemablePartnerCode() {
        RewardCode creator = liveCode(3000);
        creator.setProgram(RewardProgram.PROMO);
        when(codeRepository.findByCodeIgnoreCase("CREATOR")).thenReturn(Optional.of(creator));
        RewardCode disabled = liveCode(3000);
        disabled.setActive(false);
        when(codeRepository.findByCodeIgnoreCase("OFF")).thenReturn(Optional.of(disabled));
        RewardCode expired = liveCode(3000);
        expired.setValidUntil(Instant.now().minus(1, ChronoUnit.HOURS));
        when(codeRepository.findByCodeIgnoreCase("OLD")).thenReturn(Optional.of(expired));
        RewardCode usedUp = liveCode(3000);
        usedUp.setCapScope(com.apimarketplace.auth.domain.CapScope.GLOBAL);
        usedUp.setCapLimit(1);
        usedUp.setCurrentRedemptions(1);
        when(codeRepository.findByCodeIgnoreCase("FULL")).thenReturn(Optional.of(usedUp));

        assertThat(service.codeOffer("CREATOR")).isEmpty();
        assertThat(service.codeOffer("OFF")).isEmpty();
        assertThat(service.codeOffer("OLD")).isEmpty();
        assertThat(service.codeOffer("FULL")).isEmpty();
        assertThat(service.codeOffer("UNKNOWN")).isEmpty();
        assertThat(service.codeOffer("  ")).isEmpty();
        assertThat(service.codeOffer(null)).isEmpty();
        assertThat(service.codeOffer("X".repeat(65))).isEmpty();
    }

    @Nested
    @DisplayName("dashboard")
    class DashboardState {

        @Test
        @DisplayName("never applied: state none, terms only")
        void none() {
            var d = service.dashboard(USER);

            assertThat(d.state()).isEqualTo("none");
            assertThat(d.code()).isNull();
            assertThat(d.terms().commissionPercent()).isEqualTo(30.0);
            assertThat(d.standing()).isNull();
            // No code, no earnings to chart.
            assertThat(d.months()).isEmpty();
        }

        @Test
        @DisplayName("pending or rejected application with no code: the dashboard reports it")
        void pendingAndRejected() {
            when(applicationRepository.findFirstByUserIdOrderByCreatedAtDescIdDesc(USER))
                    .thenReturn(Optional.of(application(PartnerApplication.Status.PENDING)));
            assertThat(service.dashboard(USER).state()).isEqualTo("pending");

            when(applicationRepository.findFirstByUserIdOrderByCreatedAtDescIdDesc(USER))
                    .thenReturn(Optional.of(application(PartnerApplication.Status.REJECTED)));
            assertThat(service.dashboard(USER).state()).isEqualTo("rejected");
        }

        @Test
        @DisplayName("an approved application whose code was deleted reads none, so the user can apply again")
        void approvedWithoutCodeIsNone() {
            when(applicationRepository.findFirstByUserIdOrderByCreatedAtDescIdDesc(USER))
                    .thenReturn(Optional.of(application(PartnerApplication.Status.APPROVED)));

            assertThat(service.dashboard(USER).state()).isEqualTo("none");
        }

        @Test
        @DisplayName("live code: active, totals bucketed like the admin report, paying customers exclude voided ones")
        void activeWithTotals() {
            Instant now = Instant.now();
            when(codeRepository.findByOwnerUserIdAndProgram(USER, RewardProgram.PARTNER))
                    .thenReturn(Optional.of(liveCode(5000)));
            when(commissionRepository.findByRewardCodeIdIn(List.of(99L))).thenReturn(List.of(
                    line(10, 500, PartnerCommission.Status.HOLD, now.minus(1, ChronoUnit.DAYS), now.plus(13, ChronoUnit.DAYS)),
                    line(11, 700, PartnerCommission.Status.HOLD, now.minus(20, ChronoUnit.DAYS), now.minus(6, ChronoUnit.DAYS)),
                    line(10, 300, PartnerCommission.Status.PAID, now.minus(40, ChronoUnit.DAYS), now.minus(26, ChronoUnit.DAYS)),
                    line(12, 900, PartnerCommission.Status.VOID, now.minus(5, ChronoUnit.DAYS), now.plus(9, ChronoUnit.DAYS))));

            var d = service.dashboard(USER);

            assertThat(d.state()).isEqualTo("active");
            assertThat(d.payingCustomers()).isEqualTo(2);
            assertThat(d.commissions().onHold()).isEqualTo(Map.of("eur", 500L));
            assertThat(d.commissions().payable()).isEqualTo(Map.of("eur", 700L));
            assertThat(d.commissions().paid()).isEqualTo(Map.of("eur", 300L));
            assertThat(d.commissions().voided()).isEqualTo(Map.of("eur", 900L));
            assertThat(d.lines()).extracting(PartnerProgramService.Line::status)
                    .containsExactly("on_hold", "void", "payable", "paid");
            assertThat(d.standing().tier()).isEqualTo(PartnerTier.SILVER);
            // The code was created at 50%: above the Silver rate, so that is what it earns.
            assertThat(d.commissionPercent()).isEqualTo(50.0);
            // The chart's year, built from the same lines: 12 months, the voided line left out
            // wherever the month boundaries fall today (the window itself is pinned by months()).
            assertThat(d.months()).hasSize(12);
            assertThat(d.months().stream().mapToLong(m -> m.commissions().getOrDefault("eur", 0L)).sum()).isEqualTo(1500L);
        }

        @Test
        @DisplayName("V556: the dashboard shows the tier reached, the settled revenue and the next threshold")
        void standingAndProgress() {
            when(codeRepository.findByOwnerUserIdAndProgram(USER, RewardProgram.PARTNER))
                    .thenReturn(Optional.of(liveCode(3000)));
            when(commissionRepository.sumSettledRevenue(eq(USER), eq("usd"), any())).thenReturn(600_000L);

            var d = service.dashboard(USER);

            assertThat(d.standing().tier()).isEqualTo(PartnerTier.GOLD);
            assertThat(d.standing().revenueMinor()).isEqualTo(600_000L);
            assertThat(d.standing().nextTier()).isEqualTo(PartnerTier.PLATINUM);
            assertThat(d.standing().nextThresholdMinor()).isEqualTo(2_500_000L);
            assertThat(d.commissionPercent()).isEqualTo(40.0);
            verify(standingRepository).raise(eq(USER), eq("GOLD"), eq(false), isNull(), any());
        }

        @Test
        @DisplayName("a disabled code reads inactive; the list is capped at the latest lines")
        void inactiveAndCapped() {
            RewardCode c = liveCode(5000);
            c.setActive(false);
            when(codeRepository.findByOwnerUserIdAndProgram(USER, RewardProgram.PARTNER)).thenReturn(Optional.of(c));
            List<PartnerCommission> many = new ArrayList<>();
            Instant base = Instant.now().minus(400, ChronoUnit.DAYS);
            for (int i = 0; i < PartnerProgramService.DASHBOARD_LINES + 10; i++) {
                many.add(line(20 + i, 10, PartnerCommission.Status.PAID, base.plus(i, ChronoUnit.DAYS), base));
            }
            when(commissionRepository.findByRewardCodeIdIn(List.of(99L))).thenReturn(many);

            var d = service.dashboard(USER);

            assertThat(d.state()).isEqualTo("inactive");
            assertThat(d.lines()).hasSize(PartnerProgramService.DASHBOARD_LINES);
            assertThat(d.lines().get(0).invoicePaidAt())
                    .isEqualTo(base.plus(PartnerProgramService.DASHBOARD_LINES + 9, ChronoUnit.DAYS));
        }
    }

    @Nested
    @DisplayName("apply")
    class Apply {

        @Test
        @DisplayName("stores a trimmed PENDING application")
        void storesPending() {
            var out = service.apply(USER, new ApplicationForm("  Acme  ", " https://acme.io ", " SMBs ", " hi ", TERMS_VERSION), EVIDENCE);

            assertThat(out.success()).isTrue();
            PartnerApplication a = out.application();
            assertThat(a.getStatus()).isEqualTo(PartnerApplication.Status.PENDING);
            assertThat(a.getUserId()).isEqualTo(USER);
            assertThat(a.getCompanyName()).isEqualTo("Acme");
            assertThat(a.getWebsite()).isEqualTo("https://acme.io");
            assertThat(a.getAudience()).isEqualTo("SMBs");
            assertThat(a.getMessage()).isEqualTo("hi");
            // The database dates the row; the response must not carry a null date meanwhile.
            assertThat(a.getCreatedAt()).isNotNull();
        }

        @Test
        @DisplayName("refuses a blank company, an over-long field and a website that is not http(s)")
        void validates() {
            assertThat(service.apply(USER, new ApplicationForm("  ", null, null, null, TERMS_VERSION), EVIDENCE).error()).isEqualTo("missing_company");
            assertThat(service.apply(USER, null, EVIDENCE).error()).isEqualTo("missing_body");
            assertThat(service.apply(USER, new ApplicationForm("x".repeat(121), null, null, null, TERMS_VERSION), EVIDENCE).error())
                    .isEqualTo("too_long");
            assertThat(service.apply(USER, new ApplicationForm("Acme", null, null, "m".repeat(2001), TERMS_VERSION), EVIDENCE).error())
                    .isEqualTo("too_long");
            assertThat(service.apply(USER, new ApplicationForm("Acme", "javascript:alert(1)", null, null, TERMS_VERSION), EVIDENCE).error())
                    .isEqualTo("invalid_website");
            assertThat(service.apply(USER, new ApplicationForm("Acme", "https://" + "a".repeat(250), null, null, TERMS_VERSION), EVIDENCE).error())
                    .isEqualTo("too_long");
            assertThat(service.apply(USER, new ApplicationForm("Acme", null, "x".repeat(501), null, TERMS_VERSION), EVIDENCE).error())
                    .isEqualTo("too_long");
            assertThat(service.apply(null, new ApplicationForm("Acme", null, null, null, TERMS_VERSION), EVIDENCE).error())
                    .isEqualTo("unauthorized");
            verify(applicationRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("an existing partner or an open application cannot apply again")
        void refusesDuplicates() {
            when(codeRepository.findByOwnerUserIdAndProgram(USER, RewardProgram.PARTNER))
                    .thenReturn(Optional.of(liveCode(5000)));
            assertThat(service.apply(USER, new ApplicationForm("Acme", null, null, null, TERMS_VERSION), EVIDENCE).error())
                    .isEqualTo("already_partner");

            when(codeRepository.findByOwnerUserIdAndProgram(USER, RewardProgram.PARTNER)).thenReturn(Optional.empty());
            when(applicationRepository.existsByUserIdAndStatus(USER, PartnerApplication.Status.PENDING)).thenReturn(true);
            assertThat(service.apply(USER, new ApplicationForm("Acme", null, null, null, TERMS_VERSION), EVIDENCE).error())
                    .isEqualTo("already_pending");
            verify(applicationRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("V557: an application accepts the current terms, recorded with the evidence of the click")
        void recordsTheTermsAcceptance() {
            var out = service.apply(USER, new ApplicationForm("Acme", null, null, null, TERMS_VERSION), EVIDENCE);

            assertThat(out.success()).isTrue();
            verify(termsRepository).record(eq(USER), eq(TERMS_VERSION), eq(PartnerTermsService.CURRENT_FINGERPRINT), any(Instant.class), eq("APPLICATION"),
                    eq("203.0.113.7"), eq("Mozilla/5.0"));
        }

        @Test
        @DisplayName("V557: no application without the terms: nothing ticked or an outdated version stores nothing")
        void refusesWithoutTheTerms() {
            assertThat(service.apply(USER, new ApplicationForm("Acme", null, null, null, null), EVIDENCE).error())
                    .isEqualTo("terms_not_accepted");
            assertThat(service.apply(USER, new ApplicationForm("Acme", null, null, null, "2026-01-01"), EVIDENCE).error())
                    .isEqualTo("terms_outdated");

            verify(applicationRepository, never()).saveAndFlush(any());
            verify(termsRepository, never()).record(any(), any(), any(), any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("acceptTerms (V557, from the dashboard)")
    class AcceptTerms {

        @Test
        @DisplayName("a partner accepts the current terms: recorded as a DASHBOARD acceptance with its evidence")
        void partnerAccepts() {
            when(codeRepository.findByOwnerUserIdAndProgram(USER, RewardProgram.PARTNER)).thenReturn(Optional.of(liveCode(3000)));

            assertThat(service.acceptTerms(USER, TERMS_VERSION, EVIDENCE)).isNull();
            verify(termsRepository).record(eq(USER), eq(TERMS_VERSION), eq(PartnerTermsService.CURRENT_FINGERPRINT), any(Instant.class), eq("DASHBOARD"),
                    eq("203.0.113.7"), eq("Mozilla/5.0"));
        }

        @Test
        @DisplayName("an applicant without a code yet (applied on an older version) may accept too")
        void applicantAccepts() {
            when(applicationRepository.findFirstByUserIdOrderByCreatedAtDescIdDesc(USER))
                    .thenReturn(Optional.of(application(PartnerApplication.Status.PENDING)));

            assertThat(service.acceptTerms(USER, TERMS_VERSION, EVIDENCE)).isNull();
            verify(termsRepository).record(eq(USER), eq(TERMS_VERSION), eq(PartnerTermsService.CURRENT_FINGERPRINT), any(Instant.class), eq("DASHBOARD"), any(), any());
        }

        @Test
        @DisplayName("someone who is neither a partner nor an applicant has nothing to accept: not_partner, nothing recorded")
        void strangerRefused() {
            when(applicationRepository.findFirstByUserIdOrderByCreatedAtDescIdDesc(USER)).thenReturn(Optional.empty());

            assertThat(service.acceptTerms(USER, TERMS_VERSION, EVIDENCE)).isEqualTo("not_partner");
            assertThat(service.acceptTerms(null, TERMS_VERSION, EVIDENCE)).isEqualTo("unauthorized");
            verify(termsRepository, never()).record(any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("a partner quoting an outdated version is told so and nothing is recorded")
        void outdatedRefused() {
            when(codeRepository.findByOwnerUserIdAndProgram(USER, RewardProgram.PARTNER)).thenReturn(Optional.of(liveCode(3000)));

            assertThat(service.acceptTerms(USER, "2026-01-01", EVIDENCE)).isEqualTo("terms_outdated");
            verify(termsRepository, never()).record(any(), any(), any(), any(), any(), any(), any());
        }
    }

    @Test
    @DisplayName("V557: the dashboard carries where the user stands with the terms, partner or not")
    void dashboardCarriesTheAgreement() {
        when(termsRepository.findFirstByUserIdOrderByAcceptedAtDescIdDesc(USER)).thenReturn(Optional.empty());

        var none = service.dashboard(USER);

        assertThat(none.agreement().currentVersion()).isEqualTo(TERMS_VERSION);
        assertThat(none.agreement().acceptedAny()).isFalse();

        when(codeRepository.findByOwnerUserIdAndProgram(USER, RewardProgram.PARTNER)).thenReturn(Optional.of(liveCode(3000)));
        when(termsRepository.existsByUserIdAndTermsVersion(USER, TERMS_VERSION)).thenReturn(true);

        var partner = service.dashboard(USER);

        assertThat(partner.agreement().acceptedCurrent()).isTrue();
    }

    @Test
    @DisplayName("applicant e-mails: one batched lookup for distinct users, an unknown user simply has no e-mail")
    void applicantEmailsBatched() {
        PartnerApplication a = application(PartnerApplication.Status.PENDING);
        PartnerApplication b = application(PartnerApplication.Status.REJECTED);
        PartnerApplication c = application(PartnerApplication.Status.PENDING);
        c.setUserId(8L);
        User known = new User();
        known.setId(USER);
        known.setEmail("p@acme.io");
        when(userRepository.findAllById(any())).thenReturn(List.of(known));

        Map<Long, String> emails = service.applicantEmails(List.of(a, b, c));

        assertThat(emails).containsEntry(USER, "p@acme.io").doesNotContainKey(8L);
        ArgumentCaptor<Iterable<Long>> ids = ArgumentCaptor.forClass(Iterable.class);
        verify(userRepository).findAllById(ids.capture());
        assertThat(ids.getValue()).containsExactlyInAnyOrder(USER, 8L);
        assertThat(service.applicantEmails(List.of())).isEmpty();
        verify(userRepository, times(1)).findAllById(any());
    }

    @Nested
    @DisplayName("admin decision")
    class Decision {

        @Test
        @DisplayName("approve creates the applicant's PARTNER code from the defaults and records it")
        void approveCreatesCode() {
            when(applicationRepository.findById(5L)).thenReturn(Optional.of(application(PartnerApplication.Status.PENDING)));
            when(userRepository.findById(USER)).thenReturn(Optional.of(new User()));

            var out = service.approve(5L, ADMIN, null, null, false);

            assertThat(out.success()).isTrue();
            ArgumentCaptor<RewardCode> code = ArgumentCaptor.forClass(RewardCode.class);
            verify(codeRepository).saveAndFlush(code.capture());
            assertThat(code.getValue().getOwnerUserId()).isEqualTo(USER);
            assertThat(code.getValue().getProgram()).isEqualTo(RewardProgram.PARTNER);
            assertThat(code.getValue().getPayoutBps()).isEqualTo(3000);
            verify(standingRepository, never()).raise(any(), any(), anyBoolean(), any(), any());
            assertThat(code.getValue().getLabel()).isEqualTo("Acme Automation");
            assertThat(out.application().getStatus()).isEqualTo(PartnerApplication.Status.APPROVED);
            assertThat(out.application().getRewardCodeId()).isEqualTo(99L);
            assertThat(out.application().getReviewedBy()).isEqualTo(ADMIN);
            assertThat(out.application().getReviewedAt()).isNotNull();
            // Flushed inside the transaction, so a concurrent decision fails this call (not the commit).
            verify(applicationRepository).saveAndFlush(out.application());
        }

        @Test
        @DisplayName("approve passes the admin's code and commission overrides through")
        void approveWithOverrides() {
            when(applicationRepository.findById(5L)).thenReturn(Optional.of(application(PartnerApplication.Status.PENDING)));
            when(userRepository.findById(USER)).thenReturn(Optional.of(new User()));

            service.approve(5L, ADMIN, "acme", 4000, false);

            ArgumentCaptor<RewardCode> code = ArgumentCaptor.forClass(RewardCode.class);
            verify(codeRepository).saveAndFlush(code.capture());
            assertThat(code.getValue().getCode()).isEqualTo("ACME");
            assertThat(code.getValue().getPayoutBps()).isEqualTo(4000);
        }

        @Test
        @DisplayName("V556: approving as a founder grants Platinum for life, naming the admin")
        void approveAsFounderGrantsPlatinum() {
            when(applicationRepository.findById(5L)).thenReturn(Optional.of(application(PartnerApplication.Status.PENDING)));
            when(userRepository.findById(USER)).thenReturn(Optional.of(new User()));

            var out = service.approve(5L, ADMIN, null, null, true);

            assertThat(out.success()).isTrue();
            verify(standingRepository).raise(eq(USER), eq("PLATINUM"), eq(true), eq(ADMIN), any());
        }

        @Test
        @DisplayName("V556: once the founder window has closed, a founder approval is refused and creates nothing")
        void founderApprovalAfterTheWindowCreatesNothing() {
            PartnerProgramService closed = new PartnerProgramService(adminService, applicationRepository, codeRepository,
                    commissionRepository, userRepository, tierService(FOUNDER_UNTIL), termsService);
            when(applicationRepository.findById(5L)).thenReturn(Optional.of(application(PartnerApplication.Status.PENDING)));
            when(userRepository.findById(USER)).thenReturn(Optional.of(new User()));

            var out = closed.approve(5L, ADMIN, null, null, true);

            assertThat(out.error()).isEqualTo("founder_closed");
            verify(codeRepository, never()).saveAndFlush(any());
            verify(applicationRepository, never()).saveAndFlush(any());
            verify(standingRepository, never()).raise(any(), any(), anyBoolean(), any(), any());
        }

        @Test
        @DisplayName("V556: the founder window closing between the check and the grant throws, so the new code rolls back")
        void founderGrantRefusedAfterTheCheckThrows() {
            PartnerTierService racing = spy(tierService(Instant.parse("2026-12-31T23:59:59Z")));
            doReturn(new PartnerTierService.Result(null, "founder_closed")).when(racing).grantFounder(any(), any());
            PartnerProgramService service = new PartnerProgramService(adminService, applicationRepository, codeRepository,
                    commissionRepository, userRepository, racing, termsService);
            when(applicationRepository.findById(5L)).thenReturn(Optional.of(application(PartnerApplication.Status.PENDING)));
            when(userRepository.findById(USER)).thenReturn(Optional.of(new User()));

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.approve(5L, ADMIN, null, null, true))
                    .isInstanceOf(FounderWindowClosedException.class);
            // The application is never saved as approved.
            verify(applicationRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("V556: the rate named in the approval mail is the effective one (a founder code says 50%)")
        void effectivePercentUsesTheTier() {
            RewardCode c = liveCode(3000);
            com.apimarketplace.auth.domain.PartnerStanding founder = new com.apimarketplace.auth.domain.PartnerStanding();
            founder.setUserId(USER);
            founder.setTier(PartnerTier.PLATINUM);
            founder.setFounder(true);
            when(standingRepository.findById(USER)).thenReturn(Optional.of(founder));

            assertThat(service.effectiveCommissionPercent(c)).isEqualTo(50.0);
            assertThat(service.effectiveCommissionPercent(null)).isZero();
        }

        @Test
        @DisplayName("regression: an applicant who already owns a code is approved onto it, not stuck pending")
        void approveLinksExistingCode() {
            // Before: approve always answered partner_already_has_code, so the only way out was a
            // reject that mailed "not accepted" to someone who is already a partner.
            PartnerApplication pending = application(PartnerApplication.Status.PENDING);
            when(applicationRepository.findById(5L)).thenReturn(Optional.of(pending));
            RewardCode existing = liveCode(3000);
            existing.setId(42L);
            when(codeRepository.findByOwnerUserIdAndProgram(USER, RewardProgram.PARTNER))
                    .thenReturn(Optional.of(existing));

            var out = service.approve(5L, ADMIN, "IGNORED", 4000, false);

            assertThat(out.success()).isTrue();
            assertThat(out.application().getStatus()).isEqualTo(PartnerApplication.Status.APPROVED);
            assertThat(out.application().getRewardCodeId()).isEqualTo(42L);
            verify(codeRepository, never()).saveAndFlush(any());
            verify(applicationRepository).saveAndFlush(pending);
        }

        @Test
        @DisplayName("an applicant whose existing code is disabled or expired is not approved onto it")
        void approveRefusesInactiveExistingCode() {
            PartnerApplication pending = application(PartnerApplication.Status.PENDING);
            when(applicationRepository.findById(5L)).thenReturn(Optional.of(pending));
            RewardCode disabled = liveCode(3000);
            disabled.setActive(false);
            when(codeRepository.findByOwnerUserIdAndProgram(USER, RewardProgram.PARTNER)).thenReturn(Optional.of(disabled));

            var out = service.approve(5L, ADMIN, null, null, false);

            assertThat(out.error()).isEqualTo("existing_code_inactive");
            assertThat(pending.getStatus()).isEqualTo(PartnerApplication.Status.PENDING);
            verify(applicationRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("isPending: true only for a PENDING application, false once decided or when unknown")
        void isPending() {
            when(applicationRepository.findById(5L)).thenReturn(Optional.of(application(PartnerApplication.Status.PENDING)));
            when(applicationRepository.findById(6L)).thenReturn(Optional.of(application(PartnerApplication.Status.APPROVED)));
            when(applicationRepository.findById(7L)).thenReturn(Optional.empty());

            assertThat(service.isPending(5L)).isTrue();
            assertThat(service.isPending(6L)).isFalse();
            assertThat(service.isPending(7L)).isFalse();
        }

        @Test
        @DisplayName("any other code refusal leaves the application pending and reports the refusal")
        void approveRefusalKeepsPending() {
            PartnerApplication pending = application(PartnerApplication.Status.PENDING);
            when(applicationRepository.findById(5L)).thenReturn(Optional.of(pending));
            when(userRepository.findById(USER)).thenReturn(Optional.of(new User()));

            var out = service.approve(5L, ADMIN, "not a valid code!", null, false);

            assertThat(out.error()).isEqualTo("invalid_code_format");
            assertThat(pending.getStatus()).isEqualTo(PartnerApplication.Status.PENDING);
            verify(applicationRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("a concurrent decision surfaces as the optimistic-lock error, so the whole approval rolls back")
        void approveConcurrentDecisionPropagates() {
            // A fresh PENDING row per call, as the database would return it to each request.
            when(applicationRepository.findById(5L))
                    .thenAnswer(inv -> Optional.of(application(PartnerApplication.Status.PENDING)));
            when(userRepository.findById(USER)).thenReturn(Optional.of(new User()));
            when(applicationRepository.saveAndFlush(any(PartnerApplication.class)))
                    .thenThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException(PartnerApplication.class, 5L));

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.approve(5L, ADMIN, null, null, false))
                    .isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.reject(5L, ADMIN, null))
                    .isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
        }

        @Test
        @DisplayName("only a pending application can be decided, and an unknown one is not found")
        void onlyPending() {
            when(applicationRepository.findById(5L)).thenReturn(Optional.of(application(PartnerApplication.Status.REJECTED)));
            assertThat(service.approve(5L, ADMIN, null, null, false).error()).isEqualTo("not_pending");
            assertThat(service.reject(5L, ADMIN, null).error()).isEqualTo("not_pending");

            when(applicationRepository.findById(6L)).thenReturn(Optional.empty());
            assertThat(service.approve(6L, ADMIN, null, null, false).error()).isEqualTo("application_not_found");
            assertThat(service.reject(6L, ADMIN, null).error()).isEqualTo("application_not_found");
            verify(codeRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("reject records the note, the reviewer and the time; an over-long note is refused")
        void reject() {
            when(applicationRepository.findById(5L)).thenReturn(Optional.of(application(PartnerApplication.Status.PENDING)));

            assertThat(service.reject(5L, ADMIN, "n".repeat(501)).error()).isEqualTo("too_long");
            var out = service.reject(5L, ADMIN, "  Not a fit yet  ");

            assertThat(out.success()).isTrue();
            assertThat(out.application().getStatus()).isEqualTo(PartnerApplication.Status.REJECTED);
            assertThat(out.application().getDecisionNote()).isEqualTo("Not a fit yet");
            assertThat(out.application().getReviewedBy()).isEqualTo(ADMIN);
            verify(codeRepository, never()).saveAndFlush(any());
        }
    }
}

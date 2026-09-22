package com.apimarketplace.auth.integration;

import com.apimarketplace.auth.domain.BillingCustomer;
import com.apimarketplace.auth.domain.CreditLedgerEntry;
import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.BillingCustomerRepository;
import com.apimarketplace.auth.repository.CreditLedgerRepository;
import com.apimarketplace.auth.repository.PlanRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.service.YearlyCreditCycleScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The monthly credit cycle of a YEARLY Stripe subscription (V498), on a REAL Postgres with
 * REAL transaction boundaries: the scheduler loads rows in one transaction and the attribution
 * service grants in another, so the rows it holds are detached and a mocked test cannot see
 * whether the credits, the index and the reset actually commit together. Same reasoning and
 * same fixture discipline as {@link InternalRenewalCreditPersistenceTest}; the class is
 * deliberately NOT {@code @Transactional}.
 */
@SpringBootTest
@DisplayName("Yearly Stripe subscription - the monthly credit cycle lands and stays (real Postgres)")
class YearlyCreditCyclePersistenceTest extends AuthPostgresIntegrationTest {

    @Autowired private YearlyCreditCycleScheduler scheduler;
    @Autowired private com.apimarketplace.auth.service.CreditAttributionService creditAttributionService;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private CreditLedgerRepository ledgerRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private BillingCustomerRepository billingCustomerRepository;
    @Autowired private PlanRepository planRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    /** Spy, so one test can make the real grant fail and prove the index rolls back with it. */
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private com.apimarketplace.auth.service.CreditService creditService;

    /** TEAM tier 4: Stripe quantity 100 = 100,000 credits (CreditTierConstants). */
    private static final int TEAM_TIER_4_QUANTITY = 100;
    private static final BigDecimal TEAM_TIER_4_CREDITS = new BigDecimal("100000");
    private static final BigDecimal FREE_GRANT = new BigDecimal("1000");

    private Plan teamPlan;
    private Plan freePlan;

    /** ShedLock's lockAtLeastFor would let only the first pass of the class through the proxy. */
    private YearlyCreditCycleScheduler unlockedScheduler;

    @BeforeEach
    void reset() {
        unlockedScheduler = AopTestUtils.getTargetObject(scheduler);
        org.mockito.Mockito.reset(creditService);
        jdbcTemplate.update("DELETE FROM auth.shedlock");

        ledgerRepository.deleteAll();
        subscriptionRepository.deleteAll();
        billingCustomerRepository.deleteAll();
        userRepository.deleteAll();
        planRepository.deleteAll();

        teamPlan = new Plan();
        teamPlan.setCode("TEAM");
        teamPlan.setName("Team");
        teamPlan = planRepository.save(teamPlan);

        freePlan = new Plan();
        freePlan.setCode("FREE");
        freePlan.setName("Free");
        freePlan.setIncludedLlmTokens(FREE_GRANT.longValue());
        freePlan = planRepository.save(freePlan);
    }

    // ─────────────────────────── seeding ───────────────────────────

    private record Seeded(Long subId, Long userId) {}

    private Seeded seed(String email, String provider, String cadence, Plan plan, int creditQuantity,
                        BigDecimal balance, LocalDateTime periodStart, LocalDateTime periodEnd) {
        User user = new User();
        user.setEmail(email);
        user.setUsername(email);
        user = userRepository.save(user);

        BillingCustomer bc = billingCustomerRepository.save(new BillingCustomer(user, provider));

        Subscription sub = new Subscription();
        sub.setBillingCustomer(bc);
        sub.setPlan(plan);
        sub.setProvider(provider);
        sub.setProviderSubscriptionId("stripe".equals(provider) ? "sub_" + email : null);
        sub.setStatus("active");
        sub.setCadence(cadence);
        sub.setQuantity(1);
        sub.setCreditQuantity(creditQuantity);
        sub.setCancelAtPeriodEnd(false);
        sub.setDelinquent(balance.signum() <= 0);
        sub.setRemainingCredits(balance);
        sub.setPaygRemainingCredits(BigDecimal.ZERO);
        sub.setCurrentPeriodStart(periodStart);
        sub.setCurrentPeriodEnd(periodEnd);
        Subscription saved = subscriptionRepository.save(sub);
        return new Seeded(saved.getId(), user.getId());
    }

    /** Prod user 121's shape: TEAM yearly, tier 4, wallet at -100.83 and delinquent. */
    private Seeded seedYearlyTeam(String email, LocalDateTime periodStart) {
        return seed(email, "stripe", "yearly", teamPlan, TEAM_TIER_4_QUANTITY,
                new BigDecimal("-100.8285"), periodStart, periodStart.plusMonths(12));
    }

    private Subscription row(Long subId) {
        return subscriptionRepository.findById(subId).orElseThrow();
    }

    private List<CreditLedgerEntry> ledger(Long userId, String sourceType) {
        return ledgerRepository.findAll().stream()
                .filter(e -> userId.equals(e.getUserId()) && sourceType.equals(e.getSourceType()))
                .toList();
    }

    private static String key(LocalDateTime t) {
        return String.valueOf(t.toEpochSecond(ZoneOffset.UTC));
    }

    /** Postgres keeps microseconds; a nanosecond "now" would never round-trip equal. */
    private static LocalDateTime now() {
        return LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    // ─────────────────────────── the fix ───────────────────────────

    @Test
    @DisplayName("REGRESSION: two months into a yearly period the pack is on the row, the debt is gone, delinquency cleared")
    void theMonthlyCycleLandsAndStays() {
        LocalDateTime periodStart = now().minusMonths(2).minusDays(1);
        Seeded sub = seedYearlyTeam("yearly@test.local", periodStart);

        unlockedScheduler.grantDueMonthlyCycles();

        Subscription after = row(sub.subId());
        // Pre-fix: -100.8285 until the next invoice.paid, a year away.
        assertThat(after.getRemainingCredits()).isEqualByComparingTo(TEAM_TIER_4_CREDITS);
        assertThat(after.getDelinquent()).isFalse();
        // Two boundaries passed: the index jumps to 2, granted once.
        assertThat(after.getCreditCycleIndex()).isEqualTo(2);
        // The period itself is Stripe's and must not move.
        assertThat(after.getCurrentPeriodStart()).isEqualTo(periodStart);
        assertThat(after.getCurrentPeriodEnd()).isEqualTo(periodStart.plusMonths(12));

        List<CreditLedgerEntry> grants = ledger(sub.userId(), "PURCHASE");
        assertThat(grants).hasSize(1);
        assertThat(grants.get(0).getAmount()).isEqualByComparingTo(TEAM_TIER_4_CREDITS);
        assertThat(grants.get(0).getSourceId()).isEqualTo("pack_sub_" + sub.subId() + "_" + key(periodStart.plusMonths(2)));

        List<CreditLedgerEntry> resets = ledger(sub.userId(), "PLAN_RESET");
        assertThat(resets).hasSize(1);
        assertThat(resets.get(0).getAmount()).isEqualByComparingTo(new BigDecimal("100.8285"));
        assertThat(resets.get(0).getSourceId()).isEqualTo("reset_sub_" + sub.subId() + "_" + key(periodStart.plusMonths(2)));
    }

    @Test
    @DisplayName("a second pass in the same month neither re-grants nor wipes the balance")
    void secondPassIsANoOp() {
        Seeded sub = seedYearlyTeam("idem@test.local", now().minusMonths(1).minusDays(1));

        unlockedScheduler.grantDueMonthlyCycles();
        unlockedScheduler.grantDueMonthlyCycles();

        assertThat(row(sub.subId()).getRemainingCredits()).isEqualByComparingTo(TEAM_TIER_4_CREDITS);
        assertThat(row(sub.subId()).getCreditCycleIndex()).isEqualTo(1);
        assertThat(ledger(sub.userId(), "PURCHASE")).hasSize(1);
    }

    @Test
    @DisplayName("the yearly Stripe renewal moves the period, which restarts the count, and the next month is granted again")
    void renewalReArmsTheMonthlyCycle() {
        // Year one is over: eleven cycles granted (the first pass catches up to 11).
        LocalDateTime yearOneStart = now().minusMonths(13).minusDays(1);
        Seeded sub = seedYearlyTeam("renew@test.local", yearOneStart);
        unlockedScheduler.grantDueMonthlyCycles();
        assertThat(row(sub.subId()).getCreditCycleIndex()).isEqualTo(11);

        // What the Stripe sync does on invoice.paid / customer.subscription.updated.
        Subscription renewed = row(sub.subId());
        LocalDateTime yearTwoStart = yearOneStart.plusMonths(12);
        renewed.setCurrentPeriodStart(yearTwoStart);
        renewed.setCurrentPeriodEnd(yearTwoStart.plusMonths(12));
        subscriptionRepository.save(renewed);
        assertThat(row(sub.subId()).getCreditCycleIndex())
                .as("a moved anchor restarts the count, persisted")
                .isZero();

        unlockedScheduler.grantDueMonthlyCycles();

        // A stale 11 would have made every month of year two "already granted".
        Subscription after = row(sub.subId());
        assertThat(after.getCreditCycleIndex()).isEqualTo(1);
        assertThat(ledger(sub.userId(), "PURCHASE"))
                .extracting(CreditLedgerEntry::getSourceId)
                .contains("pack_sub_" + sub.subId() + "_" + key(yearTwoStart.plusMonths(1)))
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("over a full year: eleven monthly cycles plus the renewal make exactly twelve packs, no key twice")
    void twelvePacksAYearAndNoDuplicateKey() {
        LocalDateTime yearOneStart = now().minusMonths(13);
        Seeded sub = seedYearlyTeam("twelve@test.local", yearOneStart);
        Long userId = sub.userId();

        // Month by month, through the transactional proxy on a real database, as the hourly
        // pass would have done had it existed all year.
        for (int month = 1; month <= 12; month++) {
            LocalDateTime clock = yearOneStart.plusMonths(month).plusHours(1);
            creditAttributionService.attributeMonthlyCreditCycle(userId, row(sub.subId()), clock);
        }
        assertThat(row(sub.subId()).getCreditCycleIndex()).isEqualTo(11);

        // The yearly Stripe renewal: period moved by the sync, then invoice.paid re-grants.
        Subscription renewed = row(sub.subId());
        renewed.setCurrentPeriodStart(yearOneStart.plusMonths(12));
        renewed.setCurrentPeriodEnd(yearOneStart.plusMonths(24));
        subscriptionRepository.save(renewed);
        creditAttributionService.attributeOnRenewal(userId, row(sub.subId()));

        List<CreditLedgerEntry> grants = ledger(userId, "PURCHASE");
        assertThat(grants).hasSize(12);
        assertThat(grants).extracting(CreditLedgerEntry::getSourceId).doesNotHaveDuplicates();
        assertThat(grants).allSatisfy(g -> assertThat(g.getAmount()).isEqualByComparingTo(TEAM_TIER_4_CREDITS));
        // Twelve resets too (the seed was negative, every later cycle had a full pack to absorb).
        assertThat(ledger(userId, "PLAN_RESET")).hasSize(12);
        assertThat(row(sub.subId()).getRemainingCredits()).isEqualByComparingTo(TEAM_TIER_4_CREDITS);
        assertThat(row(sub.subId()).getCreditCycleIndex()).isZero();
    }

    @Test
    @DisplayName("the ledger key is a second, independent guard: a rewound index cannot re-grant a cycle")
    void ledgerKeyGuardHoldsWhenTheIndexIsRewound() {
        Seeded sub = seedYearlyTeam("rewind@test.local", now().minusMonths(1).minusDays(1));
        unlockedScheduler.grantDueMonthlyCycles();
        assertThat(row(sub.subId()).getCreditCycleIndex()).isEqualTo(1);

        // Whatever rewinds the index (a bad manual fix, a future bug in the reset), the cycle's
        // sourceIds already exist, and existsBySourceId absorbs both the reset and the grant.
        Subscription rewound = row(sub.subId());
        rewound.setCreditCycleIndex(0);
        subscriptionRepository.save(rewound);

        unlockedScheduler.grantDueMonthlyCycles();

        assertThat(ledger(sub.userId(), "PURCHASE")).hasSize(1);
        assertThat(ledger(sub.userId(), "PLAN_RESET")).hasSize(1);
        assertThat(row(sub.subId()).getRemainingCredits()).isEqualByComparingTo(TEAM_TIER_4_CREDITS);
        // The index still advances (the cycle IS taken), so the row is consistent again.
        assertThat(row(sub.subId()).getCreditCycleIndex()).isEqualTo(1);
    }

    @Test
    @DisplayName("a failed grant rolls the index back too, so the next pass retries the whole cycle")
    void aFailedGrantRollsTheIndexBack() {
        Seeded sub = seedYearlyTeam("rollback@test.local", now().minusMonths(1).minusDays(1));
        org.mockito.Mockito.doThrow(new IllegalStateException("grant exploded"))
                .when(creditService).grantCredits(org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());

        unlockedScheduler.grantDueMonthlyCycles();

        Subscription after = row(sub.subId());
        assertThat(after.getCreditCycleIndex())
                .as("the cycle must not be marked granted when the credits did not land")
                .isZero();
        assertThat(after.getRemainingCredits()).isEqualByComparingTo(new BigDecimal("-100.8285"));
        assertThat(ledgerRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("monthly Stripe and internal FREE rows in the same table are not touched by the pass")
    void monthlyAndInternalRowsAreUntouched() {
        LocalDateTime twoMonthsAgo = now().minusMonths(2).minusDays(1);
        Seeded monthly = seed("monthly@test.local", "stripe", "monthly", teamPlan, TEAM_TIER_4_QUANTITY,
                new BigDecimal("500"), twoMonthsAgo, twoMonthsAgo.plusMonths(1));
        Seeded internal = seed("free@test.local", "internal", "monthly", freePlan, 0,
                new BigDecimal("700"), twoMonthsAgo, twoMonthsAgo.plusMonths(1));
        Seeded yearly = seedYearlyTeam("yearly2@test.local", twoMonthsAgo);

        unlockedScheduler.grantDueMonthlyCycles();

        // Stripe owns the monthly row's cycle, the internal scheduler owns the FREE row's.
        assertThat(row(monthly.subId()).getRemainingCredits()).isEqualByComparingTo(new BigDecimal("500"));
        assertThat(row(monthly.subId()).getCreditCycleIndex()).isZero();
        assertThat(row(internal.subId()).getRemainingCredits()).isEqualByComparingTo(new BigDecimal("700"));
        assertThat(row(internal.subId()).getCreditCycleIndex()).isZero();
        assertThat(ledger(monthly.userId(), "PURCHASE")).isEmpty();
        assertThat(ledger(internal.userId(), "PURCHASE")).isEmpty();
        // ... while the yearly one next to them was served.
        assertThat(row(yearly.subId()).getRemainingCredits()).isEqualByComparingTo(TEAM_TIER_4_CREDITS);
    }

    @Test
    @DisplayName("a yearly row that is not active is left alone, whatever its wallet says")
    void inactiveYearlyRowIsLeftAlone() {
        LocalDateTime twoMonthsAgo = now().minusMonths(2).minusDays(1);
        Seeded sub = seedYearlyTeam("pastdue@test.local", twoMonthsAgo);
        Subscription r = row(sub.subId());
        r.setStatus("past_due");
        subscriptionRepository.save(r);

        unlockedScheduler.grantDueMonthlyCycles();

        assertThat(row(sub.subId()).getRemainingCredits()).isEqualByComparingTo(new BigDecimal("-100.8285"));
        assertThat(row(sub.subId()).getCreditCycleIndex()).isZero();
        assertThat(ledgerRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("the PAYG bucket survives the monthly reset")
    void paygSurvivesTheMonthlyReset() {
        Seeded sub = seedYearlyTeam("payg@test.local", now().minusMonths(1).minusDays(1));
        Subscription r = row(sub.subId());
        r.setPaygRemainingCredits(new BigDecimal("300"));
        r.setRemainingCredits(new BigDecimal("50"));
        r.setDelinquent(false);
        subscriptionRepository.save(r);

        unlockedScheduler.grantDueMonthlyCycles();

        Subscription after = row(sub.subId());
        assertThat(after.getRemainingCredits()).isEqualByComparingTo(TEAM_TIER_4_CREDITS);
        assertThat(after.getPaygRemainingCredits()).isEqualByComparingTo(new BigDecimal("300"));
        Optional<CreditLedgerEntry> reset = ledger(sub.userId(), "PLAN_RESET").stream().findFirst();
        assertThat(reset).isPresent();
        assertThat(reset.get().getAmount()).isEqualByComparingTo(new BigDecimal("-50"));
    }
}

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
import com.apimarketplace.auth.service.CreditAttributionService;
import com.apimarketplace.auth.service.FreeSubscriptionRenewalScheduler;
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
 * Regression suite for the internal (non-Stripe) monthly renewal, on a REAL Postgres with
 * REAL transaction boundaries. Every assertion here targets a defect that shipped to
 * production and that a mock-based test structurally cannot catch.
 *
 * <p><b>Why this must not be a mocked unit test.</b> All three defects live in the seam
 * between two transactions: {@code FreeSubscriptionRenewalScheduler} loads subscriptions in
 * one transaction and {@code CreditAttributionService.attributeOnRenewal} grants in another,
 * so the scheduler's entities are DETACHED. A test that mocks the attribution service (as
 * {@code FreeSubscriptionRenewalSchedulerTest} does, by design, for the routing concerns)
 * never materialises that seam and passed happily while production lost every grant.
 * The class is deliberately NOT {@code @Transactional}: wrapping it would keep everything in
 * one persistence context and re-hide the bugs.
 *
 * <p>Defects pinned here:
 * <ol>
 *   <li><b>Lost update</b> - the scheduler used to re-save its stale detached copy after the
 *       grant committed, silently writing the pre-grant balance back over the fresh credits.
 *       Ledger showed the grant, wallet showed zero.</li>
 *   <li><b>Period-key reuse</b> - the renewal sourceId is derived from
 *       {@code currentPeriodStart}; attributing before advancing it re-minted the key an
 *       admin comp grant had already consumed, so the {@code existsBySourceId} guards
 *       skipped the whole renewal and the user got nothing.</li>
 *   <li><b>Stale reset amount</b> - {@code resetBalance} recorded the balance it read off the
 *       caller's (possibly stale) entity, mis-stating how much the {@code PLAN_RESET}
 *       actually absorbed and corrupting the ledger permanently.</li>
 * </ol>
 */
@SpringBootTest
@DisplayName("Internal monthly renewal - the granted credits survive (real Postgres)")
class InternalRenewalCreditPersistenceTest extends AuthScratchPostgresSpringTest {


    @Autowired private FreeSubscriptionRenewalScheduler scheduler;
    @Autowired private CreditAttributionService creditAttributionService;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private CreditLedgerRepository ledgerRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private BillingCustomerRepository billingCustomerRepository;
    @Autowired private PlanRepository planRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    /**
     * Spy, not mock: every test but the crash-safety one needs the REAL grant to run (that is
     * what proves the credits land and stay). Only {@code aFailedGrantRollsBackThePeriodAsWell}
     * stubs it, and {@code Mockito.reset} in {@link #reset()} puts it back.
     */
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private com.apimarketplace.auth.service.CreditService creditService;

    /** FREE grants 1000 plan-included credits on renewal (grantPlanCredits path). */
    private static final BigDecimal FREE_GRANT = new BigDecimal("1000");

    private Plan freePlan;

    /**
     * The scheduler with its ShedLock advisor unwrapped. {@code @SchedulerLock} declares
     * {@code lockAtLeastFor=PT30S}, so through the proxy only the FIRST pass of the class
     * would ever reach the method body and every later test would silently assert on an
     * untouched fixture. {@code creditAttributionService} is still injected as its Spring proxy
     * inside this target, so {@code @Transactional} boundaries - the whole point of these
     * tests - are untouched.
     *
     * <p><b>Consequence to be explicit about:</b> ShedLock is what bounds overlapping passes in
     * production, so multi-instance behaviour is NOT covered by this class. The guard that makes
     * an overlap safe (skip a row another actor already renewed) is covered deterministically by
     * {@code alreadyRenewedRowIsSkipped} below and by
     * {@code CreditAttributionServiceTest.skipsARowAlreadyRenewedUnderTheLock}.
     */
    private FreeSubscriptionRenewalScheduler unlockedScheduler;

    @BeforeEach
    void reset() {
        unlockedScheduler = AopTestUtils.getTargetObject(scheduler);
        org.mockito.Mockito.reset(creditService); // drop any stubbing, restore real behaviour
        jdbcTemplate.update("DELETE FROM auth.shedlock");

        ledgerRepository.deleteAll();
        subscriptionRepository.deleteAll();
        billingCustomerRepository.deleteAll();
        userRepository.deleteAll();
        planRepository.deleteAll();

        freePlan = new Plan();
        freePlan.setCode("FREE");
        freePlan.setName("Free");
        freePlan.setIncludedLlmTokens(FREE_GRANT.longValue());
        freePlan = planRepository.save(freePlan);
    }

    // ─────────────────────────── seeding ───────────────────────────

    /** Ids of a seeded fixture - captured at insert time, since re-reading outside a
     *  transaction hands back a detached entity whose billingCustomer is a lazy proxy. */
    private record Seeded(Long subId, Long userId) {}

    /**
     * An internal subscription already past its period end, i.e. exactly what
     * {@code findExpiredInternalSubscriptions} selects on the next hourly pass.
     */
    private Seeded seedExpiredInternalSub(String email, BigDecimal balance, LocalDateTime periodStart) {
        User user = new User();
        user.setEmail(email);
        user.setUsername(email);
        user = userRepository.save(user);

        BillingCustomer bc = billingCustomerRepository.save(new BillingCustomer(user, "internal"));

        Subscription sub = new Subscription();
        sub.setBillingCustomer(bc);
        sub.setPlan(freePlan);
        sub.setProvider("internal");
        sub.setStatus("active");
        sub.setCadence("monthly");
        sub.setQuantity(1);
        sub.setCreditQuantity(0);
        sub.setCancelAtPeriodEnd(false);
        sub.setDelinquent(false);
        sub.setRemainingCredits(balance);
        sub.setPaygRemainingCredits(BigDecimal.ZERO);
        sub.setCurrentPeriodStart(periodStart);
        sub.setCurrentPeriodEnd(periodStart.plusMonths(1));
        Subscription saved = subscriptionRepository.save(sub);
        return new Seeded(saved.getId(), user.getId());
    }

    private BigDecimal liveBalance(Long subId) {
        return subscriptionRepository.findById(subId).orElseThrow().getRemainingCredits();
    }

    private Optional<CreditLedgerEntry> ledgerRow(String sourceType) {
        return ledgerRepository.findAll().stream()
                .filter(e -> sourceType.equals(e.getSourceType()))
                .findFirst();
    }

    private static String periodKey(LocalDateTime t) {
        return String.valueOf(t.toEpochSecond(ZoneOffset.UTC));
    }

    // ─────────────────────────── defect 1 ───────────────────────────

    @Test
    @DisplayName("the credits granted on renewal are still on the row when the pass ends")
    void grantedCreditsSurviveTheRenewalPass() {
        LocalDateTime periodStart = LocalDateTime.now().minusMonths(2);
        Seeded sub = seedExpiredInternalSub("survive@test.local", new BigDecimal("200"), periodStart);

        unlockedScheduler.renewExpiredInternalSubscriptions();

        // THE regression. Pre-fix this read 0.0000: the grant committed, then the scheduler
        // merged its stale detached copy back over the row.
        assertThat(liveBalance(sub.subId()))
                .as("wallet must hold the credits the ledger says were granted")
                .isEqualByComparingTo(FREE_GRANT);

        // ... and the ledger agrees, so wallet and ledger cannot silently diverge.
        CreditLedgerEntry grant = ledgerRow("PURCHASE").orElseThrow();
        assertThat(grant.getAmount()).isEqualByComparingTo(FREE_GRANT);
        assertThat(grant.getBalanceAfter()).isEqualByComparingTo(FREE_GRANT);
    }

    @Test
    @DisplayName("the pre-renewal balance is absorbed by a PLAN_RESET, not carried over")
    void previousBalanceIsResetBeforeTheGrant() {
        LocalDateTime periodStart = LocalDateTime.now().minusMonths(2);
        Seeded sub = seedExpiredInternalSub("reset@test.local", new BigDecimal("200"), periodStart);

        unlockedScheduler.renewExpiredInternalSubscriptions();

        CreditLedgerEntry planReset = ledgerRow("PLAN_RESET").orElseThrow();
        assertThat(planReset.getAmount()).isEqualByComparingTo(new BigDecimal("-200"));
        // 200 absorbed then 1000 granted - never 1200.
        assertThat(liveBalance(sub.subId())).isEqualByComparingTo(FREE_GRANT);

        // The row is keyed on the NEW cycle, so next month's key differs again. Keying on the
        // old one is what silently collided with an admin grant's already-consumed key.
        Subscription renewed = subscriptionRepository.findById(sub.subId()).orElseThrow();
        assertThat(planReset.getSourceId())
                .isEqualTo("reset_sub_" + sub.subId() + "_" + periodKey(renewed.getCurrentPeriodStart()));
        assertThat(planReset.getSourceId()).doesNotContain(periodKey(periodStart));
    }

    @Test
    @DisplayName("the billing period rolls one month forward and is persisted")
    void periodRollsForwardAndPersists() {
        LocalDateTime before = LocalDateTime.now();
        LocalDateTime periodStart = before.minusMonths(2);
        Seeded sub = seedExpiredInternalSub("period@test.local", BigDecimal.ZERO, periodStart);

        unlockedScheduler.renewExpiredInternalSubscriptions();

        Subscription renewed = subscriptionRepository.findById(sub.subId()).orElseThrow();
        assertThat(renewed.getCurrentPeriodStart()).isAfterOrEqualTo(before);
        assertThat(renewed.getCurrentPeriodEnd())
                .isEqualTo(renewed.getCurrentPeriodStart().plusMonths(1));
        // No longer selected by the next pass - this is what makes the renewal idempotent.
        assertThat(subscriptionRepository.findExpiredInternalSubscriptions(LocalDateTime.now()))
                .noneMatch(s -> s.getId().equals(sub.subId()));
    }

    @Test
    @DisplayName("a second pass in the same period neither re-grants nor wipes the balance")
    void secondPassIsANoOp() {
        LocalDateTime periodStart = LocalDateTime.now().minusMonths(2);
        Seeded sub = seedExpiredInternalSub("idem@test.local", new BigDecimal("200"), periodStart);

        unlockedScheduler.renewExpiredInternalSubscriptions();

        unlockedScheduler.renewExpiredInternalSubscriptions();

        assertThat(liveBalance(sub.subId())).isEqualByComparingTo(FREE_GRANT);
        assertThat(ledgerRepository.findAll().stream().filter(e -> "PURCHASE".equals(e.getSourceType())))
                .hasSize(1);
    }

    // ─────────────────────────── defect 2 ───────────────────────────

    @Test
    @DisplayName("a renewal following an admin grant on the same period start still delivers credits")
    void renewalAfterAdminGrantOnTheSamePeriodStartStillGrants() {
        // AdminPlanService anchors currentPeriodStart to its own "now" and immediately
        // attributes with a sourceId derived from it. Reproduce that state exactly.
        LocalDateTime adminGrantInstant = LocalDateTime.now().minusMonths(2);
        Seeded sub = seedExpiredInternalSub("collide@test.local", new BigDecimal("200"), adminGrantInstant);

        String consumedSuffix = periodKey(adminGrantInstant);
        seedLedgerRow(sub.userId(), "reset_sub_" + sub.subId() + "_" + consumedSuffix, "PLAN_RESET", BigDecimal.ZERO);
        seedLedgerRow(sub.userId(), "plan_sub_" + sub.subId() + "_" + consumedSuffix, "PURCHASE", FREE_GRANT);

        unlockedScheduler.renewExpiredInternalSubscriptions();

        // Pre-fix the renewal re-minted `*_<consumedSuffix>`, both existsBySourceId guards
        // short-circuited, and the user silently received nothing while the period advanced.
        assertThat(liveBalance(sub.subId()))
                .as("the new cycle must deliver its own credits")
                .isEqualByComparingTo(FREE_GRANT);

        List<String> grantSourceIds = ledgerRepository.findAll().stream()
                .filter(e -> "PURCHASE".equals(e.getSourceType()))
                .map(CreditLedgerEntry::getSourceId)
                .toList();
        assertThat(grantSourceIds).hasSize(2);
        assertThat(grantSourceIds).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("a comp STARTER row renews at the tier-0 pack, and keeps it")
    void compPlanRenewsAtTheBasePackAndKeepsIt() {
        // The scheduler was broadened from plan=FREE to provider=internal precisely to cover
        // admin-granted comp tiers; that branch (grantPackCredits) needs its own end-to-end
        // proof, since it is the one that carries the largest amounts.
        Plan starter = new Plan();
        starter.setCode("STARTER");
        starter.setName("Starter");
        starter.setIncludedLlmTokens(25000L);
        starter = planRepository.save(starter);

        LocalDateTime periodStart = LocalDateTime.now().minusMonths(2);
        Seeded sub = seedExpiredInternalSub("comp@test.local", new BigDecimal("300"), periodStart);
        Subscription row = subscriptionRepository.findById(sub.subId()).orElseThrow();
        row.setPlan(starter);
        subscriptionRepository.save(row);

        unlockedScheduler.renewExpiredInternalSubscriptions();

        // Tier-0 base pack, not the plan's larger allowance (the "5k/month max" rule).
        assertThat(liveBalance(sub.subId())).isEqualByComparingTo(new BigDecimal("5000"));
        assertThat(ledgerRow("PURCHASE").orElseThrow().getSourceId())
                .startsWith("pack_sub_" + sub.subId() + "_");
    }

    @Test
    @DisplayName("a failed grant rolls the period back too, so the next pass retries the whole renewal")
    void aFailedGrantRollsBackThePeriodAsWell() {
        // The crash-safety property the new ordering claims: period and credits commit together.
        // Without it, a grant that blows up would leave the cycle advanced and the user unpaid
        // for a month, with no pass ever coming back for them.
        LocalDateTime periodStart = LocalDateTime.now().minusMonths(2);
        Seeded sub = seedExpiredInternalSub("rollback@test.local", new BigDecimal("200"), periodStart);

        org.mockito.Mockito.doThrow(new IllegalStateException("grant exploded"))
                .when(creditService).grantCredits(org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());

        unlockedScheduler.renewExpiredInternalSubscriptions();

        Subscription row = subscriptionRepository.findById(sub.subId()).orElseThrow();
        assertThat(row.getCurrentPeriodStart())
                .as("the cycle must not move when the credits did not land")
                .isBefore(LocalDateTime.now().minusMonths(1));
        assertThat(row.getRemainingCredits()).isEqualByComparingTo(new BigDecimal("200"));
        assertThat(ledgerRepository.findAll()).isEmpty();
        // Still expired, so the next hourly pass picks it up and retries cleanly.
        assertThat(subscriptionRepository.findExpiredInternalSubscriptions(LocalDateTime.now()))
                .anyMatch(s -> s.getId().equals(sub.subId()));
    }

    @Test
    @DisplayName("an admin grant survives the renewal that wipes the monthly bucket")
    void adminGrantSurvivesRenewal() {
        LocalDateTime periodStart = LocalDateTime.now().minusMonths(2);
        Seeded sub = seedExpiredInternalSub("adminkeep@test.local", new BigDecimal("200"), periodStart);

        creditService.grantCredits(sub.userId(), new BigDecimal("500000"), "MANUAL_ADJUSTMENT",
                "admin-grant-it-1", "Admin grant by user 1");

        unlockedScheduler.renewExpiredInternalSubscriptions();

        Subscription row = subscriptionRepository.findById(sub.subId()).orElseThrow();
        // The 200 monthly credits are absorbed by PLAN_RESET and replaced by the fresh grant;
        // the admin's 500,000 must be untouched. Before the routing change it lived in the same
        // bucket the reset wipes, which is how production destroyed 453,494 credits in one pass.
        assertThat(row.getPaygRemainingCredits()).isEqualByComparingTo(new BigDecimal("500000"));
        assertThat(row.getRemainingCredits()).isEqualByComparingTo(FREE_GRANT);
        assertThat(row.getTotalBalance()).isEqualByComparingTo(new BigDecimal("501000"));
    }

    @Test
    @DisplayName("two expired rows in one pass are renewed independently")
    void twoRowsInOnePassAreRenewedIndependently() {
        LocalDateTime periodStart = LocalDateTime.now().minusMonths(2);
        Seeded a = seedExpiredInternalSub("loop-a@test.local", new BigDecimal("50"), periodStart);
        Seeded b = seedExpiredInternalSub("loop-b@test.local", new BigDecimal("70"), periodStart);

        unlockedScheduler.renewExpiredInternalSubscriptions();

        assertThat(liveBalance(a.subId())).isEqualByComparingTo(FREE_GRANT);
        assertThat(liveBalance(b.subId())).isEqualByComparingTo(FREE_GRANT);
    }

    @Test
    @DisplayName("a row a concurrent actor already renewed is skipped, not granted a second time")
    void alreadyRenewedRowIsSkipped() {
        LocalDateTime periodStart = LocalDateTime.now().minusMonths(2);
        Seeded sub = seedExpiredInternalSub("race@test.local", new BigDecimal("1000"), periodStart);

        // The caller selected this row as expired with an OLD cutoff, but by the time the
        // attribution acquires the lock another actor has moved the cycle forward. Advancing
        // anyway would mint a second sourceId for the same cycle and grant twice.
        Subscription row = subscriptionRepository.findById(sub.subId()).orElseThrow();
        LocalDateTime winnerStart = LocalDateTime.now();
        row.setCurrentPeriodStart(winnerStart);
        row.setCurrentPeriodEnd(winnerStart.plusMonths(1));
        subscriptionRepository.save(row);

        creditAttributionService.attributeOnRenewal(sub.userId(), row, winnerStart.minusMinutes(5));

        assertThat(liveBalance(sub.subId()))
                .as("the loser must not reset or re-grant the winner's cycle")
                .isEqualByComparingTo(new BigDecimal("1000"));
        assertThat(ledgerRepository.findAll()).isEmpty();
    }

    private void seedLedgerRow(Long userId, String sourceId, String sourceType, BigDecimal amount) {
        CreditLedgerEntry e = new CreditLedgerEntry();
        e.setUserId(userId);
        e.setExecutorUserId(userId);
        e.setAmount(amount);
        e.setBalanceAfter(amount);
        e.setSourceType(sourceType);
        e.setSourceId(sourceId);
        e.setDescription("seeded by test");
        ledgerRepository.save(e);
    }

    // ─────────────────────────── defect 3 ───────────────────────────

    @Test
    @DisplayName("PLAN_RESET records the balance the row actually held, not the caller's stale copy")
    void planResetRecordsTheLiveBalance() {
        LocalDateTime periodStart = LocalDateTime.now().minusMonths(2);
        Seeded sub = seedExpiredInternalSub("stale@test.local", new BigDecimal("200"), periodStart);

        // A caller loads the subscription (detached from here on) ...
        Subscription staleCopy = subscriptionRepository.findById(sub.subId()).orElseThrow();

        // ... the wallet then moves underneath it, exactly as a concurrent grant or a
        // sibling subscription row in the same scheduler pass would move it.
        Subscription live = subscriptionRepository.findById(sub.subId()).orElseThrow();
        live.setRemainingCredits(new BigDecimal("500"));
        subscriptionRepository.save(live);

        creditAttributionService.attributeOnRenewal(sub.userId(), staleCopy, LocalDateTime.now());

        CreditLedgerEntry planReset = ledgerRow("PLAN_RESET").orElseThrow();
        assertThat(planReset.getAmount())
                .as("the reset must state what it really absorbed (500), not the stale 200")
                .isEqualByComparingTo(new BigDecimal("-500"));
        assertThat(liveBalance(sub.subId())).isEqualByComparingTo(FREE_GRANT);
    }

    @Test
    @DisplayName("a stale caller entity cannot resurrect an already-spent balance")
    void staleCallerEntityDoesNotResurrectSpentCredits() {
        LocalDateTime periodStart = LocalDateTime.now().minusMonths(2);
        Seeded sub = seedExpiredInternalSub("spent@test.local", new BigDecimal("900"), periodStart);

        Subscription staleCopy = subscriptionRepository.findById(sub.subId()).orElseThrow();

        // The user spends everything after the caller loaded its copy.
        Subscription live = subscriptionRepository.findById(sub.subId()).orElseThrow();
        live.setRemainingCredits(BigDecimal.ZERO);
        subscriptionRepository.save(live);

        creditAttributionService.attributeOnRenewal(sub.userId(), staleCopy, LocalDateTime.now());

        // Balance already zero, so no PLAN_RESET row is due; the fresh grant still lands
        // and the 900 stale credits must NOT come back.
        assertThat(ledgerRow("PLAN_RESET")).isEmpty();
        assertThat(liveBalance(sub.subId())).isEqualByComparingTo(FREE_GRANT);
    }
}
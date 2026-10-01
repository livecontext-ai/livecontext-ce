package com.apimarketplace.auth.integration;

import com.apimarketplace.auth.domain.*;
import com.apimarketplace.auth.repository.*;
import com.apimarketplace.auth.service.CreditService;
import com.apimarketplace.auth.service.RewardService;
import com.apimarketplace.auth.service.PersonalOfferService;
import com.apimarketplace.auth.validation.UsernameValidator;
import com.stripe.StripeClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;

/** Exercises the real wallet and Spring transactions, not a mock of the grant method. */
@SpringBootTest
@DisplayName("A personal offer reaches the PAYG ledger once and survives transaction retries")
class PersonalOfferCreditPersistenceTest extends AuthScratchPostgresSpringTest {
    @Autowired private RewardService rewards;
    @Autowired private RewardCodeRepository codes;
    @Autowired private RewardRedemptionRepository redemptions;
    @Autowired private SubscriptionRepository subscriptions;
    @Autowired private BillingCustomerRepository customers;
    @Autowired private UserRepository users;
    @Autowired private PlanRepository plans;
    @Autowired private PersonalOfferPolicyRepository policies;
    @Autowired private PersonalOfferMatrixRepository matrix;
    @Autowired private PersonalOfferFirstPaidPurchaseRepository firstPaid;
    @Autowired private PersonalOfferCheckoutAttemptRepository attempts;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    // The narrow wallet fixture has no credential tables or external OAuth providers.
    @MockitoBean private com.apimarketplace.auth.credential.service.OAuth2RefreshScheduler oauthRefresh;
    @MockitoSpyBean private CreditService credits;
    private Subscription subscription;
    private RewardCode code;
    private UUID attemptId;

    @BeforeEach
    void seedPaidRecipient() {
        reset(credits);
        jdbc.execute("TRUNCATE auth.reward_redemption,auth.personal_offer_checkout_attempt,auth.reward_code,"
                + "auth.personal_offer_matrix,auth.personal_offer_policy,auth.personal_offer_first_paid_purchase,"
                + "auth.credit_ledger,auth.subscription,auth.billing_customer,auth.users,auth.plan RESTART IDENTITY CASCADE");
        jdbc.execute("ALTER TABLE auth.reward_code ALTER COLUMN created_at SET DEFAULT now()");
        jdbc.execute("ALTER TABLE auth.personal_offer_policy ALTER COLUMN created_at SET DEFAULT now()");
        jdbc.execute("ALTER TABLE auth.personal_offer_checkout_attempt ALTER COLUMN created_at SET DEFAULT now()");
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_personal_offer_grant_user_campaign ON "
                + "auth.reward_redemption(redeemer_user_id,campaign_key) WHERE program='PERSONAL_UPGRADE'");
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_personal_offer_grant_invoice ON "
                + "auth.reward_redemption(qualifying_invoice_id) WHERE program='PERSONAL_UPGRADE'");

        User user = new User();
        user.setEmail("personal.offer@livecontext.test");
        user.setUsername("personal.offer@livecontext.test");
        user.setEmailVerified(true);
        user = users.save(user);
        BillingCustomer customer = customers.save(new BillingCustomer(user, "stripe"));
        Plan plan = new Plan();
        plan.setCode("PRO");
        plan.setName("Pro");
        plan = plans.save(plan);
        subscription = new Subscription();
        subscription.setBillingCustomer(customer);
        subscription.setPlan(plan);
        subscription.setProvider("stripe");
        subscription.setProviderSubscriptionId("sub_personal_test");
        subscription.setStatus("active");
        subscription.setCadence("monthly");
        subscription.setQuantity(1);
        subscription.setCreditQuantity(42);
        subscription.setRemainingCredits(new BigDecimal("50000"));
        subscription.setPaygRemainingCredits(new BigDecimal("7"));
        subscription.setCancelAtPeriodEnd(false);
        subscription.setDelinquent(false);
        subscription.setCurrentPeriodStart(LocalDateTime.now());
        subscription.setCurrentPeriodEnd(LocalDateTime.now().plusMonths(1));
        subscription = subscriptions.save(subscription);

        PersonalOfferPolicy policy = new PersonalOfferPolicy();
        policy.setCampaignKey("free-credit-upgrade");
        policy.setVersion(1);
        policy.setLabel("Personal offer test");
        policy.setState("PAUSED");
        policy = policies.save(policy);
        code = new RewardCode();
        code.setCode("PERSONAL23456789TESTABCDE");
        code.setProgram(RewardProgram.PERSONAL_UPGRADE);
        code.setRecipientUserId(user.getId());
        code.setCampaignKey("free-credit-upgrade");
        code.setPolicyVersionId(policy.getId());
        code.setIssuedAt(Instant.now().minusSeconds(3600));
        code.setBenefitKind(BenefitKind.CREDIT_GRANT);
        code.setBenefitTrigger(BenefitTrigger.PAID_CONVERSION);
        code.setClawbackEnabled(true);
        code.setValidFrom(Instant.now().minusSeconds(3600));
        code.setValidUntil(Instant.now().plusSeconds(3600));
        code = codes.save(code);
        PersonalOfferCheckoutAttempt attempt = new PersonalOfferCheckoutAttempt();
        attemptId = UUID.randomUUID();
        attempt.setId(attemptId);
        attempt.setRewardCodeId(code.getId());
        attempt.setRecipientUserId(user.getId());
        attempt.setPolicyVersionId(policy.getId());
        attempt.setPlanCode("PRO");
        attempt.setPlanPriceId("price_pro_frozen");
        attempt.setCreditPriceId("price_credits_frozen");
        attempt.setCreditTierIndex(3);
        attempt.setMonthlyCredits(50000);
        attempt.setCadence("monthly");
        attempt.setBonusCredits(8000);
        attempt.setSessionExpiresAt(Instant.now().plusSeconds(1800));
        attempt.setNextReconcileAt(Instant.now().minusSeconds(60));
        attempt.setStatus("PAID");
        attempts.save(attempt);
    }

    @Test
    @DisplayName("A verified purchase adds PAYG once without changing the subscription's monthly credits")
    void grantIsPersistentAndIdempotent() {
        RewardRedemption grant = qualify();

        assertThat(rewards.releaseOne(grant.getId())).isTrue();
        assertThat(rewards.releaseOne(grant.getId())).isFalse();

        Subscription actual = subscriptions.findById(subscription.getId()).orElseThrow();
        assertThat(actual.getPaygRemainingCredits()).isEqualByComparingTo("8007");
        assertThat(actual.getRemainingCredits()).isEqualByComparingTo("50000");
        assertThat(ledgerCount("REWARD_CODE")).isEqualTo(1);
        assertThat(redemptions.findById(grant.getId()).orElseThrow().getStatus()).isEqualTo(RewardStatus.RELEASED);
    }

    @Test
    @DisplayName("Only the qualifying invoice is clawed back and a repeated refund cannot debit twice")
    void refundIsInvoiceBoundAndIdempotent() {
        RewardRedemption grant = qualify();
        rewards.releaseOne(grant.getId());

        rewards.clawbackPersonalByInvoice("invoice_unrelated", "refund");
        assertThat(subscriptions.findById(subscription.getId()).orElseThrow().getPaygRemainingCredits()).isEqualByComparingTo("8007");
        rewards.clawbackPersonalByInvoice("invoice_personal_test", "refund");
        rewards.clawbackPersonalByInvoice("invoice_personal_test", "refund");

        assertThat(subscriptions.findById(subscription.getId()).orElseThrow().getPaygRemainingCredits()).isEqualByComparingTo("7");
        assertThat(ledgerCount("REWARD_CLAWBACK")).isEqualTo(1);
        assertThat(redemptions.findById(grant.getId()).orElseThrow().getStatus()).isEqualTo(RewardStatus.CLAWED_BACK);
    }

    @Test
    @DisplayName("A failed wallet write leaves the reward qualified for a safe retry instead of marking it granted")
    void failedGrantRollsBackAndCanRetry() {
        RewardRedemption grant = qualify();
        doThrow(new IllegalStateException("wallet unavailable")).when(credits)
                .grantCredits(anyLong(), any(BigDecimal.class), eq("REWARD_CODE"), anyString(), anyString());

        assertThatThrownBy(() -> rewards.releaseOne(grant.getId())).hasMessageContaining("wallet unavailable");

        assertThat(redemptions.findById(grant.getId()).orElseThrow().getStatus()).isEqualTo(RewardStatus.QUALIFIED);
        assertThat(ledgerCount("REWARD_CODE")).isZero();
        reset(credits);
        rewards.releaseOne(grant.getId());
        assertThat(subscriptions.findById(subscription.getId()).orElseThrow().getPaygRemainingCredits()).isEqualByComparingTo("8007");
    }

    private RewardRedemption qualify() {
        return rewards.qualifyPersonalPaid(code, attemptId, "sub_personal_test", "invoice_personal_test", 8000, Instant.now());
    }

    @Test
    @DisplayName("Deferring an unresolved attempt lets the next backlog entry progress, while terminal grants leave the queue")
    void reconciliationQueueDoesNotStarveOlderUnresolvedPayments() {
        Instant now = Instant.now();
        PersonalOfferCheckoutAttempt later = new PersonalOfferCheckoutAttempt();
        BeanUtils.copyProperties(attempts.findById(attemptId).orElseThrow(), later);
        later.setId(UUID.randomUUID());
        later.setNextReconcileAt(now.minusSeconds(30));
        attempts.save(later);

        assertThat(attempts.findDueForReconcile(now, PageRequest.of(0, 1)))
                .extracting(PersonalOfferCheckoutAttempt::getId).containsExactly(attemptId);
        new TransactionTemplate(transactions).executeWithoutResult(status ->
                attempts.scheduleNextReconcile(attemptId, now.plusSeconds(900)));

        assertThat(attempts.findDueForReconcile(now, PageRequest.of(0, 1)))
                .extracting(PersonalOfferCheckoutAttempt::getId).containsExactly(later.getId());
        later.setStatus("GRANTED");
        attempts.save(later);
        assertThat(attempts.findDueForReconcile(now, PageRequest.of(0, 1))).isEmpty();
    }

    private int ledgerCount(String source) {
        return jdbc.queryForObject("SELECT count(*) FROM auth.credit_ledger WHERE user_id=? AND source_type=?", Integer.class,
                code.getRecipientUserId(), source);
    }

    @Test
    @DisplayName("Distinct usernames that normalize alike receive distinct codes even when issued concurrently")
    void concurrentNormalizedUsernameCollisionUsesNumericSuffix() throws Exception {
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_reward_code_code ON auth.reward_code(upper(code))");
        PersonalOfferPolicy policy = policies.findById(code.getPolicyVersionId()).orElseThrow();
        policy.setState("ACTIVE");
        policies.save(policy);
        Plan free = new Plan();
        free.setCode("FREE");
        free.setName("Free");
        free = plans.save(free);
        long firstUser = freeNamedRecipient("lucas.test", free);
        long secondUser = freeNamedRecipient("lucas-test", free);
        var offers = new PersonalOfferService(policies, matrix, attempts, firstPaid, codes,
                redemptions, users, subscriptions, customers, mock(StripeClient.class), new UsernameValidator(users));
        var firstIssued = new CountDownLatch(1);
        var commitFirst = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                String issued = offers.issueForEligible(firstUser, "free-credit-upgrade", Instant.now())
                        .orElseThrow().code();
                firstIssued.countDown();
                await().atMost(10, TimeUnit.SECONDS).until(() -> commitFirst.getCount() == 0);
                return issued;
            }));
            assertThat(firstIssued.await(10, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> new TransactionTemplate(transactions).execute(status ->
                    offers.issueForEligible(secondUser, "free-credit-upgrade", Instant.now()).orElseThrow().code()));
            await().atMost(5, TimeUnit.SECONDS).until(() -> jdbc.queryForObject(
                    "SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted", Integer.class) > 0);
            commitFirst.countDown();

            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo("LUCAS-TEST-BONUS-72H");
            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo("LUCAS-TEST-BONUS-72H-2");
            assertThat(codes.findByCodeIgnoreCase("lucas-test-bonus-72h-2").orElseThrow().getRecipientUserId())
                    .isEqualTo(secondUser);
        } finally {
            commitFirst.countDown();
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private long freeNamedRecipient(String username, Plan free) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@livecontext.test");
        user.setFirstName("Lucas");
        user.setEmailVerified(true);
        user = users.save(user);
        jdbc.update("UPDATE auth.users SET marketing_consent=true WHERE id=?", user.getId());
        BillingCustomer customer = customers.save(new BillingCustomer(user, "internal"));
        Subscription freeSubscription = new Subscription();
        BeanUtils.copyProperties(subscription, freeSubscription);
        freeSubscription.setId(null);
        freeSubscription.setBillingCustomer(customer);
        freeSubscription.setProvider("internal");
        freeSubscription.setProviderSubscriptionId(null);
        freeSubscription.setPlan(free);
        subscriptions.save(freeSubscription);
        PersonalOfferFirstPaidPurchase history = new PersonalOfferFirstPaidPurchase();
        history.setUserId(user.getId());
        history.setStatus("VERIFIED_NEW");
        firstPaid.save(history);
        return user.getId();
    }
}

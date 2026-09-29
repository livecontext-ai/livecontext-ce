package com.apimarketplace.auth.integration;

import com.apimarketplace.auth.domain.*;
import com.apimarketplace.auth.repository.*;
import com.apimarketplace.auth.service.RewardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V549 through the real Spring context on a real Postgres: the new JPQL queries run against
 * Hibernate (a mocked repository proves nothing about a query string), and a creator code is
 * redeemed end to end through the real services and transaction, so the credits must land in
 * the PAYG bucket, the plan must become a timed PRO, and the single use must be consumed.
 */
@SpringBootTest
@DisplayName("Partner program - queries and a real creator-code redeem (real Postgres)")
class PartnerProgramPersistenceTest extends AuthScratchPostgresSpringTest {

    @Autowired private RewardService rewardService;
    @Autowired private RewardCodeRepository codeRepository;
    @Autowired private RewardRedemptionRepository redemptionRepository;
    @Autowired private PartnerCommissionRepository commissionRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private BillingCustomerRepository billingCustomerRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PlanRepository planRepository;
    @Autowired private CreditLedgerRepository ledgerRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private Plan free;
    private Plan pro;

    @BeforeEach
    void reset() {
        // ddl-auto builds these tables from the entities, which leave created_at to the database
        // (insertable = false); the migrations give it DEFAULT now(), so the test schema must too.
        jdbcTemplate.execute("ALTER TABLE auth.reward_code ALTER COLUMN created_at SET DEFAULT now()");
        jdbcTemplate.execute("ALTER TABLE auth.partner_commission ALTER COLUMN created_at SET DEFAULT now()");
        commissionRepository.deleteAll();
        redemptionRepository.deleteAll();
        codeRepository.deleteAll();
        ledgerRepository.deleteAll();
        subscriptionRepository.deleteAll();
        billingCustomerRepository.deleteAll();
        userRepository.deleteAll();
        planRepository.deleteAll();
        free = plan("FREE", 1000L);
        pro = plan("PRO", null);
    }

    private Plan plan(String code, Long included) {
        Plan p = new Plan();
        p.setCode(code);
        p.setName(code);
        p.setIncludedLlmTokens(included);
        return planRepository.save(p);
    }

    private Subscription internalSub(String email, Plan plan, LocalDateTime compEndsAt) {
        User user = new User();
        user.setEmail(email);
        user.setUsername(email);
        user.setEmailVerified(true);
        user = userRepository.save(user);
        BillingCustomer bc = billingCustomerRepository.save(new BillingCustomer(user, "internal"));
        Subscription sub = new Subscription();
        sub.setBillingCustomer(bc);
        sub.setPlan(plan);
        sub.setProvider("internal");
        sub.setStatus("active");
        sub.setCadence("monthly");
        sub.setQuantity(1);
        sub.setCreditQuantity(0);
        sub.setCancelAtPeriodEnd(false);
        sub.setDelinquent(false);
        sub.setRemainingCredits(BigDecimal.ZERO);
        sub.setPaygRemainingCredits(BigDecimal.ZERO);
        sub.setCurrentPeriodStart(LocalDateTime.now().minusDays(3));
        sub.setCurrentPeriodEnd(LocalDateTime.now().plusDays(27));
        sub.setCompEndsAt(compEndsAt);
        return subscriptionRepository.save(sub);
    }

    private RewardCode code(String value, RewardProgram program, BenefitKind kind) {
        RewardCode c = new RewardCode();
        c.setCode(value);
        c.setProgram(program);
        c.setBenefitKind(kind);
        c.setBenefitTrigger(BenefitTrigger.REDEEM_TIME);
        c.setValidFrom(Instant.now().minus(1, ChronoUnit.DAYS));
        c.setActive(true);
        return c;
    }

    @Test
    @DisplayName("findEndedInternalComps: a comp past its end mid-period is found; a running one and a permanent one are not")
    void endedCompsQuery() {
        Subscription ended = internalSub("ended@x.io", pro, LocalDateTime.now().minusMinutes(1));
        internalSub("running@x.io", pro, LocalDateTime.now().plusDays(10));
        internalSub("permanent@x.io", pro, null);

        List<Subscription> found = subscriptionRepository.findEndedInternalComps(LocalDateTime.now());

        assertThat(found).extracting(Subscription::getId).containsExactly(ended.getId());
    }

    @Test
    @DisplayName("findPartnerProgramCodes: partner and creator codes only, newest first (never referral or legacy promo)")
    void programCodesQuery() {
        RewardCode creator = code("LC-AAAA2222", RewardProgram.PROMO, BenefitKind.CREDIT_GRANT);
        creator.setCapScope(CapScope.GLOBAL);
        creator.setCapLimit(1);
        creator = codeRepository.save(creator);
        RewardCode legacy = code("LEGACY01", RewardProgram.PROMO, BenefitKind.FREE_NODE_COUNTER);
        codeRepository.save(legacy);
        RewardCode referral = code("REFER001", RewardProgram.REFERRAL, BenefitKind.CREDIT_GRANT);
        referral.setOwnerUserId(5L);
        referral.setBenefitTrigger(BenefitTrigger.PAID_CONVERSION);
        codeRepository.save(referral);
        RewardCode partner = code("TECHDOX", RewardProgram.PARTNER, BenefitKind.CREDIT_GRANT);
        partner.setOwnerUserId(9L);
        partner.setOwnerRewardKind(OwnerRewardKind.PARTNER_PAYOUT);
        partner.setPayoutBps(3000);
        partner.setPayoutMonths(12);
        partner = codeRepository.save(partner);

        assertThat(codeRepository.findPartnerProgramCodes()).extracting(RewardCode::getId)
                .containsExactly(partner.getId(), creator.getId());
    }

    @Test
    @DisplayName("commission queries: first paid instant anchors the window; unsettled lines come newest first")
    void commissionQueries() {
        RewardCode partner = code("TECHDOX", RewardProgram.PARTNER, BenefitKind.CREDIT_GRANT);
        partner.setOwnerUserId(9L);
        partner = codeRepository.save(partner);
        RewardRedemption r = new RewardRedemption();
        r.setRewardCodeId(partner.getId());
        r.setRedeemerUserId(7L);
        r.setOwnerUserId(9L);
        r.setProgram(RewardProgram.PARTNER);
        r.setStatus(RewardStatus.GRANTED);
        r.setRedeemedAt(Instant.now());
        r.setActive(true);
        r = redemptionRepository.save(r);
        Instant jan = Instant.parse("2026-01-15T00:00:00Z");
        Instant feb = Instant.parse("2026-02-15T00:00:00Z");
        saveLine(r, partner, "in_feb", feb);
        saveLine(r, partner, "in_jan", jan);

        assertThat(commissionRepository.findFirstInvoicePaidAt(r.getId())).contains(jan);
        assertThat(commissionRepository.findByCustomerUserIdAndStatusOrderByInvoicePaidAtDesc(7L, PartnerCommission.Status.HOLD))
                .extracting(PartnerCommission::getProviderInvoiceId).containsExactly("in_feb", "in_jan");
        assertThat(commissionRepository.existsByProviderInvoiceId("in_jan")).isTrue();

        // Settling is conditional on HOLD: the second attempt (or one after a refund void) is a no-op.
        Long janId = commissionRepository.findByProviderInvoiceId("in_jan").orElseThrow().getId();
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        Integer first = tx.execute(st -> commissionRepository.markPaidIfOnHold(janId, Instant.now(), 42L));
        Integer second = tx.execute(st -> commissionRepository.markPaidIfOnHold(janId, Instant.now(), 42L));
        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status || ':' || paid_by_user_id FROM auth.partner_commission WHERE id = ?", String.class, janId))
                .isEqualTo("PAID:42");
        // A refund void arriving after the payout leaves the PAID record intact: status, who, when.
        java.sql.Timestamp paidAt = jdbcTemplate.queryForObject(
                "SELECT paid_at FROM auth.partner_commission WHERE id = ?", java.sql.Timestamp.class, janId);
        Integer lateVoid = tx.execute(st -> commissionRepository.voidIfOnHold(janId, Instant.now(), "REFUNDED"));
        assertThat(lateVoid).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status || ':' || paid_by_user_id FROM auth.partner_commission WHERE id = ?", String.class, janId))
                .isEqualTo("PAID:42");
        assertThat(paidAt).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT paid_at FROM auth.partner_commission WHERE id = ?", java.sql.Timestamp.class, janId)).isEqualTo(paidAt);
        // And the other way round: a line voided first is never paid.
        Long febId = commissionRepository.findByProviderInvoiceId("in_feb").orElseThrow().getId();
        Integer voided = tx.execute(st -> commissionRepository.voidIfOnHold(febId, Instant.now(), "REFUNDED"));
        Integer latePay = tx.execute(st -> commissionRepository.markPaidIfOnHold(febId, Instant.now(), 42L));
        assertThat(voided).isEqualTo(1);
        assertThat(latePay).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM auth.partner_commission WHERE id = ?", String.class, febId)).isEqualTo("VOID");
    }

    private void saveLine(RewardRedemption r, RewardCode c, String invoice, Instant paidAt) {
        PartnerCommission l = new PartnerCommission();
        l.setRedemptionId(r.getId());
        l.setRewardCodeId(c.getId());
        l.setPartnerUserId(9L);
        l.setCustomerUserId(7L);
        l.setProviderInvoiceId(invoice);
        l.setBaseAmountMinor(2400);
        l.setCurrency("usd");
        l.setPayoutBps(3000);
        l.setCommissionMinor(720);
        l.setInvoicePaidAt(paidAt);
        l.setDueAt(paidAt.plus(14, ChronoUnit.DAYS));
        commissionRepository.save(l);
    }

    @Test
    @DisplayName("a real creator-code redeem: 50,000 credits land in PAYG, the account is PRO until the code's end, the single use is spent")
    void realCreatorCodeRedeem() {
        Subscription sub = internalSub("creator@x.io", free, null);
        Long userId = sub.getBillingCustomer().getUser().getId();
        RewardCode creator = code("LC-CREATOR9", RewardProgram.PROMO, BenefitKind.CREDIT_GRANT);
        creator.setBenefitAmount(50_000);
        creator.setBenefitPlanCode("PRO");
        creator.setBenefitPlanDays(90);
        creator.setCapScope(CapScope.GLOBAL);
        creator.setCapLimit(1);
        codeRepository.save(creator);

        RewardService.RedeemResult result = rewardService.redeem(userId, "LC-CREATOR9");

        assertThat(result.status()).isEqualTo(RewardService.RedeemStatus.SUCCESS);
        // Read back through SQL: what the database kept, not a detached entity.
        var row = jdbcTemplate.queryForMap("""
                SELECT p.code AS plan_code, s.comp_ends_at, s.payg_remaining_credits
                FROM auth.subscription s JOIN auth.plan p ON p.id = s.plan_id
                WHERE s.id = ?""", sub.getId());
        assertThat(row.get("plan_code")).isEqualTo("PRO");
        assertThat(((java.sql.Timestamp) row.get("comp_ends_at")).toLocalDateTime())
                .isAfter(LocalDateTime.now().plusDays(89));
        assertThat((BigDecimal) row.get("payg_remaining_credits")).isEqualByComparingTo("50000");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT current_redemptions FROM auth.reward_code WHERE code = 'LC-CREATOR9'", Integer.class)).isEqualTo(1);

        // Single use: nobody else can take it.
        Subscription other = internalSub("other@x.io", free, null);
        assertThat(rewardService.redeem(other.getBillingCustomer().getUser().getId(), "LC-CREATOR9").status())
                .isEqualTo(RewardService.RedeemStatus.EXHAUSTED);
    }
}

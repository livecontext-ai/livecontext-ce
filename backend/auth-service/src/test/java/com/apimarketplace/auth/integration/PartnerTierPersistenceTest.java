package com.apimarketplace.auth.integration;

import com.apimarketplace.auth.domain.PartnerCommission;
import com.apimarketplace.auth.domain.PartnerTier;
import com.apimarketplace.auth.repository.PartnerCommissionRepository;
import com.apimarketplace.auth.repository.PartnerStandingRepository;
import com.apimarketplace.auth.service.PartnerTierService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V556 through the real Spring context on a real Postgres: the settled-revenue JPQL, the native
 * raise through Hibernate, and the tier service end to end. A mocked repository proves nothing
 * about a query string, and the whole promise ("a tier never goes down") rests on these two.
 */
@SpringBootTest
// A founder window far in the future: the founder approval below must not start failing on 2027-01-01.
@org.springframework.test.context.TestPropertySource(properties = "reward.partner.founder-until=2100-01-01T00:00:00Z")
@DisplayName("Partner tiers - settled revenue and the raise, through the real context (real Postgres)")
class PartnerTierPersistenceTest extends AuthScratchPostgresSpringTest {

    private static final long PARTNER = 7001L;

    @Autowired private PartnerCommissionRepository commissionRepository;
    @Autowired private PartnerStandingRepository standingRepository;
    @Autowired private PartnerTierService tierService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private com.apimarketplace.auth.service.PartnerProgramService programService;
    @Autowired private com.apimarketplace.auth.repository.PartnerApplicationRepository applicationRepository;
    @Autowired private com.apimarketplace.auth.repository.RewardCodeRepository codeRepository;
    @Autowired private com.apimarketplace.auth.repository.UserRepository userRepository;

    @BeforeEach
    void reset() {
        // ddl-auto leaves created_at to the database (insertable = false); the migrations give it a default.
        jdbcTemplate.execute("ALTER TABLE auth.partner_commission ALTER COLUMN created_at SET DEFAULT now()");
        jdbcTemplate.execute("ALTER TABLE auth.partner_application ALTER COLUMN created_at SET DEFAULT now()");
        jdbcTemplate.execute("ALTER TABLE auth.reward_code ALTER COLUMN created_at SET DEFAULT now()");
        commissionRepository.deleteAll();
        standingRepository.deleteAll();
        applicationRepository.deleteAll();
    }

    @Test
    @DisplayName("approving as a founder, through the real service and transaction: approved, code at the Silver rate, Platinum for life")
    void founderApprovalEndToEnd() {
        com.apimarketplace.auth.domain.User user = new com.apimarketplace.auth.domain.User();
        String email = "founder-" + java.util.UUID.randomUUID() + "@x.io";
        user.setEmail(email);
        user.setUsername(email);
        user.setEmailVerified(true);
        Long userId = userRepository.save(user).getId();
        com.apimarketplace.auth.domain.PartnerApplication a = new com.apimarketplace.auth.domain.PartnerApplication();
        a.setUserId(userId);
        a.setCompanyName("Founding Agency");
        Long applicationId = applicationRepository.saveAndFlush(a).getId();

        var outcome = programService.approve(applicationId, 1L, null, null, true);

        assertThat(outcome.success()).isTrue();
        assertThat(applicationRepository.findById(applicationId)).get()
                .extracting(com.apimarketplace.auth.domain.PartnerApplication::getStatus)
                .isEqualTo(com.apimarketplace.auth.domain.PartnerApplication.Status.APPROVED);
        var code = codeRepository.findByOwnerUserIdAndProgram(userId, com.apimarketplace.auth.domain.RewardProgram.PARTNER).orElseThrow();
        assertThat(code.getPayoutBps()).isEqualTo(3000);
        var standing = standingRepository.findById(userId).orElseThrow();
        assertThat(standing.getTier()).isEqualTo(PartnerTier.PLATINUM);
        assertThat(standing.isFounder()).isTrue();
        assertThat(standing.getUpdatedByUserId()).isEqualTo(1L);
        // The code keeps its Silver rate; the founder tier lifts what it earns to the Platinum rate.
        assertThat(programService.effectiveCommissionPercent(code)).isEqualTo(50.0);
        codeRepository.delete(code);
    }

    private void line(long partner, String invoice, long base, String currency, PartnerCommission.Status status,
                      Instant invoicePaidAt) {
        Instant dueAt = invoicePaidAt.plus(14, ChronoUnit.DAYS);
        PartnerCommission c = new PartnerCommission();
        c.setRedemptionId(1L);
        c.setRewardCodeId(1L);
        c.setPartnerUserId(partner);
        c.setCustomerUserId(99L);
        c.setProviderInvoiceId(invoice);
        c.setBaseAmountMinor(base);
        c.setCurrency(currency);
        c.setPayoutBps(3000);
        c.setCommissionMinor(base * 3 / 10);
        c.setStatus(status);
        c.setInvoicePaidAt(invoicePaidAt);
        c.setDueAt(dueAt);
        commissionRepository.save(c);
    }

    @Test
    @DisplayName("settled revenue: lines paid on or before the cutoff and not voided count; later ones, VOID, other currency and other partners do not")
    void settledRevenueCountsOnlyInvoicesPastTheSettleWindow() {
        Instant cutoff = Instant.now().minus(60, ChronoUnit.DAYS);
        line(PARTNER, "in_paid", 1_000, "usd", PartnerCommission.Status.PAID, cutoff.minus(30, ChronoUnit.DAYS));
        line(PARTNER, "in_on_hold_old", 2_000, "usd", PartnerCommission.Status.HOLD, cutoff.minus(1, ChronoUnit.DAYS));
        line(PARTNER, "in_exactly_at_cutoff", 100, "usd", PartnerCommission.Status.HOLD, cutoff);
        line(PARTNER, "in_too_recent", 4_000, "usd", PartnerCommission.Status.PAID, cutoff.plus(1, ChronoUnit.DAYS));
        line(PARTNER, "in_refunded", 8_000, "usd", PartnerCommission.Status.VOID, cutoff.minus(1, ChronoUnit.DAYS));
        line(PARTNER, "in_eur", 16_000, "eur", PartnerCommission.Status.PAID, cutoff.minus(30, ChronoUnit.DAYS));
        line(PARTNER + 1, "in_other", 32_000, "usd", PartnerCommission.Status.PAID, cutoff.minus(30, ChronoUnit.DAYS));

        assertThat(commissionRepository.sumSettledRevenue(PARTNER, "usd", cutoff)).isEqualTo(3_100L);
        assertThat(commissionRepository.sumSettledRevenue(PARTNER + 2, "usd", cutoff)).isZero();
    }

    @Test
    @DisplayName("regression: an invoice paid last week does not lift a tier yet, even past its 14-day payout hold")
    void recentInvoiceDoesNotLiftATier() {
        line(PARTNER, "in_recent_big", 3_000_000, "usd", PartnerCommission.Status.PAID, Instant.now().minus(20, ChronoUnit.DAYS));

        assertThat(tierService.refresh(PARTNER).tier()).isEqualTo(PartnerTier.SILVER);
        assertThat(standingRepository.count()).isZero();
    }

    @Test
    @DisplayName("refresh: crossing the Gold threshold on settled revenue raises and persists the tier")
    void refreshPersistsTheEarnedTier() {
        line(PARTNER, "in_big", 500_000, "usd", PartnerCommission.Status.PAID, Instant.now().minus(61, ChronoUnit.DAYS));

        PartnerTierService.Standing s = tierService.refresh(PARTNER);

        assertThat(s.tier()).isEqualTo(PartnerTier.GOLD);
        assertThat(s.nextTier()).isEqualTo(PartnerTier.PLATINUM);
        assertThat(standingRepository.findById(PARTNER)).get()
                .extracting(r -> r.getTier()).isEqualTo(PartnerTier.GOLD);
        assertThat(tierService.tierOf(PARTNER)).isEqualTo(PartnerTier.GOLD);
    }

    @Test
    @DisplayName("regression: a refund after a promotion never demotes (the revenue drops, the tier stays)")
    void refundAfterPromotionKeepsTheTier() {
        line(PARTNER, "in_big", 500_000, "usd", PartnerCommission.Status.PAID, Instant.now().minus(61, ChronoUnit.DAYS));
        tierService.refresh(PARTNER);
        jdbcTemplate.update("UPDATE auth.partner_commission SET status = 'VOID' WHERE provider_invoice_id = 'in_big'");

        PartnerTierService.Standing s = tierService.refresh(PARTNER);

        assertThat(s.revenueMinor()).isZero();
        assertThat(s.tier()).isEqualTo(PartnerTier.GOLD);
    }

    @Test
    @DisplayName("no revenue and no row: Silver, and nothing is written")
    void silverWritesNothing() {
        assertThat(tierService.refresh(PARTNER).tier()).isEqualTo(PartnerTier.SILVER);
        assertThat(standingRepository.count()).isZero();
    }

    @Test
    @DisplayName("two raises at once (Gold and Platinum): Platinum wins whichever commits last")
    void concurrentRaisesKeepTheHigher() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CyclicBarrier start = new CyclicBarrier(2);
        Instant now = Instant.now();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gold = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return tx.execute(st -> standingRepository.raise(PARTNER, "GOLD", false, null, now));
            });
            var platinum = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return tx.execute(st -> standingRepository.raise(PARTNER, "PLATINUM", false, null, now));
            });
            gold.get(10, TimeUnit.SECONDS);
            platinum.get(10, TimeUnit.SECONDS);
        }

        assertThat(tierService.tierOf(PARTNER)).isEqualTo(PartnerTier.PLATINUM);
    }
}

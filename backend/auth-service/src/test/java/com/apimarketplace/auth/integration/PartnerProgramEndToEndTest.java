package com.apimarketplace.auth.integration;

import com.apimarketplace.auth.domain.BillingCustomer;
import com.apimarketplace.auth.domain.PartnerApplication;
import com.apimarketplace.auth.domain.PartnerCommission;
import com.apimarketplace.auth.domain.PartnerTier;
import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.RewardCode;
import com.apimarketplace.auth.domain.RewardProgram;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.BillingCustomerRepository;
import com.apimarketplace.auth.repository.CreditLedgerRepository;
import com.apimarketplace.auth.repository.PartnerApplicationRepository;
import com.apimarketplace.auth.repository.PartnerCommissionRepository;
import com.apimarketplace.auth.repository.PartnerStandingRepository;
import com.apimarketplace.auth.repository.PartnerTermsAcceptanceRepository;
import com.apimarketplace.auth.repository.PlanRepository;
import com.apimarketplace.auth.repository.RewardCodeRepository;
import com.apimarketplace.auth.repository.RewardRedemptionRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.service.PartnerCommissionService;
import com.apimarketplace.auth.service.PartnerProgramAdminService;
import com.apimarketplace.auth.service.PartnerProgramService;
import com.apimarketplace.auth.service.PartnerTermsNotAcceptedException;
import com.apimarketplace.auth.service.PartnerTermsService;
import com.apimarketplace.auth.service.PartnerTierService;
import com.apimarketplace.auth.service.RewardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The affiliate program end to end, through the real Spring context, services and transactions
 * on a real Postgres: the path a partner and their client actually take, one step feeding the
 * next. Each step is covered in isolation elsewhere; this is the proof that they still connect
 * (a renamed field, a gate in the wrong place or a broken query between two of them fails here).
 *
 * <p>apply (ticking the terms) -> approve -> the partner code -> a NEW client redeems it and gets
 * the audience credits -> the client pays an invoice -> a commission line on hold -> past the hold
 * it is paid out. And the guards on that path: no payout to a partner bound by no terms, until
 * they accept; ending founder status returns the partner to the tier their revenue earned.
 */
@SpringBootTest
// A founder window far in the future: the founder steps must not start failing on 2027-01-01.
@TestPropertySource(properties = "reward.partner.founder-until=2100-01-01T00:00:00Z")
@DisplayName("Partner program end to end - apply, approve, attribute, earn, pay (real context, real Postgres)")
class PartnerProgramEndToEndTest extends AuthScratchPostgresSpringTest {

    private static final long ADMIN = 1L;
    private static final PartnerTermsService.Evidence CLICK = new PartnerTermsService.Evidence("203.0.113.7", "Mozilla/5.0 e2e");

    @Autowired private PartnerProgramService programService;
    @Autowired private PartnerProgramAdminService adminService;
    @Autowired private PartnerCommissionService commissionService;
    @Autowired private PartnerTierService tierService;
    @Autowired private RewardService rewardService;
    @Autowired private PartnerApplicationRepository applicationRepository;
    @Autowired private PartnerCommissionRepository commissionRepository;
    @Autowired private PartnerStandingRepository standingRepository;
    @Autowired private PartnerTermsAcceptanceRepository acceptanceRepository;
    @Autowired private RewardCodeRepository codeRepository;
    @Autowired private RewardRedemptionRepository redemptionRepository;
    @Autowired private CreditLedgerRepository ledgerRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private BillingCustomerRepository billingCustomerRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PlanRepository planRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Plan free;

    @BeforeEach
    void reset() {
        // ddl-auto builds the tables from the entities; give them what the migrations give them:
        // the created_at defaults and the unique key the acceptance insert's ON CONFLICT targets.
        jdbcTemplate.execute("ALTER TABLE auth.reward_code ALTER COLUMN created_at SET DEFAULT now()");
        jdbcTemplate.execute("ALTER TABLE auth.partner_commission ALTER COLUMN created_at SET DEFAULT now()");
        jdbcTemplate.execute("ALTER TABLE auth.partner_application ALTER COLUMN created_at SET DEFAULT now()");
        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_e2e_partner_terms_acceptance_user_version "
                + "ON auth.partner_terms_acceptance (user_id, terms_version)");
        acceptanceRepository.deleteAll();
        applicationRepository.deleteAll();
        commissionRepository.deleteAll();
        standingRepository.deleteAll();
        redemptionRepository.deleteAll();
        codeRepository.deleteAll();
        ledgerRepository.deleteAll();
        subscriptionRepository.deleteAll();
        billingCustomerRepository.deleteAll();
        userRepository.deleteAll();
        planRepository.deleteAll();
        free = new Plan();
        free.setCode("FREE");
        free.setName("FREE");
        free.setIncludedLlmTokens(1000L);
        free = planRepository.save(free);
    }

    /** A new, verified account on the free plan: what a client signing up from a partner link is. */
    private Long newAccount(String email) {
        User user = new User();
        user.setEmail(email);
        user.setUsername(email);
        user.setEmailVerified(true);
        user = userRepository.save(user);
        BillingCustomer bc = billingCustomerRepository.save(new BillingCustomer(user, "internal"));
        Subscription sub = new Subscription();
        sub.setBillingCustomer(bc);
        sub.setPlan(free);
        sub.setProvider("internal");
        sub.setStatus("active");
        sub.setCadence("monthly");
        sub.setQuantity(1);
        sub.setCreditQuantity(0);
        sub.setCancelAtPeriodEnd(false);
        sub.setDelinquent(false);
        sub.setRemainingCredits(BigDecimal.ZERO);
        sub.setPaygRemainingCredits(BigDecimal.ZERO);
        sub.setCurrentPeriodStart(LocalDateTime.now().minusDays(1));
        sub.setCurrentPeriodEnd(LocalDateTime.now().plusDays(29));
        subscriptionRepository.save(sub);
        return user.getId();
    }

    /**
     * Redeem, and on a refusal say why. The code's valid_from is stamped by the JVM clock, but the
     * redeem reserves its slot in SQL against the DATABASE clock (now() >= valid_from): a code
     * redeemed within the app-to-database clock skew of its creation is refused. No person redeems
     * a code milliseconds after it is created; a test does, and the Postgres container's clock can
     * trail the JVM's by tens of milliseconds. So wait until the database sees the code as started.
     */
    private RewardService.RedeemResult redeem(Long userId, String code) throws InterruptedException {
        for (int i = 0; i < 100 && !Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT now() >= valid_from FROM auth.reward_code WHERE code = ?", Boolean.class, code)); i++) {
            Thread.sleep(20);
        }
        RewardService.RedeemResult r = rewardService.redeem(userId, code);
        assertThat(r.redemption()).as("redeem status %s, code %s", r.status(),
                jdbcTemplate.queryForList("SELECT active, valid_from, valid_until, now() AS db_now FROM auth.reward_code WHERE code = ?", code))
                .isNotNull();
        return r;
    }

    private BigDecimal paygCredits(Long userId) {
        return jdbcTemplate.queryForObject("""
                SELECT s.payg_remaining_credits FROM auth.subscription s
                JOIN auth.billing_customer b ON b.id = s.billing_customer_id WHERE b.user_id = ?""",
                BigDecimal.class, userId);
    }

    @Test
    @DisplayName("apply with the terms -> approve -> a new client redeems the code (8,000 credits) -> pays -> commission on hold -> paid out")
    void theWholePath() throws InterruptedException {
        Long partner = newAccount("agency@e2e.io");
        Long client = newAccount("client@e2e.io");

        // 1. Applying without ticking the terms is refused and stores nothing.
        assertThat(programService.apply(partner, new PartnerProgramService.ApplicationForm(
                "Acme Automation", null, null, null, null), CLICK).error()).isEqualTo("terms_not_accepted");
        assertThat(applicationRepository.count()).isZero();

        // 2. Ticked: the application and the evidence of the click are stored together.
        var applied = programService.apply(partner, new PartnerProgramService.ApplicationForm(
                "Acme Automation", "https://acme.io", null, null, PartnerTermsService.CURRENT_VERSION), CLICK);
        assertThat(applied.success()).isTrue();
        var acceptance = jdbcTemplate.queryForMap(
                "SELECT terms_version, terms_fingerprint, source, ip_address FROM auth.partner_terms_acceptance WHERE user_id = ?", partner);
        assertThat(acceptance).containsEntry("terms_version", PartnerTermsService.CURRENT_VERSION)
                .containsEntry("terms_fingerprint", PartnerTermsService.CURRENT_FINGERPRINT)
                .containsEntry("source", "APPLICATION").containsEntry("ip_address", "203.0.113.7");

        // 3. The admin approves: one partner code, at the Silver rate, giving the audience credits.
        assertThat(programService.approve(applied.application().getId(), ADMIN, null, null, false).success()).isTrue();
        assertThat(applicationRepository.findById(applied.application().getId())).get()
                .extracting(PartnerApplication::getStatus).isEqualTo(PartnerApplication.Status.APPROVED);
        RewardCode code = codeRepository.findByOwnerUserIdAndProgram(partner, RewardProgram.PARTNER).orElseThrow();
        assertThat(code.getPayoutBps()).isEqualTo(3000);
        assertThat(code.getBenefitAmount()).isEqualTo(8_000);

        // 4. A new client redeems it: attributed to the partner, 8,000 credits on their account.
        redeem(client, code.getCode());
        assertThat(paygCredits(client)).isEqualByComparingTo("8000");
        // The partner cannot bring themselves.
        assertThat(rewardService.redeem(partner, code.getCode()).status()).isEqualTo(RewardService.RedeemStatus.SELF_REFERRAL);

        // 5. The client pays $209 (excluding tax) 20 days ago: a commission at 30%, already past its 14-day hold.
        Instant paidAt = Instant.now().minus(20, ChronoUnit.DAYS);
        assertThat(commissionService.recordPaidInvoice(client, "in_e2e_1", 20_900L, "usd", paidAt))
                .isEqualTo(PartnerCommissionService.RecordOutcome.RECORDED);
        // A replayed webhook records nothing new.
        assertThat(commissionService.recordPaidInvoice(client, "in_e2e_1", 20_900L, "usd", paidAt))
                .isEqualTo(PartnerCommissionService.RecordOutcome.DUPLICATE);
        PartnerCommission line = commissionRepository.findByRewardCodeIdIn(java.util.List.of(code.getId())).get(0);
        assertThat(line.getCommissionMinor()).isEqualTo(6_270L);
        assertThat(line.getStatus()).isEqualTo(PartnerCommission.Status.HOLD);
        assertThat(line.isPayableAt(Instant.now())).isTrue();

        // 6. The admin pays it out: the partner accepted the terms, so it goes through.
        var paid = adminService.markPayablePaid(code.getId(), ADMIN);
        assertThat(paid).hasSize(1);
        assertThat(commissionRepository.findById(line.getId())).get()
                .extracting(PartnerCommission::getStatus).isEqualTo(PartnerCommission.Status.PAID);
        // Nothing payable is left: a second click settles nothing.
        assertThat(adminService.markPayablePaid(code.getId(), ADMIN)).isEmpty();
    }

    @Test
    @DisplayName("a partner the admin created without the terms earns, but is paid nothing until they accept them")
    void noPayoutWithoutTheTerms() throws InterruptedException {
        Long partner = newAccount("direct@e2e.io");
        Long client = newAccount("their-client@e2e.io");
        var created = adminService.createPartnerCode(new PartnerProgramAdminService.PartnerCodeRequest(
                partner, "DIRECT-E2E", null, null, null, null, null, null, null));
        assertThat(created.success()).as(String.valueOf(created.error())).isTrue();
        redeem(client, "DIRECT-E2E");
        assertThat(commissionService.recordPaidInvoice(client, "in_e2e_2", 10_000L, "usd",
                Instant.now().minus(20, ChronoUnit.DAYS))).isEqualTo(PartnerCommissionService.RecordOutcome.RECORDED);

        assertThatThrownBy(() -> adminService.markPayablePaid(created.code().getId(), ADMIN))
                .isInstanceOf(PartnerTermsNotAcceptedException.class);
        assertThat(commissionRepository.findByRewardCodeIdIn(java.util.List.of(created.code().getId())))
                .allMatch(c -> c.getStatus() == PartnerCommission.Status.HOLD);

        // The dashboard asks them to accept; once they do, the same payout goes through.
        assertThat(programService.acceptTerms(partner, PartnerTermsService.CURRENT_VERSION, CLICK)).isNull();
        assertThat(adminService.markPayablePaid(created.code().getId(), ADMIN)).hasSize(1);
    }

    @Test
    @DisplayName("a founder earns the Platinum rate; ending founder status returns them to the tier their revenue earned")
    void founderGrantedThenEnded() throws InterruptedException {
        Long partner = newAccount("founder@e2e.io");
        Long client = newAccount("founder-client@e2e.io");
        var created = adminService.createPartnerCode(new PartnerProgramAdminService.PartnerCodeRequest(
                partner, "FOUNDER-E2E", null, null, null, null, null, null, null));
        assertThat(created.success()).isTrue();
        assertThat(tierService.grantFounder(partner, ADMIN).success()).isTrue();
        redeem(client, "FOUNDER-E2E");

        commissionService.recordPaidInvoice(client, "in_e2e_3", 10_000L, "usd", Instant.now().minus(1, ChronoUnit.DAYS));
        assertThat(commissionRepository.findByRewardCodeIdIn(java.util.List.of(created.code().getId())).get(0)
                .getCommissionMinor()).as("Platinum rate").isEqualTo(5_000L);

        var ended = tierService.endFounder(partner, ADMIN);
        assertThat(ended.success()).isTrue();
        assertThat(ended.standing().tier()).isEqualTo(PartnerTier.SILVER);
        assertThat(standingRepository.findById(partner)).get().satisfies(s -> {
            assertThat(s.isFounder()).isFalse();
            assertThat(s.getTier()).isEqualTo(PartnerTier.SILVER);
        });
        // The next invoice earns the Silver rate, and a refresh does not bring Platinum back.
        commissionService.recordPaidInvoice(client, "in_e2e_4", 10_000L, "usd", Instant.now());
        assertThat(tierService.refresh(partner).tier()).isEqualTo(PartnerTier.SILVER);
        assertThat(commissionRepository.findByRewardCodeIdIn(java.util.List.of(created.code().getId())))
                .filteredOn(c -> "in_e2e_4".equals(c.getProviderInvoiceId()))
                .singleElement().extracting(PartnerCommission::getCommissionMinor).isEqualTo(3_000L);
    }
}

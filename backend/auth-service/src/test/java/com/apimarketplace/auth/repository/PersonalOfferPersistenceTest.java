package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.service.AccountPurgeService;
import com.apimarketplace.auth.service.PersonalOfferAdminService;
import com.apimarketplace.auth.lifecycle.PersonalOfferLifecycleRepository;
import com.apimarketplace.auth.lifecycle.ResendClient;
import com.apimarketplace.testsupport.ScratchPostgres;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Personal offer constraints on PostgreSQL using the shipped migration")
class PersonalOfferPersistenceTest {
    private static final ScratchPostgres DB = ScratchPostgres.forPrefix("CREDENTIAL_TEST_PG",
            "personal offers must be issued and granted once even when checkout and webhook workers race");
    private static JdbcTemplate jdbc;
    private long policy;

    @BeforeAll
    static void migrate() {
        DB.require();
        var source = new DriverManagerDataSource(DB.url(), DB.user(), DB.password());
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS auth");
        jdbc.execute("DROP TABLE IF EXISTS auth.personal_offer_reversal_task, auth.personal_offer_reversed_invoice, auth.personal_offer_lifecycle, auth.reward_redemption, "
                + "auth.personal_offer_checkout_attempt, auth.reward_code, auth.personal_offer_matrix, "
                + "auth.personal_offer_policy, auth.personal_offer_first_paid_purchase, auth.users CASCADE");
        // Only the pre-existing columns used by V552; every new constraint comes from the migration itself.
        jdbc.execute("CREATE TABLE auth.users(id BIGINT PRIMARY KEY)");
        jdbc.execute("""
                CREATE TABLE auth.reward_code (
                  id BIGSERIAL PRIMARY KEY, code VARCHAR(64) UNIQUE NOT NULL,
                  program VARCHAR(16) NOT NULL, owner_user_id BIGINT,
                  active BOOLEAN NOT NULL DEFAULT TRUE, valid_until TIMESTAMPTZ DEFAULT now() + interval '3 days',
                  benefit_kind VARCHAR(24) NOT NULL,
                  benefit_trigger VARCHAR(16) NOT NULL, owner_reward_kind VARCHAR(16) NOT NULL,
                  clawback_enabled BOOLEAN NOT NULL DEFAULT FALSE,
                  CONSTRAINT chk_reward_code_program CHECK (program IN ('PROMO','REFERRAL','PARTNER')))
                """);
        jdbc.execute("""
                CREATE TABLE auth.reward_redemption (
                  id BIGSERIAL PRIMARY KEY, reward_code_id BIGINT NOT NULL REFERENCES auth.reward_code(id),
                  redeemer_user_id BIGINT NOT NULL, program VARCHAR(16) NOT NULL,
                  status VARCHAR(16) NOT NULL, redeemer_reward_amount INTEGER)
                """);
        jdbc.update("INSERT INTO auth.users(id) VALUES (100)");
        var migration = Path.of("..", "migration-service", "src", "main", "resources", "db", "migration",
                "V552__personal_upgrade_offers.sql");
        new ResourceDatabasePopulator(new FileSystemResource(migration)).execute(source);
    }

    @BeforeEach
    void resetAccounts() {
        jdbc.execute("TRUNCATE auth.personal_offer_reversal_task, auth.personal_offer_lifecycle, auth.reward_redemption, "
                + "auth.personal_offer_checkout_attempt, auth.reward_code, "
                + "auth.personal_offer_first_paid_purchase, auth.users RESTART IDENTITY CASCADE");
        jdbc.update("INSERT INTO auth.users(id) VALUES (1),(2)");
        policy = jdbc.queryForObject("SELECT id FROM auth.personal_offer_policy WHERE campaign_key = "
                + "'free-credit-upgrade' AND version = 1", Long.class);
    }

    @Test
    @DisplayName("The initial campaign and its reminder are inactive while the configured packs carry the promised credits")
    void seededCampaignDoesNotStartSending() {
        assertThat(jdbc.queryForObject("SELECT state FROM auth.personal_offer_policy WHERE id = ?", String.class, policy))
                .isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT reminder_enabled FROM auth.personal_offer_policy WHERE id = ?", Boolean.class, policy))
                .isFalse();
        assertThat(bonus("STARTER", 5000)).isZero();
        assertThat(bonus("STARTER", 50000)).isEqualTo(8000);
        assertThat(bonus("PRO", 250000)).isEqualTo(40000);
        assertThat(bonus("TEAM", 500000)).isEqualTo(80000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth.personal_offer_matrix WHERE plan_code = "
                + "'STARTER' AND monthly_credits > 100000", Integer.class)).isZero();
    }

    @Test
    @DisplayName("Changing a code or policy version cannot issue another offer for the same account and campaign")
    void recipientCampaignUniquenessSurvivesAnotherCode() {
        issue(1);
        assertThatThrownBy(() -> issue(1)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth.reward_code", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("An active personal offer cannot be issued without an account-bound recipient")
    void anonymousPersonalOfferIsRejected() {
        assertThatThrownBy(() -> issue(null)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("A completed checkout awaiting payment prevents another payable reservation")
    void asynchronousPaymentKeepsExclusiveReservation() {
        long offer = issue(1);
        attempt(1, offer, "COMPLETED");
        assertThatThrownBy(() -> attempt(1, offer, "OPEN")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Two simultaneous checkout workers can reserve only one session for the account")
    void concurrentReservationsHaveOneWinner() throws Exception {
        long offer = issue(1);
        var start = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> reserveAtBarrier(start, offer));
            var second = pool.submit(() -> reserveAtBarrier(start, offer));
            assertThat(first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth.personal_offer_checkout_attempt", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("A repeated qualifying invoice cannot grant a bonus twice, even for another account")
    void invoiceCannotFundTwoRecipients() {
        long firstOffer = issue(1);
        long secondOffer = issue(2);
        redeem(1, firstOffer, "invoice_unique");
        assertThatThrownBy(() -> redeem(2, secondOffer, "invoice_unique"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth.reward_redemption", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("A different invoice cannot grant the same campaign twice to one account")
    void accountCannotReceiveCampaignTwice() {
        long offer = issue(1);
        redeem(1, offer, "invoice_first");
        assertThatThrownBy(() -> redeem(1, offer, "invoice_second"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("The admin report distinguishes a paid zero-bonus pack from an offer still available")
    void paidZeroBonusIsTerminalInAdminReport() {
        long paidOffer = issue(1);
        issue(2);
        UUID paid = attempt(1, paidOffer, "NO_BONUS");
        jdbc.update("UPDATE auth.personal_offer_checkout_attempt SET bonus_credits=0 WHERE id=?", paid);
        var admin = new PersonalOfferAdminService(null, null, jdbc);

        var report = admin.codes();

        assertThat(report).filteredOn(code -> code.recipientUserId().equals(1L)).singleElement()
                .satisfies(code -> {
                    assertThat(code.status()).isEqualTo("NO_BONUS");
                    assertThat(code.bonusCredits()).isZero();
                });
        assertThat(report).filteredOn(code -> code.recipientUserId().equals(2L)).singleElement()
                .satisfies(code -> assertThat(code.status()).isEqualTo("AVAILABLE"));
    }

    @Test
    @DisplayName("An unissued exhaustion observation can restart after a recharge, while an issued offer keeps its original observation")
    void rechargeRearmsOnlyUnissuedObservation() {
        var lifecycle = new PersonalOfferLifecycleRepository(jdbc);
        Instant first = Instant.parse("2026-09-29T10:00:00Z");
        Instant again = first.plusSeconds(3600);
        lifecycle.observe(1, first);
        lifecycle.observe(1, again);
        assertThat(lifecycle.observations(0)).singleElement()
                .satisfies(row -> assertThat(row.exhaustedAt()).isEqualTo(first));
        lifecycle.resetUnissued(1, again);
        lifecycle.observe(1, again);
        assertThat(lifecycle.observations(0)).singleElement()
                .satisfies(row -> assertThat(row.exhaustedAt()).isEqualTo(again));

        long code = issue(1);
        lifecycle.attachIssued(1, code, again);
        lifecycle.resetUnissued(1, again.plusSeconds(3600));

        assertThat(lifecycle.observations(0)).isEmpty();
        assertThat(lifecycle.issuedWithPendingEmails(0)).singleElement()
                .satisfies(row -> assertThat(row.codeId()).isEqualTo(code));
    }

    @Test
    @DisplayName("Only one email worker claims a pending step and an ambiguous send can never be retried blindly")
    void unknownSendIsNotReclaimed() {
        var lifecycle = new PersonalOfferLifecycleRepository(jdbc);
        Instant at = Instant.parse("2026-09-29T10:00:00Z");
        lifecycle.observe(1, at);
        lifecycle.attachIssued(1, issue(1), at);

        assertThat(lifecycle.claim(1, PersonalOfferLifecycleRepository.Step.INITIAL, at)).isTrue();
        assertThat(lifecycle.claim(1, PersonalOfferLifecycleRepository.Step.INITIAL, at.plusSeconds(1))).isFalse();
        lifecycle.finish(1, PersonalOfferLifecycleRepository.Step.INITIAL, at, ResendClient.EventResult.UNKNOWN, at.plusSeconds(2));

        assertThat(lifecycle.claim(1, PersonalOfferLifecycleRepository.Step.INITIAL, at.plusSeconds(3))).isFalse();
        assertThat(lifecycle.initialAccepted(1)).isFalse();
        assertThat(jdbc.queryForObject("SELECT initial_status FROM auth.personal_offer_lifecycle WHERE user_id=1", String.class))
                .isEqualTo("unknown");
    }

    @Test
    @DisplayName("A definitively unsent email can retry but the old worker cannot finalize the new claim")
    void staleWorkerCannotAcceptAnotherWorkersSend() {
        var lifecycle = new PersonalOfferLifecycleRepository(jdbc);
        Instant at = Instant.parse("2026-09-29T10:00:00Z");
        lifecycle.observe(1, at);
        lifecycle.attachIssued(1, issue(1), at);
        assertThat(lifecycle.claim(1, PersonalOfferLifecycleRepository.Step.INITIAL, at)).isTrue();
        lifecycle.finish(1, PersonalOfferLifecycleRepository.Step.INITIAL, at, ResendClient.EventResult.NOT_SENT, at.plusSeconds(1));
        assertThat(lifecycle.claim(1, PersonalOfferLifecycleRepository.Step.INITIAL, at.plusSeconds(2))).isTrue();

        lifecycle.finish(1, PersonalOfferLifecycleRepository.Step.INITIAL, at, ResendClient.EventResult.ACCEPTED, at.plusSeconds(3));

        assertThat(lifecycle.initialAccepted(1)).isFalse();
        lifecycle.finish(1, PersonalOfferLifecycleRepository.Step.INITIAL, at.plusSeconds(2), ResendClient.EventResult.ACCEPTED, at.plusSeconds(4));
        assertThat(lifecycle.initialAccepted(1)).isTrue();
    }

    @Test
    @DisplayName("A dead worker's unresolved send is held for reconciliation while suppressing emails preserves the promised code")
    void abandonedClaimAndSuppressionDoNotRevokeCode() {
        var lifecycle = new PersonalOfferLifecycleRepository(jdbc);
        Instant at = Instant.parse("2026-09-29T10:00:00Z");
        long code = issue(1);
        lifecycle.observe(1, at);
        lifecycle.attachIssued(1, code, at);
        lifecycle.claim(1, PersonalOfferLifecycleRepository.Step.INITIAL, at);

        lifecycle.markStaleClaimsUnknown(at.plusSeconds(600), at.plusSeconds(900));
        lifecycle.suppressPending(1, "opt_out", at.plusSeconds(901));

        assertThat(lifecycle.claim(1, PersonalOfferLifecycleRepository.Step.INITIAL, at.plusSeconds(902))).isFalse();
        assertThat(lifecycle.issuedWithPendingEmails(0)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT active FROM auth.reward_code WHERE id=?", Boolean.class, code)).isTrue();
    }

    @Test
    @DisplayName("Purging the recipient disables their code and clears dependent offer state without deleting another user's offer")
    void purgeRemovesAccountBindingWithoutLeavingRedeemableCode() {
        long offer = issue(1);
        long other = issue(2);
        UUID reserved = attempt(1, offer, "PAID");
        redeem(1, offer, "invoice_purged");
        jdbc.update("UPDATE auth.reward_redemption SET offer_attempt_id = ? WHERE reward_code_id = ?", reserved, offer);
        jdbc.update("INSERT INTO auth.personal_offer_lifecycle(user_id,campaign_key,offer_code_id) VALUES (1,'free-credit-upgrade',?)", offer);
        jdbc.update("INSERT INTO auth.personal_offer_first_paid_purchase(user_id,status) VALUES (1,'PAID')");

        jdbc.update(AccountPurgeService.DEACTIVATE_PERSONAL_REWARD_CODES_SQL, 1L);
        jdbc.update("DELETE FROM auth.reward_redemption WHERE redeemer_user_id = ? AND program = 'PERSONAL_UPGRADE'", 1L);
        jdbc.update("DELETE FROM auth.personal_offer_lifecycle WHERE user_id = ?", 1L);
        jdbc.update("DELETE FROM auth.personal_offer_checkout_attempt WHERE recipient_user_id = ?", 1L);
        jdbc.update("DELETE FROM auth.personal_offer_first_paid_purchase WHERE user_id = ?", 1L);
        jdbc.update("DELETE FROM auth.users WHERE id = ?", 1L);

        assertThat(jdbc.queryForObject("SELECT active FROM auth.reward_code WHERE id = ?", Boolean.class, offer)).isFalse();
        assertThat(jdbc.queryForObject("SELECT recipient_user_id FROM auth.reward_code WHERE id = ?", Long.class, offer)).isNull();
        assertThat(jdbc.queryForObject("SELECT active FROM auth.reward_code WHERE id = ?", Boolean.class, other)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth.personal_offer_lifecycle", Integer.class)).isZero();
    }

    private int bonus(String plan, int credits) {
        return jdbc.queryForObject("SELECT bonus_credits FROM auth.personal_offer_matrix WHERE policy_id = ? "
                + "AND plan_code = ? AND monthly_credits = ?", Integer.class, policy, plan, credits);
    }

    @Test
    @DisplayName("A refund survives failed invoice lookup, resolves later, and its replay cannot schedule another clawback")
    void refundLookupFailureIsDurableAndRetriesAfterProviderRecovery() throws Exception {
        var tasks = new PersonalOfferReversalRepository(jdbc);
        var stripe = org.mockito.Mockito.mock(com.stripe.StripeClient.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        var payments = org.mockito.Mockito.mock(com.apimarketplace.auth.service.PersonalOfferPaymentService.class);
        var service = new com.apimarketplace.auth.service.PersonalOfferReversalService(tasks, payments, stripe);
        var payload = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                "{\"id\":\"ch_recovery\",\"amount\":1000,\"amount_refunded\":1000}");
        var charge = new com.stripe.model.Charge();
        charge.setId("ch_recovery");
        charge.setPaymentIntent("pi_recovery");
        var payment = new com.stripe.model.InvoicePayment();
        payment.setInvoice("in_recovery");
        var page = new com.stripe.model.InvoicePaymentCollection();
        page.setData(java.util.List.of(payment));
        org.mockito.Mockito.when(stripe.charges().retrieve("ch_recovery")).thenReturn(charge);
        org.mockito.Mockito.when(stripe.invoicePayments().list(org.mockito.ArgumentMatchers.any(com.stripe.param.InvoicePaymentListParams.class)))
                .thenThrow(new IllegalStateException("Stripe unavailable")).thenReturn(page);

        service.capture("charge.refunded", payload);
        // The task is inserted with the DATABASE clock (DEFAULT now()) and selected against the
        // JVM clock (due(Instant.now())). With the database a second ahead (a Docker VM on a dev
        // machine), the first reconcile skipped it and the test failed; make it due explicitly,
        // with a margin no clock drift reaches. A freshly captured task is still checked to be
        // due at once, give or take that drift.
        assertThat(tasks.due(Instant.now().plusSeconds(60)))
                .extracting(PersonalOfferReversalRepository.Task::chargeId).contains("ch_recovery");
        jdbc.update("UPDATE auth.personal_offer_reversal_task SET next_attempt_at=now()-interval '1 hour'");
        service.reconcile();

        assertThat(tasks.pending("ch_recovery")).isPresent();
        assertThat(tasks.due(Instant.now())).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(payments);
        // Advance the persisted deadline, as a later scheduler invocation would observe it.
        jdbc.update("UPDATE auth.personal_offer_reversal_task SET next_attempt_at=now()-interval '1 hour'");
        service.reconcile();
        assertThat(tasks.pending("ch_recovery")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM auth.personal_offer_reversal_task WHERE charge_id='ch_recovery'", String.class))
                .isEqualTo("RESOLVED");

        service.capture("charge.refunded", payload);
        service.reconcile();
        org.mockito.Mockito.verify(payments, org.mockito.Mockito.times(1)).onInvoiceReversed("in_recovery", "REFUNDED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth.personal_offer_reversal_task", Integer.class)).isEqualTo(1);
    }

    private long issue(Integer recipient) {
        return jdbc.queryForObject("""
                INSERT INTO auth.reward_code(code,program,benefit_kind,benefit_trigger,owner_reward_kind,
                  clawback_enabled,recipient_user_id,campaign_key,policy_version_id,issued_at)
                VALUES (?,'PERSONAL_UPGRADE','CREDIT_GRANT','PAID_CONVERSION','NONE',TRUE,?,'free-credit-upgrade',?,now())
                RETURNING id
                """, Long.class, UUID.randomUUID().toString(), recipient, policy);
    }

    private UUID attempt(int user, long offer, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO auth.personal_offer_checkout_attempt(id,reward_code_id,recipient_user_id,policy_version_id,
                  plan_code,plan_price_id,monthly_credits,credit_tier_index,cadence,bonus_credits,session_expires_at,status)
                VALUES (?,?,?,?,'PRO','price_pro_frozen',50000,3,'monthly',8000,now() + interval '30 minutes',?)
                """, id, offer, user, policy, status);
        return id;
    }

    private int reserveAtBarrier(CyclicBarrier start, long offer) throws Exception {
        start.await(5, TimeUnit.SECONDS);
        try {
            attempt(1, offer, "CREATING");
            return 1;
        } catch (DataIntegrityViolationException rejected) {
            return 0;
        }
    }

    private void redeem(int user, long offer, String invoice) {
        jdbc.update("INSERT INTO auth.reward_redemption(reward_code_id,redeemer_user_id,program,status,campaign_key,"
                + "qualifying_invoice_id) VALUES (?,?,'PERSONAL_UPGRADE','RELEASED','free-credit-upgrade',?)", offer, user, invoice);
    }
}

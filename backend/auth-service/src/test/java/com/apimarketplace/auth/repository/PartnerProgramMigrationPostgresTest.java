package com.apimarketplace.auth.repository;

import com.apimarketplace.testsupport.ScratchPostgres;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V549 on a real Postgres: the constraints the partner program's correctness rests on.
 *
 * <p>A replayed Stripe {@code invoice.paid} must never record a second commission, and a customer
 * must never be attributed to two partners; the services check both first, but only the unique
 * constraints hold under concurrency. The Spring tests build the schema with {@code ddl-auto},
 * which cannot express a partial unique index or a CHECK, so this runs the REAL migration files
 * (V366's reward tables, then V549, twice, to prove it re-runs cleanly) and asserts on them.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("V549 partner program migration - constraints on a real Postgres")
class PartnerProgramMigrationPostgresTest {

    private static final ScratchPostgres DB = ScratchPostgres.forPrefix(
            "CREDENTIAL_TEST_PG",
            "it is the only proof that a replayed invoice never pays a partner twice and that a "
                    + "customer is attributed to one partner at most, which only V549's constraints enforce");

    /** V366 up to (excluding) its carry-over of the legacy promo tables, which no longer exist. */
    private static final String V366_CUT = "-- Defensive carry of any existing promo rows";

    private JdbcTemplate jdbc;

    private static String migration(String name) throws Exception {
        String relative = "migration-service/src/main/resources/db/migration/" + name;
        Path here = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path candidate = here; candidate != null; candidate = candidate.getParent()) {
            Path file = candidate.resolve(relative);
            if (Files.isRegularFile(file)) return Files.readString(file);
        }
        throw new IllegalStateException("could not locate " + relative + " from " + here);
    }

    @BeforeAll
    void setUpSchema() throws Exception {
        DB.require();
        DriverManagerDataSource ds = new DriverManagerDataSource(DB.url(), DB.user(), DB.password());
        ds.setDriverClassName("org.postgresql.Driver");
        jdbc = new JdbcTemplate(ds);

        jdbc.execute("DROP TABLE IF EXISTS auth.partner_commission, auth.reward_redemption, auth.reward_code CASCADE");
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS auth");
        jdbc.execute("CREATE TABLE IF NOT EXISTS auth.subscription (id BIGSERIAL PRIMARY KEY)");
        String v366 = migration("V366__unified_reward_codes.sql");
        jdbc.execute(v366.substring(0, v366.indexOf(V366_CUT)));
        String v549 = migration("V549__partner_program.sql");
        jdbc.execute(v549);
        jdbc.execute(v549); // idempotent: a re-run must not fail
    }

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE auth.partner_commission, auth.reward_redemption, auth.reward_code RESTART IDENTITY CASCADE");
    }

    private long partnerCode(String code, long owner) {
        return jdbc.queryForObject("""
                INSERT INTO auth.reward_code (code, program, owner_user_id, benefit_kind, benefit_amount,
                    benefit_trigger, owner_reward_kind, payout_bps, payout_months, hold_days)
                VALUES (?, 'PARTNER', ?, 'CREDIT_GRANT', 10000, 'REDEEM_TIME', 'PARTNER_PAYOUT', 3000, 12, 14)
                RETURNING id""", Long.class, code, owner);
    }

    private long redemption(long codeId, long customer, long owner) {
        return jdbc.queryForObject("""
                INSERT INTO auth.reward_redemption (reward_code_id, redeemer_user_id, owner_user_id, program, status)
                VALUES (?, ?, ?, 'PARTNER', 'GRANTED') RETURNING id""", Long.class, codeId, customer, owner);
    }

    private void commission(long redemptionId, long codeId, String invoiceId) {
        jdbc.update("""
                INSERT INTO auth.partner_commission (redemption_id, reward_code_id, partner_user_id, customer_user_id,
                    provider_invoice_id, base_amount_minor, currency, payout_bps, commission_minor, invoice_paid_at, due_at)
                VALUES (?, ?, 99, 7, ?, 2400, 'usd', 3000, 720, now(), now() + interval '14 days')""",
                redemptionId, codeId, invoiceId);
    }

    @Test
    @DisplayName("one commission per Stripe invoice: a replayed invoice.paid is refused by the database")
    void oneCommissionPerInvoice() {
        long code = partnerCode("TECHDOX", 99);
        long r = redemption(code, 7, 99);
        commission(r, code, "in_1");

        assertThatThrownBy(() -> commission(r, code, "in_1")).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth.partner_commission", Long.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("a customer is attributed to one partner at most, even through two different partner codes")
    void onePartnerPerCustomer() {
        long first = partnerCode("FIRST", 98);
        long second = partnerCode("SECOND", 99);
        redemption(first, 7, 98);

        assertThatThrownBy(() -> redemption(second, 7, 99)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a plan grant is only ever a redeem-time benefit with a positive duration")
    void planGrantShape() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO auth.reward_code (code, program, benefit_kind, benefit_trigger, benefit_plan_code, benefit_plan_days)
                VALUES ('X1', 'PROMO', 'CREDIT_GRANT', 'PAID_CONVERSION', 'PRO', 90)""")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO auth.reward_code (code, program, benefit_kind, benefit_trigger, benefit_plan_code, benefit_plan_days)
                VALUES ('X2', 'PROMO', 'CREDIT_GRANT', 'REDEEM_TIME', 'PRO', 0)""")).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("""
                INSERT INTO auth.reward_code (code, program, benefit_kind, benefit_amount, benefit_trigger,
                    benefit_plan_code, benefit_plan_days, cap_scope, cap_limit)
                VALUES ('LC-OK', 'PROMO', 'CREDIT_GRANT', 50000, 'REDEEM_TIME', 'PRO', 90, 'GLOBAL', 1)""");
    }

    @Test
    @DisplayName("a revenue share needs a PARTNER code, a rate within 0..100% and a duration")
    void payoutShape() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO auth.reward_code (code, program, owner_user_id, benefit_kind, benefit_trigger, owner_reward_kind, payout_bps)
                VALUES ('P1', 'PARTNER', 9, 'CREDIT_GRANT', 'REDEEM_TIME', 'PARTNER_PAYOUT', 3000)"""))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO auth.reward_code (code, program, owner_user_id, benefit_kind, benefit_trigger, owner_reward_kind, payout_bps, payout_months)
                VALUES ('P2', 'PARTNER', 9, 'CREDIT_GRANT', 'REDEEM_TIME', 'PARTNER_PAYOUT', 10001, 12)"""))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO auth.reward_code (code, program, benefit_kind, benefit_trigger, owner_reward_kind, payout_bps, payout_months)
                VALUES ('P3', 'PROMO', 'CREDIT_GRANT', 'REDEEM_TIME', 'PARTNER_PAYOUT', 3000, 12)"""))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("comp_ends_at exists on the subscription (the scheduler's revert reads it)")
    void compEndsAtColumn() {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = 'auth' AND table_name = 'subscription' AND column_name = 'comp_ends_at'""",
                Long.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("account purge: a purged partner's codes are disabled and only their lines still in the refund window are voided")
    void purgeStatementsOnRealTables() {
        long code = partnerCode("TECHDOX", 99);
        long r = redemption(code, 7, 99);
        commission(r, code, "in_hold");
        commission(r, code, "in_paid");
        commission(r, code, "in_payable");
        jdbc.update("UPDATE auth.partner_commission SET status = 'PAID' WHERE provider_invoice_id = 'in_paid'");
        // Past its hold: payable when the account is deleted, so the terms (16.8) still pay it.
        jdbc.update("UPDATE auth.partner_commission SET due_at = now() - interval '1 day' WHERE provider_invoice_id = 'in_payable'");

        jdbc.update(com.apimarketplace.auth.service.AccountPurgeService.DEACTIVATE_OWNED_REWARD_CODES_SQL, 99L);
        jdbc.update(com.apimarketplace.auth.service.AccountPurgeService.VOID_PARTNER_HOLD_COMMISSIONS_SQL, 99L);

        assertThat(jdbc.queryForObject("SELECT active FROM auth.reward_code WHERE id = ?", Boolean.class, code)).isFalse();
        assertThat(jdbc.queryForObject("SELECT status || ':' || void_reason FROM auth.partner_commission WHERE provider_invoice_id = 'in_hold'",
                String.class)).isEqualTo("VOID:PARTNER_PURGED");
        assertThat(jdbc.queryForObject("SELECT status FROM auth.partner_commission WHERE provider_invoice_id = 'in_paid'",
                String.class)).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT status FROM auth.partner_commission WHERE provider_invoice_id = 'in_payable'",
                String.class)).isEqualTo("HOLD");
    }
}

package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.RewardCode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.dao.DataAccessException;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Postgres integration test for the unified reward-code schema (V366) and its two
 * native queries. Mock-based unit tests pin the int return value; this proves the
 * ACTUAL SQL + DDL against real PostgreSQL: the cap reserve (GLOBAL blocks,
 * NONE/PER_OWNER_SOFT never block), the FOR UPDATE SKIP LOCKED free-node claim,
 * the coherence CHECK constraints, and the partial unique indexes (one referral
 * per referee, one referral/partner code per owner). The two query strings are
 * read straight off the {@code @Query} annotations via reflection so they can
 * never drift from what the repositories actually run.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Reward repositories - Postgres reserve/claim/constraints integration")
class RewardRepositoryPostgresIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("auth_test")
            .withUsername("test")
            .withPassword("test");

    private JdbcTemplate jdbc;
    private NamedParameterJdbcTemplate namedJdbc;
    private String claimSql;
    private String reserveSql;

    private static final String BENEFIT = RewardCode.BENEFIT_WORKFLOW_NODE_FREE;

    @BeforeAll
    void setUpSchema() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        ds.setDriverClassName(POSTGRES.getDriverClassName());
        this.jdbc = new JdbcTemplate(ds);
        this.namedJdbc = new NamedParameterJdbcTemplate(ds);

        claimSql = RewardRedemptionRepository.class
                .getMethod("claimFreeWorkflowNode", Long.class, String.class)
                .getAnnotation(Query.class).value();
        reserveSql = RewardCodeRepository.class
                .getMethod("tryReserveRedemption", Long.class)
                .getAnnotation(Query.class).value();

        jdbc.execute("CREATE SCHEMA IF NOT EXISTS auth");
        // Mirror of V366 (tables + checks + indexes; defensive promo carry omitted).
        jdbc.execute("""
                CREATE TABLE auth.reward_code (
                    id BIGSERIAL PRIMARY KEY,
                    code VARCHAR(64) NOT NULL,
                    program VARCHAR(16) NOT NULL,
                    owner_user_id BIGINT,
                    benefit_kind VARCHAR(24) NOT NULL,
                    benefit_amount INT NOT NULL DEFAULT 0,
                    benefit_duration_days INT NOT NULL DEFAULT 0,
                    benefit_trigger VARCHAR(16) NOT NULL,
                    owner_reward_kind VARCHAR(16) NOT NULL DEFAULT 'NONE',
                    owner_reward_amount INT NOT NULL DEFAULT 0,
                    hold_days INT NOT NULL DEFAULT 0,
                    clawback_enabled BOOLEAN NOT NULL DEFAULT FALSE,
                    cap_scope VARCHAR(16) NOT NULL DEFAULT 'NONE',
                    cap_limit INT,
                    current_redemptions INT NOT NULL DEFAULT 0,
                    payout_kind VARCHAR(24), payout_bps INT, payout_currency VARCHAR(3),
                    payout_external_account_id VARCHAR(255),
                    active BOOLEAN NOT NULL DEFAULT TRUE,
                    valid_from TIMESTAMPTZ NOT NULL DEFAULT now(),
                    valid_until TIMESTAMPTZ,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    CONSTRAINT chk_reward_code_program CHECK (program IN ('PROMO','REFERRAL','PARTNER')),
                    CONSTRAINT chk_reward_code_benefit_kind CHECK (benefit_kind IN ('FREE_NODE_COUNTER','CREDIT_GRANT')),
                    CONSTRAINT chk_reward_code_benefit_trigger CHECK (benefit_trigger IN ('REDEEM_TIME','PAID_CONVERSION')),
                    CONSTRAINT chk_reward_code_owner_reward_kind CHECK (owner_reward_kind IN ('NONE','CREDIT_GRANT','PARTNER_PAYOUT')),
                    CONSTRAINT chk_reward_code_cap_scope CHECK (cap_scope IN ('NONE','GLOBAL','PER_OWNER_SOFT')),
                    CONSTRAINT chk_reward_code_free_node_shape
                        CHECK (benefit_kind <> 'FREE_NODE_COUNTER'
                               OR (hold_days = 0 AND owner_reward_kind = 'NONE' AND benefit_trigger = 'REDEEM_TIME')),
                    CONSTRAINT chk_reward_code_credit_grant_shape
                        CHECK (benefit_kind <> 'CREDIT_GRANT' OR benefit_duration_days = 0)
                )
                """);
        jdbc.execute("CREATE UNIQUE INDEX uq_reward_code_code ON auth.reward_code (UPPER(code))");
        jdbc.execute("CREATE UNIQUE INDEX uq_reward_code_owner_program ON auth.reward_code (owner_user_id, program) WHERE owner_user_id IS NOT NULL");
        jdbc.execute("""
                CREATE TABLE auth.reward_redemption (
                    id BIGSERIAL PRIMARY KEY,
                    reward_code_id BIGINT NOT NULL REFERENCES auth.reward_code(id) ON DELETE CASCADE,
                    redeemer_user_id BIGINT NOT NULL,
                    owner_user_id BIGINT,
                    program VARCHAR(16) NOT NULL,
                    status VARCHAR(16) NOT NULL,
                    benefit_type VARCHAR(64),
                    benefit_until TIMESTAMPTZ,
                    free_credits_used INT NOT NULL DEFAULT 0,
                    free_credits_cap INT NOT NULL DEFAULT 0,
                    provider_subscription_id VARCHAR(255),
                    qualified_at TIMESTAMPTZ, release_due_at TIMESTAMPTZ, released_at TIMESTAMPTZ,
                    clawed_back_at TIMESTAMPTZ, redeemer_reward_amount INT, owner_reward_amount INT,
                    reward_source_id VARCHAR(512), owner_reward_source_id VARCHAR(512),
                    redeemed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    active BOOLEAN NOT NULL DEFAULT TRUE,
                    CONSTRAINT chk_reward_redemption_status
                        CHECK (status IN ('GRANTED','PENDING','QUALIFIED','RELEASED','CLAWED_BACK','TRACK_ONLY','INELIGIBLE')),
                    CONSTRAINT uq_reward_redemption_user_code UNIQUE (redeemer_user_id, reward_code_id)
                )
                """);
        jdbc.execute("CREATE UNIQUE INDEX uq_reward_redemption_referral_referee ON auth.reward_redemption (redeemer_user_id) WHERE program = 'REFERRAL'");
    }

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE auth.reward_redemption RESTART IDENTITY CASCADE");
        jdbc.execute("TRUNCATE auth.reward_code RESTART IDENTITY CASCADE");
    }

    // ---- reserve (tryReserveRedemption) ----

    private long insertReferralCode(String code, Long owner, String capScope, Integer capLimit,
                                    int current, String validFromExpr, String validUntilExpr, boolean active) {
        return jdbc.queryForObject("INSERT INTO auth.reward_code "
                + "(code, program, owner_user_id, benefit_kind, benefit_amount, benefit_trigger, "
                + " owner_reward_kind, owner_reward_amount, hold_days, clawback_enabled, "
                + " cap_scope, cap_limit, current_redemptions, valid_from, valid_until, active) "
                + "VALUES (?, 'REFERRAL', ?, 'CREDIT_GRANT', 8000, 'PAID_CONVERSION', "
                + " 'CREDIT_GRANT', 8000, 14, TRUE, ?, ?, ?, " + validFromExpr + ", " + validUntilExpr + ", ?) "
                + "RETURNING id",
                Long.class, code, owner, capScope, capLimit, current, active);
    }

    private int reserve(long codeId) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", codeId);
        return namedJdbc.update(reserveSql, p);
    }

    private int currentRedemptions(long codeId) {
        return jdbc.queryForObject("SELECT current_redemptions FROM auth.reward_code WHERE id = ?", Integer.class, codeId);
    }

    @Test
    @DisplayName("reserve GLOBAL cap stops at cap_limit: max=2 -> [1,1,0], current=2")
    void reserveGlobalStopsAtCap() {
        long id = insertReferralCode("G1", 1L, "GLOBAL", 2, 0, "now() - interval '1 day'", "now() + interval '30 days'", true);
        assertThat(reserve(id)).isEqualTo(1);
        assertThat(reserve(id)).isEqualTo(1);
        assertThat(reserve(id)).isEqualTo(0); // GLOBAL cap reached
        assertThat(currentRedemptions(id)).isEqualTo(2);
    }

    @Test
    @DisplayName("reserve NONE cap never blocks (referral default)")
    void reserveNoneNeverBlocks() {
        long id = insertReferralCode("N1", 1L, "NONE", null, 0, "now() - interval '1 day'", "NULL", true);
        assertThat(reserve(id)).isEqualTo(1);
        assertThat(reserve(id)).isEqualTo(1);
        assertThat(reserve(id)).isEqualTo(1);
        assertThat(currentRedemptions(id)).isEqualTo(3);
    }

    @Test
    @DisplayName("reserve PER_OWNER_SOFT never blocks even past cap_limit (overflow is TRACK_ONLY, not a block)")
    void reserveSoftNeverBlocks() {
        long id = insertReferralCode("S1", 1L, "PER_OWNER_SOFT", 2, 0, "now() - interval '1 day'", "NULL", true);
        assertThat(reserve(id)).isEqualTo(1);
        assertThat(reserve(id)).isEqualTo(1);
        assertThat(reserve(id)).isEqualTo(1); // past soft cap, still reserved
        assertThat(currentRedemptions(id)).isEqualTo(3);
    }

    @Test
    @DisplayName("reserve rejects an inactive code")
    void reserveRejectsInactive() {
        long id = insertReferralCode("I1", 1L, "NONE", null, 0, "now() - interval '1 day'", "NULL", false);
        assertThat(reserve(id)).isEqualTo(0);
    }

    @Test
    @DisplayName("reserve rejects an out-of-window code (valid_until in the past)")
    void reserveRejectsOutOfWindow() {
        long id = insertReferralCode("O1", 1L, "NONE", null, 0, "now() - interval '30 days'", "now() - interval '1 day'", true);
        assertThat(reserve(id)).isEqualTo(0);
    }

    @Test
    @DisplayName("reserve accepts a code with no expiry (valid_until NULL)")
    void reserveNullValidUntilWorks() {
        long id = insertReferralCode("E1", 1L, "NONE", null, 0, "now() - interval '1 day'", "NULL", true);
        assertThat(reserve(id)).isEqualTo(1);
    }

    // ---- claim (claimFreeWorkflowNode) ----

    private long insertPromoCode() {
        return jdbc.queryForObject("INSERT INTO auth.reward_code "
                + "(code, program, benefit_kind, benefit_amount, benefit_duration_days, benefit_trigger, "
                + " cap_scope, valid_until, active) "
                + "VALUES ('PROMO1', 'PROMO', 'FREE_NODE_COUNTER', 20000, 30, 'REDEEM_TIME', 'GLOBAL', now() + interval '30 days', TRUE) "
                + "RETURNING id", Long.class);
    }

    private void insertRedemption(long codeId, long redeemer, String program, String status,
                                  String benefitType, String benefitUntilExpr, int used, int cap, boolean active) {
        jdbc.update("INSERT INTO auth.reward_redemption "
                + "(reward_code_id, redeemer_user_id, program, status, benefit_type, benefit_until, "
                + " free_credits_used, free_credits_cap, active) "
                + "VALUES (?, ?, ?, ?, ?, " + benefitUntilExpr + ", ?, ?, ?)",
                codeId, redeemer, program, status, benefitType, used, cap, active);
    }

    private int claim(long userId) {
        Map<String, Object> p = new HashMap<>();
        p.put("userId", userId);
        p.put("benefitType", BENEFIT);
        return namedJdbc.update(claimSql, p);
    }

    private int usedFor(long userId) {
        return jdbc.queryForObject("SELECT free_credits_used FROM auth.reward_redemption WHERE redeemer_user_id = ?", Integer.class, userId);
    }

    @Test
    @DisplayName("claim stops exactly at the free-node cap: cap=3 -> [1,1,1,0], used=3")
    void claimStopsAtCap() {
        long code = insertPromoCode();
        insertRedemption(code, 7L, "PROMO", "GRANTED", BENEFIT, "now() + interval '10 days'", 0, 3, true);
        assertThat(claim(7L)).isEqualTo(1);
        assertThat(claim(7L)).isEqualTo(1);
        assertThat(claim(7L)).isEqualTo(1);
        assertThat(claim(7L)).isEqualTo(0);
        assertThat(usedFor(7L)).isEqualTo(3);
    }

    @Test
    @DisplayName("claim rejects an expired benefit, an inactive row, and a non-PROMO program")
    void claimRejectsIneligible() {
        long code = insertPromoCode();
        insertRedemption(code, 1L, "PROMO", "GRANTED", BENEFIT, "now() - interval '1 day'", 0, 100, true); // expired
        insertRedemption(code, 2L, "PROMO", "GRANTED", BENEFIT, "now() + interval '10 days'", 0, 100, false); // inactive
        insertRedemption(code, 3L, "REFERRAL", "GRANTED", BENEFIT, "now() + interval '10 days'", 0, 100, true); // wrong program
        assertThat(claim(1L)).isEqualTo(0);
        assertThat(claim(2L)).isEqualTo(0);
        assertThat(claim(3L)).isEqualTo(0);
    }

    // ---- constraints ----

    @Test
    @DisplayName("CHECK rejects a FREE_NODE_COUNTER configured with a conversion trigger (incoherent config unrepresentable)")
    void checkRejectsIncoherentFreeNode() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO auth.reward_code "
                + "(code, program, benefit_kind, benefit_trigger, valid_until) "
                + "VALUES ('BAD1', 'PROMO', 'FREE_NODE_COUNTER', 'PAID_CONVERSION', now() + interval '1 day')"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("partial unique allows ONE referral redemption per referee across codes")
    void onePerRefereeAcrossCodes() {
        long a = insertReferralCode("R1", 1L, "NONE", null, 0, "now() - interval '1 day'", "NULL", true);
        long b = insertReferralCode("R2", 2L, "NONE", null, 0, "now() - interval '1 day'", "NULL", true);
        insertRedemption(a, 9L, "REFERRAL", "PENDING", null, "NULL", 0, 0, true);
        assertThatThrownBy(() -> insertRedemption(b, 9L, "REFERRAL", "PENDING", null, "NULL", 0, 0, true))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("unique (owner, program) allows ONE referral code per owner")
    void oneReferralCodePerOwner() {
        insertReferralCode("OWN1", 5L, "NONE", null, 0, "now() - interval '1 day'", "NULL", true);
        assertThatThrownBy(() -> insertReferralCode("OWN2", 5L, "NONE", null, 0, "now() - interval '1 day'", "NULL", true))
                .isInstanceOf(DataAccessException.class);
    }
}

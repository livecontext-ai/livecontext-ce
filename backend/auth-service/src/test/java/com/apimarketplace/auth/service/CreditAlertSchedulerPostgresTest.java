package com.apimarketplace.auth.service;

import com.apimarketplace.notification.client.NotificationClient;
import com.apimarketplace.notification.client.dto.NotificationEmitRequest;
import com.apimarketplace.testsupport.ScratchPostgres;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The credit scan on a real Postgres: once per level per cycle, only for accounts that spent
 * recently, never for an unlimited install. The alert ledger's DDL is read from V528 itself.
 */
@DisplayName("CreditAlertScheduler (real Postgres)")
class CreditAlertSchedulerPostgresTest {

    private static final ScratchPostgres DB = ScratchPostgres.forPrefix(
            "CREDENTIAL_TEST_PG",
            "it is the only proof that a Free account running out of credits is told ONCE per cycle, "
                    + "and that dormant accounts are not mass-mailed on the first scan after a deploy");

    static JdbcTemplate jdbc;
    private NotificationClient client;
    private CreditAlertScheduler scheduler;
    private final UUID personalOrg = UUID.randomUUID();

    @BeforeAll
    static void schema() throws Exception {
        DB.require();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(DB.url(), DB.user(), DB.password()));
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS auth");
        jdbc.execute("DROP TABLE IF EXISTS auth.credit_alert_sent, auth.credit_ledger, auth.subscription, "
                + "auth.billing_customer, auth.plan, auth.organization CASCADE");
        jdbc.execute("CREATE TABLE auth.organization (id UUID PRIMARY KEY, owner_id BIGINT NOT NULL, "
                + "is_personal BOOLEAN NOT NULL, deleted_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT now())");
        // Only the columns the scan reads; the real tables carry many more.
        jdbc.execute("CREATE TABLE auth.plan (id BIGSERIAL PRIMARY KEY, code VARCHAR(32), included_tool_credits BIGINT, "
                + "included_llm_tokens BIGINT)");
        jdbc.execute("CREATE TABLE auth.billing_customer (id BIGSERIAL PRIMARY KEY, user_id BIGINT NOT NULL)");
        jdbc.execute("CREATE TABLE auth.subscription (id BIGSERIAL PRIMARY KEY, billing_customer_id BIGINT NOT NULL, "
                + "plan_id BIGINT NOT NULL, status VARCHAR(32) NOT NULL, current_period_start TIMESTAMP NOT NULL, "
                + "credit_cycle_index INTEGER NOT NULL DEFAULT 0, remaining_credits NUMERIC(15,4) NOT NULL, "
                + "payg_remaining_credits NUMERIC(15,4) NOT NULL DEFAULT 0, credit_quantity INTEGER DEFAULT 0, "
                + "provider VARCHAR(32) NOT NULL DEFAULT 'stripe')");
        jdbc.execute("CREATE TABLE auth.credit_ledger (id BIGSERIAL PRIMARY KEY, user_id BIGINT NOT NULL, "
                + "amount NUMERIC(15,4) NOT NULL, source_type VARCHAR(64) NOT NULL DEFAULT 'WORKFLOW_NODE', "
                + "created_at TIMESTAMP NOT NULL DEFAULT now())");
        String v525 = Files.readString(Path.of("..", "migration-service", "src", "main", "resources", "db",
                "migration", "V528__notification_delivery.sql"), StandardCharsets.UTF_8)
                .replaceAll("(?m)^\\s*--.*$", "");
        String ddl = null;
        for (String s : v525.split(";")) {
            if (s.contains("CREATE TABLE IF NOT EXISTS auth.credit_alert_sent")) ddl = s.trim();
        }
        assertThat(ddl).as("V528 declares auth.credit_alert_sent").isNotNull();
        jdbc.execute(ddl);
    }

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE auth.credit_alert_sent, auth.credit_ledger, auth.subscription, auth.billing_customer, "
                + "auth.plan, auth.organization RESTART IDENTITY CASCADE");
        client = mock(NotificationClient.class);
        when(client.emit(any())).thenReturn(true);
        scheduler = scheduler(false);
    }

    private CreditAlertScheduler scheduler(boolean unlimited) {
        CreditAlertScheduler s = new CreditAlertScheduler(jdbc, unlimited, true, new BigDecimal("0.2"));
        ReflectionTestUtils.setField(s, "notificationClient", client);
        return s;
    }

    /** A FREE subscription with a 1,000-credit monthly grant, for a user WITH a personal workspace. */
    private long seed(long userId, String balance, boolean spentRecently) {
        jdbc.update("INSERT INTO auth.organization (id, owner_id, is_personal) VALUES (?, ?, true)",
                userId == 1L ? personalOrg : UUID.randomUUID(), userId);
        return seedWithoutWorkspace(userId, balance, spentRecently);
    }

    private long seedWithoutWorkspace(long userId, String balance, boolean spentRecently) {
        return seedPlan(userId, "FREE", 0, balance, spentRecently);
    }

    private long seedPlan(long userId, String planCode, int creditQuantity, String balance, boolean spentRecently) {
        // FREE grants included_llm_tokens (1000); the tool column is set to something else on
        // purpose, as V56 left it in prod, so reading the wrong column fails the tests.
        Long plan = jdbc.queryForObject("INSERT INTO auth.plan (code, included_tool_credits, included_llm_tokens) "
                        + "VALUES (?, ?, ?) RETURNING id", Long.class, planCode,
                "FREE".equals(planCode) ? Long.valueOf(5000L)
                        : ("ENTERPRISE_S".equals(planCode) ? Long.valueOf(60000L) : null),
                "FREE".equals(planCode) ? Long.valueOf(1000L) : null);
        Long bc = jdbc.queryForObject("INSERT INTO auth.billing_customer (user_id) VALUES (?) RETURNING id",
                Long.class, userId);
        Long sub = jdbc.queryForObject("INSERT INTO auth.subscription (billing_customer_id, plan_id, status, "
                        + "current_period_start, remaining_credits, credit_quantity, provider) "
                        + "VALUES (?, ?, 'active', '2026-09-01', ?, ?, ?) RETURNING id",
                Long.class, bc, plan, new BigDecimal(balance), creditQuantity,
                "FREE".equals(planCode) ? "internal" : "stripe");
        // A DEBIT: what "spent recently" means.
        jdbc.update("INSERT INTO auth.credit_ledger (user_id, amount, created_at) VALUES (?, -5, now() - interval '"
                + (spentRecently ? "1 day" : "30 days") + "')", userId);
        return sub;
    }

    private List<NotificationEmitRequest> emitted() {
        ArgumentCaptor<NotificationEmitRequest> captor = ArgumentCaptor.forClass(NotificationEmitRequest.class);
        verify(client, org.mockito.Mockito.atLeast(0)).emit(captor.capture());
        return captor.getAllValues();
    }

    @Test
    @DisplayName("Below 20% of the grant: ONE low alert, and a second scan sends nothing")
    void lowOncePerCycle() {
        seed(1L, "150", true);

        scheduler.scan();
        scheduler.scan();

        assertThat(emitted()).singleElement().satisfies(r -> {
            assertThat(r.getCategory()).isEqualTo("CREDIT_LOW");
            assertThat(r.getSubjectType()).isEqualTo("BILLING");
            assertThat(r.getOrganizationId()).isEqualTo(personalOrg.toString());
            assertThat(r.getPayload()).containsEntry("remainingCredits", "150");
        });
    }

    @Test
    @DisplayName("After LOW, running out still sends EXHAUSTED once; then nothing more this cycle")
    void exhaustedAfterLow() {
        long sub = seed(1L, "150", true);
        scheduler.scan();
        jdbc.update("UPDATE auth.subscription SET remaining_credits = -3 WHERE id = ?", sub);

        scheduler.scan();
        scheduler.scan();

        assertThat(emitted()).extracting(NotificationEmitRequest::getCategory)
                .containsExactly("CREDIT_LOW", "CREDIT_EXHAUSTED");
    }

    @Test
    @DisplayName("Regression (EXHAUSTED never sent): a balance stuck under one credit, where every debit is refused, is announced as exhausted")
    void fractionBelowOneCreditSendsExhausted() {
        long sub = seed(1L, "150", true);
        scheduler.scan();
        // A refused debit never lowers the balance, so it stays at the fraction left over.
        jdbc.update("UPDATE auth.subscription SET remaining_credits = -100.8285, payg_remaining_credits = 101.2683 "
                + "WHERE id = ?", sub);

        scheduler.scan();

        assertThat(emitted()).extracting(NotificationEmitRequest::getCategory)
                .containsExactly("CREDIT_LOW", "CREDIT_EXHAUSTED");
        assertThat(emitted().get(1).getPayload()).containsEntry("remainingCredits", "0");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth.credit_alert_sent "
                + "WHERE subscription_id = ? AND level = 'EXHAUSTED'", Integer.class, sub)).isEqualTo(1);

        // The balance stays at the fraction as long as every debit is refused: the next scans
        // (every 15 minutes) must not announce it again.
        scheduler.scan();
        assertThat(emitted()).hasSize(2);
    }

    @Test
    @DisplayName("A new credit cycle re-arms the alerts")
    void newCycleRearms() {
        long sub = seed(1L, "100", true);
        scheduler.scan();
        jdbc.update("UPDATE auth.subscription SET credit_cycle_index = 1 WHERE id = ?", sub);

        scheduler.scan();

        verify(client, times(2)).emit(any());
    }

    @Test
    @DisplayName("Regression (mass mail on deploy): an account that spent nothing for a week is not alerted")
    void dormantSkipped() {
        seed(1L, "0", false);

        scheduler.scan();

        verify(client, never()).emit(any());
    }

    @Test
    @DisplayName("Regression: a recent GRANT or top-up is not 'spending', so a dormant account stays quiet")
    void grantIsNotSpending() {
        seed(1L, "0", false);
        jdbc.update("INSERT INTO auth.credit_ledger (user_id, amount) VALUES (1, 1000)");

        scheduler.scan();

        verify(client, never()).emit(any());
    }

    @Test
    @DisplayName("Regression (false LOW on Enterprise): a full 5k pack refill is not 'low' because the plan lists 60k")
    void enterpriseFullPackIsNotLow() {
        seedPlan(1L, "ENTERPRISE_S", 0, "5000", true);
        jdbc.update("INSERT INTO auth.organization (id, owner_id, is_personal) VALUES (?, 1, true)", personalOrg);

        scheduler.scan();

        verify(client, never()).emit(any());
    }

    @Test
    @DisplayName("Regression (wrong column): FREE's threshold is 20% of included_llm_tokens, what the grant uses")
    void freeUsesIncludedLlmTokens() {
        seed(1L, "300", true);     // 300 > 20% of 1000, but <= 20% of the 5000 tool column

        scheduler.scan();

        verify(client, never()).emit(any());
    }

    @Test
    @DisplayName("Regression (dormant alert): a renewal reset or a clawback is bookkeeping, not spending")
    void resetIsNotSpending() {
        seed(1L, "0", false);
        jdbc.update("INSERT INTO auth.credit_ledger (user_id, amount, source_type) VALUES (1, -1000, 'PLAN_RESET')");
        jdbc.update("INSERT INTO auth.credit_ledger (user_id, amount, source_type) VALUES (1, -5, 'REWARD_CLAWBACK')");

        scheduler.scan();

        verify(client, never()).emit(any());
    }

    @Test
    @DisplayName("Regression (LOW never fired on paid plans): the threshold is a share of the plan's credit PACK")
    void paidPlanLowUsesPack() {
        long grant = CreditAttributionService.cycleGrantCredits("stripe", "STARTER", 0, null);
        assertThat(grant).as("the base pack of a paid plan").isPositive();
        seedPlan(1L, "STARTER", 0, String.valueOf(grant / 10), true);
        jdbc.update("INSERT INTO auth.organization (id, owner_id, is_personal) VALUES (?, 1, true)", personalOrg);

        scheduler.scan();

        assertThat(emitted()).singleElement().satisfies(r -> assertThat(r.getCategory()).isEqualTo("CREDIT_LOW"));
    }

    @Test
    @DisplayName("The grant mirrors the attribution: internal FREE its included credits, a paid or comp plan its pack")
    void grantMirrorsAttribution() {
        assertThat(CreditAttributionService.cycleGrantCredits("internal", "FREE", 0, 1000L)).isEqualTo(1000L);
        assertThat(CreditAttributionService.cycleGrantCredits("internal", "PAYG", 0, null)).isZero();
        assertThat(CreditAttributionService.cycleGrantCredits("internal", "PRO", 0, null))
                .as("a comp Pro gets the base pack").isEqualTo(CreditAttributionService.cycleGrantCredits("stripe", "PRO", 0, null));
    }

    @Test
    @DisplayName("Regression (starved scan): 600 recent spenders above the threshold do not hide the one below it")
    void scanPagesThroughEveryone() {
        Long plan = jdbc.queryForObject("INSERT INTO auth.plan (code, included_tool_credits, included_llm_tokens) "
                + "VALUES ('FREE', 1000, 1000) RETURNING id", Long.class);
        jdbc.update("INSERT INTO auth.billing_customer (user_id) SELECT g FROM generate_series(1000, 1599) g");
        jdbc.update("INSERT INTO auth.organization (id, owner_id, is_personal) "
                + "SELECT gen_random_uuid(), g, true FROM generate_series(1000, 1599) g");
        jdbc.update("INSERT INTO auth.subscription (billing_customer_id, plan_id, status, current_period_start, "
                + "remaining_credits, provider) SELECT id, ?, 'active', '2026-09-01', 900, 'internal' FROM auth.billing_customer", plan);
        jdbc.update("INSERT INTO auth.credit_ledger (user_id, amount) SELECT g, -1 FROM generate_series(1000, 1599) g");
        seed(1L, "0", true);                          // highest subscription id, last page

        scheduler.scan();

        assertThat(emitted()).singleElement()
                .satisfies(r -> assertThat(r.getCategory()).isEqualTo("CREDIT_EXHAUSTED"));
    }

    @Test
    @DisplayName("Above the threshold: no alert")
    void aboveThreshold() {
        seed(1L, "201", true);

        scheduler.scan();

        verify(client, never()).emit(any());
    }

    @Test
    @DisplayName("A failed emit is not recorded as sent, so the next scan retries it")
    void failedEmitRetried() {
        seed(1L, "0", true);
        when(client.emit(any())).thenReturn(false).thenReturn(true);

        scheduler.scan();
        scheduler.scan();

        verify(client, times(2)).emit(any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth.credit_alert_sent", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("Regression (starved scan): an account with no personal workspace is not a candidate, so it cannot block the page")
    void noWorkspaceIsNotACandidate() {
        seedWithoutWorkspace(7L, "0", true);
        seed(1L, "0", true);

        assertThat(scheduler.candidates()).extracting(CreditAlertScheduler.Candidate::userId).containsExactly(1L);
        scheduler.scan();

        assertThat(emitted()).singleElement()
                .satisfies(r -> assertThat(r.getOrganizationId()).isEqualTo(personalOrg.toString()));
    }

    @Test
    @DisplayName("A deleted personal workspace does not count")
    void deletedWorkspaceIgnored() {
        seed(1L, "0", true);
        jdbc.update("UPDATE auth.organization SET deleted_at = now()");

        assertThat(scheduler.candidates()).isEmpty();
    }

    @Test
    @DisplayName("Unlimited credits (self-hosted default): the scan does nothing")
    void unlimitedDoesNothing() {
        seed(1L, "0", true);

        scheduler(true).scan();

        verify(client, never()).emit(any());
    }
}

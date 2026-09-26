package com.apimarketplace.auth.migration;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-Postgres regression for {@code V512__free_plan_single_credit_pool.sql}.
 *
 * <p>V512 retires the Free plan's separate AI allowance now that its monthly credits pay
 * for free-tier chat/agent turns directly. What only a real database can show: that it
 * reaches exactly the Free plan (a pot an admin granted to another plan is left alone),
 * that it never touches the monthly credits themselves, and that it runs under the
 * {@code orchestrator} search_path the deploy uses (an unqualified name fails here).
 * Applied after V493/V494 in the same order as the deploy.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("V512 single Free credit pool - real Postgres, real migration search_path")
class FreePlanSinglePoolV512MigrationPostgresTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static JdbcTemplate jdbc;
    static String beforeEach;
    static String v512;

    @BeforeAll
    static void setUpClass() {
        String v493 = loadMigration("V493__free_tier_models.sql");
        String v494 = loadMigration("V494__free_ai_credit_allowance.sql");
        v512 = loadMigration("V512__free_plan_single_credit_pool.sql");
        beforeEach = loadMigration("beforeEachMigrate.sql");
        Assumptions.assumeTrue(v493 != null && v494 != null && v512 != null && beforeEach != null,
                "migration files not found from module cwd - skipped");

        DataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(ds);

        jdbc.execute("CREATE SCHEMA orchestrator");
        jdbc.execute("CREATE SCHEMA agent");
        jdbc.execute("CREATE SCHEMA auth");
        createPreMigrationSchema();
        seed();

        jdbc.execute(beforeEach + "\n" + v493);
        jdbc.execute(beforeEach + "\n" + v494);
        // State V494 leaves behind in production: the verified Free account holds its
        // 100-credit pot, and an admin has granted a pot to the paid plan too.
        jdbc.update("UPDATE auth.plan SET included_ai_credits = 50 WHERE code = 'PRO'");
        jdbc.update("UPDATE auth.subscription SET ai_remaining_credits = 37 WHERE id = 3");
        jdbc.update("UPDATE auth.subscription SET ai_remaining_credits = 12.5 WHERE id = 2");
        // A turn the pot paid for before the merge: history the reconciliation adds back.
        jdbc.update("INSERT INTO auth.credit_ledger (user_id, amount, source_type, ai_portion) "
                + "VALUES (1, -2.5, 'AGENT_EXECUTION', 2.5)");
        jdbc.execute(beforeEach + "\n" + v512);
    }

    private static void createPreMigrationSchema() {
        jdbc.execute("""
                CREATE TABLE agent.model_config_overrides (
                    id        BIGSERIAL PRIMARY KEY,
                    provider  VARCHAR(50)  NOT NULL,
                    model_id  VARCHAR(150) NOT NULL
                )""");
        jdbc.execute("""
                CREATE TABLE auth.model_pricing (
                    id        BIGSERIAL PRIMARY KEY,
                    provider  VARCHAR(50)  NOT NULL,
                    model     VARCHAR(150) NOT NULL
                )""");
        jdbc.execute("""
                CREATE TABLE auth.users (
                    id             BIGSERIAL PRIMARY KEY,
                    email          VARCHAR(255) NOT NULL,
                    email_verified BOOLEAN NOT NULL DEFAULT FALSE
                )""");
        jdbc.execute("""
                CREATE TABLE auth.plan (
                    id   BIGSERIAL PRIMARY KEY,
                    code VARCHAR(50) NOT NULL UNIQUE
                )""");
        jdbc.execute("""
                CREATE TABLE auth.billing_customer (
                    id      BIGSERIAL PRIMARY KEY,
                    user_id BIGINT NOT NULL REFERENCES auth.users (id)
                )""");
        jdbc.execute("""
                CREATE TABLE auth.subscription (
                    id                  BIGSERIAL PRIMARY KEY,
                    billing_customer_id BIGINT NOT NULL REFERENCES auth.billing_customer (id),
                    plan_id             BIGINT NOT NULL REFERENCES auth.plan (id),
                    status              VARCHAR(30) NOT NULL DEFAULT 'active',
                    remaining_credits   NUMERIC(15, 4) NOT NULL DEFAULT 0
                )""");
        jdbc.execute("""
                CREATE TABLE auth.credit_ledger (
                    id          BIGSERIAL PRIMARY KEY,
                    user_id     BIGINT NOT NULL,
                    amount      NUMERIC(15, 4) NOT NULL,
                    source_type VARCHAR(50) NOT NULL
                )""");
    }

    private static void seed() {
        jdbc.update("INSERT INTO auth.plan (id, code) VALUES (1, 'FREE'), (2, 'PRO')");
        jdbc.update("INSERT INTO auth.users (id, email, email_verified) VALUES "
                + "(1, 'free@example.com', TRUE), (2, 'free-canceled@example.com', TRUE), (3, 'paid@example.com', TRUE)");
        jdbc.update("INSERT INTO auth.billing_customer (id, user_id) VALUES (1, 1), (2, 2), (3, 3)");
        jdbc.update("INSERT INTO auth.subscription (id, billing_customer_id, plan_id, status, remaining_credits) VALUES "
                + "(1, 1, 1, 'active', 640),"
                // a canceled Free row with a pot left: nothing reads it, but it must not
                // keep advertising a balance either.
                + "(2, 2, 1, 'canceled', 0),"
                + "(3, 3, 2, 'active', 90000)");
    }

    @Test
    @DisplayName("the Free plan no longer grants an AI allowance")
    void freePlanAllowanceIsRetired() {
        assertThat(jdbc.queryForObject(
                "SELECT included_ai_credits FROM auth.plan WHERE code = 'FREE'", Integer.class))
                .as("renewal must refill nothing, and aiAllowanceEligible refuses on the plan alone")
                .isNull();
    }

    @Test
    @DisplayName("every Free subscription's pot is emptied, whatever its status")
    void freePotsAreEmptied() {
        assertThat(potOf(1)).isEqualByComparingTo("0");
        assertThat(potOf(2)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("the monthly credits themselves are untouched - they ARE the pool now")
    void monthlyCreditsAreUntouched() {
        assertThat(jdbc.queryForObject(
                "SELECT remaining_credits FROM auth.subscription WHERE id = 1", BigDecimal.class))
                .isEqualByComparingTo("640");
    }

    @Test
    @DisplayName("the ledger is untouched, so the reconciliation's ai_portion add-back sees no drift")
    void ledgerIsUntouched() {
        // Reconciliation compares the wallet (sub + payg) with the ledger plus ai_portion.
        // Zeroing the pot moves neither side of that comparison; rewriting a ledger row or
        // an ai_portion would.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth.credit_ledger", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT amount FROM auth.credit_ledger WHERE user_id = 1", BigDecimal.class))
                .isEqualByComparingTo("-2.5");
        assertThat(jdbc.queryForObject(
                "SELECT ai_portion FROM auth.credit_ledger WHERE user_id = 1", BigDecimal.class))
                .isEqualByComparingTo("2.5");
    }

    @Test
    @DisplayName("a pot an admin granted to another plan is left alone")
    void otherPlansAreLeftAlone() {
        assertThat(jdbc.queryForObject(
                "SELECT included_ai_credits FROM auth.plan WHERE code = 'PRO'", Integer.class))
                .isEqualTo(50);
        assertThat(potOf(3)).isEqualByComparingTo("37");
    }

    @Test
    @DisplayName("re-running V512 is a no-op")
    void rerunIsIdempotent() {
        jdbc.execute(beforeEach + "\n" + v512);

        assertThat(potOf(1)).isEqualByComparingTo("0");
        assertThat(potOf(3)).isEqualByComparingTo("37");
        assertThat(jdbc.queryForObject(
                "SELECT included_ai_credits FROM auth.plan WHERE code = 'FREE'", Integer.class))
                .isNull();
    }

    private static BigDecimal potOf(long subscriptionId) {
        return jdbc.queryForObject(
                "SELECT ai_remaining_credits FROM auth.subscription WHERE id = ?",
                BigDecimal.class, subscriptionId);
    }

    private static String loadMigration(String name) {
        for (String prefix : new String[]{"", "../", "../../"}) {
            Path p = Path.of(prefix + "backend/migration-service/src/main/resources/db/migration/" + name);
            Path alt = Path.of(prefix + "migration-service/src/main/resources/db/migration/" + name);
            for (Path candidate : new Path[]{p, alt}) {
                if (Files.exists(candidate)) {
                    try {
                        return Files.readString(candidate);
                    } catch (Exception e) {
                        return null;
                    }
                }
            }
        }
        return null;
    }
}

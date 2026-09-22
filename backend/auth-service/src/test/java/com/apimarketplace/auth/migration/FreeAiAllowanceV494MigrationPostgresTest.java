package com.apimarketplace.auth.migration;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real-Postgres regression for {@code V493__free_tier_models.sql} and
 * {@code V494__free_ai_credit_allowance.sql}.
 *
 * <p>Three things in these files cannot be asserted anywhere else, and each one is
 * invisible until it has already gone wrong in production:
 *
 * <ul>
 *   <li><b>The backfill's reach.</b> It hands existing accounts their first allowance
 *       without waiting a month, and it must reach exactly the accounts the CODE would
 *       grant it to: email-verified ones. A backfill that ignored verification would
 *       retroactively do the giveaway the code gate exists to prevent, on every
 *       unverified row already sitting in the table.</li>
 *   <li><b>The CHECK constraint.</b> The pot is an entitlement, not a wallet, so it has
 *       no legitimate negative state. The floor is enforced in Java; this is the
 *       backstop for the bug that gets past it, and a constraint nobody ever tried to
 *       violate is a constraint nobody knows works.</li>
 *   <li><b>Re-runnability.</b> Flyway will not re-run these, but a restored snapshot, a
 *       repair, or a replay onto a partially-migrated database will, and the {@code = 0}
 *       guard is the only thing stopping a re-run from topping up a pot the account has
 *       already spent.</li>
 * </ul>
 *
 * <p>Both files run against the same container in order, through the same
 * {@code beforeEachMigrate} callback the deploy uses, whose {@code search_path} points
 * at {@code orchestrator} - so an unqualified reference fails here rather than in prod.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("V493/V494 free-tier allowance - real Postgres, real migration search_path")
class FreeAiAllowanceV494MigrationPostgresTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static JdbcTemplate jdbc;
    static String v494;

    @BeforeAll
    static void setUpClass() {
        String v493 = loadMigration("V493__free_tier_models.sql");
        v494 = loadMigration("V494__free_ai_credit_allowance.sql");
        String beforeEach = loadMigration("beforeEachMigrate.sql");
        Assumptions.assumeTrue(v493 != null && v494 != null && beforeEach != null,
                "migration files not found from module cwd - skipped");

        DataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(ds);

        jdbc.execute("CREATE SCHEMA orchestrator");
        jdbc.execute("CREATE SCHEMA agent");
        jdbc.execute("CREATE SCHEMA auth");
        createPreMigrationSchema();
        seed();

        // Exactly as the deploy applies them: callback, V493, callback, V494.
        jdbc.execute(beforeEach + "\n" + v493);
        jdbc.execute(beforeEach + "\n" + v494);
    }

    /** The columns these migrations depend on, as they stand BEFORE them. */
    private static void createPreMigrationSchema() {
        jdbc.execute("""
                CREATE TABLE agent.model_config_overrides (
                    id        BIGSERIAL PRIMARY KEY,
                    provider  VARCHAR(50)  NOT NULL,
                    model_id  VARCHAR(150) NOT NULL,
                    UNIQUE (provider, model_id)
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
                    id                   BIGSERIAL PRIMARY KEY,
                    code                 VARCHAR(50) NOT NULL UNIQUE,
                    included_llm_tokens  BIGINT
                )""");
        jdbc.execute("""
                CREATE TABLE auth.billing_customer (
                    id      BIGSERIAL PRIMARY KEY,
                    user_id BIGINT NOT NULL REFERENCES auth.users (id)
                )""");
        jdbc.execute("""
                CREATE TABLE auth.subscription (
                    id                     BIGSERIAL PRIMARY KEY,
                    billing_customer_id    BIGINT NOT NULL REFERENCES auth.billing_customer (id),
                    plan_id                BIGINT NOT NULL REFERENCES auth.plan (id),
                    status                 VARCHAR(30) NOT NULL DEFAULT 'active',
                    remaining_credits      NUMERIC(15, 4) NOT NULL DEFAULT 0
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
        jdbc.update("INSERT INTO auth.plan (id, code, included_llm_tokens) VALUES "
                + "(1, 'FREE', 1000), (2, 'PRO', 100000)");
        jdbc.update("INSERT INTO auth.users (id, email, email_verified) VALUES "
                + "(1, 'verified@example.com', TRUE),"
                + "(2, 'unverified@example.com', FALSE),"
                + "(3, 'paid@example.com', TRUE)");
        jdbc.update("INSERT INTO auth.billing_customer (id, user_id) VALUES (1, 1), (2, 2), (3, 3)");
        jdbc.update("INSERT INTO auth.subscription (id, billing_customer_id, plan_id) VALUES "
                // verified FREE: gets the allowance.
                + "(1, 1, 1),"
                // unverified FREE: must not.
                + "(2, 2, 1),"
                // verified PRO: the plan has no allowance at all.
                + "(3, 3, 2),"
                // verified FREE but CANCELED: never resolved as active, so crediting it
                // would be writing to a row nothing reads.
                + "(4, 1, 1)");
        jdbc.update("UPDATE auth.subscription SET status = 'canceled' WHERE id = 4");
        jdbc.update("INSERT INTO auth.credit_ledger (user_id, amount, source_type) "
                + "VALUES (1, -2.5, 'AGENT_EXECUTION')");
    }

    /**
     * The container is shared by the whole class (starting one per test would cost more
     * than the suite), so the rows are put back to their post-migration values before
     * each test. Without it the cases that deliberately spend or zero the pot leave the
     * next one reading whatever ran before it, and the suite passes or fails by
     * execution order - which is the kind of test that goes green for the wrong reason.
     */
    @BeforeEach
    void restorePostMigrationState() {
        jdbc.update("UPDATE auth.subscription SET ai_remaining_credits = 100 WHERE id = 1");
        jdbc.update("UPDATE auth.subscription SET ai_remaining_credits = 0 WHERE id IN (2, 3, 4)");
    }

    @Test
    @DisplayName("V493 adds both halves of the mirror, defaulting to closed")
    void freeTierColumnsDefaultToFalse() {
        jdbc.update("INSERT INTO agent.model_config_overrides (provider, model_id) VALUES ('anthropic', 'haiku')");
        jdbc.update("INSERT INTO auth.model_pricing (provider, model) VALUES ('anthropic', 'haiku')");

        assertThat(jdbc.queryForObject(
                "SELECT free_tier_enabled FROM agent.model_config_overrides WHERE model_id = 'haiku'", Boolean.class))
                .as("a model nobody opened must not be open")
                .isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT free_tier FROM auth.model_pricing WHERE model = 'haiku'", Boolean.class))
                .as("and the billing mirror fails closed the same way")
                .isFalse();
    }

    @Test
    @DisplayName("the Free plan is configured with 100 credits and the paid plan with none")
    void planAllowanceIsSeeded() {
        assertThat(jdbc.queryForObject(
                "SELECT included_ai_credits FROM auth.plan WHERE code = 'FREE'", Integer.class))
                .isEqualTo(100);
        assertThat(jdbc.queryForObject(
                "SELECT included_ai_credits FROM auth.plan WHERE code = 'PRO'", Integer.class))
                .as("a paid plan funds agents from the wallet it already pays for")
                .isNull();
    }

    @Test
    @DisplayName("the backfill grants a verified Free account its first allowance immediately")
    void backfillGrantsVerifiedFreeAccounts() {
        assertThat(allowanceOf(1)).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("the backfill SKIPS an unverified account, matching the code gate")
    void backfillSkipsUnverifiedAccounts() {
        // The pot buys real platform-key inference. The runtime grant is gated on a
        // verified email; a backfill that ignored that would hand the same credits to
        // every unverified row already in the table, which is the giveaway the gate
        // exists to prevent, applied retroactively and all at once.
        assertThat(allowanceOf(2))
                .as("it arrives when they verify, not before")
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("the backfill skips a canceled row - it is not a live wallet")
    void backfillSkipsCanceledSubscriptions() {
        assertThat(allowanceOf(4)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("the backfill leaves a paid subscription alone")
    void backfillSkipsPaidPlans() {
        assertThat(allowanceOf(3)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("the pot cannot go negative - it is an entitlement, not a wallet that carries debt")
    void checkConstraintRejectsANegativePot() {
        // The floor is enforced in CreditService.applyDebit. This is the backstop for
        // the bug that gets past it: without the constraint, a negative pot would hand
        // out inference and surface only as a reconciliation oddity weeks later.
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE auth.subscription SET ai_remaining_credits = -1 WHERE id = 1"))
                .hasMessageContaining("chk_subscription_ai_credits_non_negative");
    }

    @Test
    @DisplayName("zero is allowed - a spent pot is the normal end of a cycle")
    void zeroIsAllowed() {
        jdbc.update("UPDATE auth.subscription SET ai_remaining_credits = 0 WHERE id = 1");
        assertThat(allowanceOf(1)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("the ledger's ai_portion defaults to zero, so existing rows read as wallet-funded")
    void ledgerPortionDefaultsToZero() {
        // Reconciliation ADDS this column back. A default of anything but zero would
        // credit every historical row for an allowance that did not exist yet.
        assertThat(jdbc.queryForObject(
                "SELECT ai_portion FROM auth.credit_ledger WHERE user_id = 1", BigDecimal.class))
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("re-running V494 does not top up a pot the account has spent")
    void rerunDoesNotRefillASpentPot() {
        // Flyway will not re-run it, but a restored snapshot or a replay onto a
        // partially-migrated database will. The '= 0' guard is the only thing between
        // that and a free refill, and it has one deliberate limit, asserted below.
        jdbc.update("UPDATE auth.subscription SET ai_remaining_credits = 30 WHERE id = 1");

        jdbc.execute(v494);

        assertThat(allowanceOf(1))
                .as("a partly-spent pot is left exactly where the account left it")
                .isEqualByComparingTo("30");
    }

    @Test
    @DisplayName("a FULLY spent pot IS refilled by a re-run - the guard cannot tell it from a new row")
    void rerunRefillsAFullySpentPot() {
        // Stated rather than hidden: '= 0' means "never granted" and "spent to nothing"
        // look identical. Bounded by one allowance, only on a replay, and the safe
        // direction is a spent account getting its month back rather than an existing
        // account never getting one - but it IS the guard's blind spot.
        jdbc.update("UPDATE auth.subscription SET ai_remaining_credits = 0 WHERE id = 1");

        jdbc.execute(v494);

        assertThat(allowanceOf(1)).isEqualByComparingTo("100");
    }

    private static BigDecimal allowanceOf(long subscriptionId) {
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

package com.apimarketplace.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;

@DisplayName("Credit pack pricing migration invariants")
class CreditPackPricingMigrationTest {

    @Test
    @DisplayName("V290 replays after V255 removed plan and price disabled columns")
    void v290ReplaysAfterV255RemovedDisabledColumns(@TempDir Path tempDir) throws Exception {
        FlywayTestSupport.assumeDockerAvailable();

        writeCreditPackFixture(tempDir);

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            FlywayTestSupport.createDatabase(postgres, "credit_pack_replay");

            assertThatCode(() -> FlywayTestSupport.runFlyway(postgres, "credit_pack_replay", tempDir))
                    .doesNotThrowAnyException();

            assertThat(disabledColumnExists(postgres, "credit_pack_replay", "plan")).isFalse();
            assertThat(disabledColumnExists(postgres, "credit_pack_replay", "price")).isFalse();
            assertThat(creditPackYearlyAmount(postgres, "credit_pack_replay")).isEqualTo(1200);
            assertThat(creditPackYearlyProviderPriceId(postgres, "credit_pack_replay"))
                    .isEqualTo("price_1Tbh1M1MnvbO0ZY36xN4c3So");
            assertThat(creditPackTeamRowsWithoutProvider(postgres, "credit_pack_replay")).isEqualTo(2);
        }
    }

    private static void writeCreditPackFixture(Path directory) throws Exception {
        Files.writeString(directory.resolve("V1__seed_credit_pack_schema.sql"), """
                CREATE SCHEMA auth;

                CREATE TABLE auth.plan (
                    id BIGINT PRIMARY KEY,
                    code VARCHAR(64) NOT NULL UNIQUE,
                    disabled BOOLEAN NOT NULL DEFAULT FALSE
                );

                CREATE TABLE auth.price (
                    id BIGINT PRIMARY KEY,
                    plan_id BIGINT NOT NULL REFERENCES auth.plan(id),
                    cadence VARCHAR(32) NOT NULL,
                    currency VARCHAR(3) NOT NULL,
                    amount_cents INTEGER NOT NULL,
                    provider VARCHAR(32) NOT NULL,
                    provider_price_id VARCHAR(128),
                    disabled BOOLEAN NOT NULL DEFAULT FALSE,
                    UNIQUE (plan_id, cadence)
                );

                INSERT INTO auth.plan (id, code) VALUES
                    (1, 'CREDIT_PACK'),
                    (2, 'CREDIT_PACK_TEAM');

                INSERT INTO auth.price (id, plan_id, cadence, currency, amount_cents, provider, provider_price_id)
                VALUES
                    (10, 1, 'monthly', 'usd', 100, 'stripe', 'price_credit_pack_monthly'),
                    (11, 1, 'yearly', 'usd', 960, 'stripe', 'price_credit_pack_yearly'),
                    (12, 2, 'monthly', 'usd', 150, 'stripe', 'price_credit_pack_team_monthly'),
                    (13, 2, 'yearly', 'usd', 1440, 'stripe', 'price_credit_pack_team_yearly');
                """);

        Files.writeString(directory.resolve("V2__drop_dead_disabled_columns.sql"), """
                ALTER TABLE auth.plan DROP COLUMN IF EXISTS disabled;
                ALTER TABLE auth.price DROP COLUMN IF EXISTS disabled;
                """);

        String productionV290 = Files.readString(Path.of(
                "src/main/resources/db/migration/V290__credit_pack_yearly_no_credit_discount.sql"));
        Files.writeString(directory.resolve("V3__credit_pack_yearly_no_credit_discount.sql"), productionV290);
    }

    private static boolean disabledColumnExists(
            PostgreSQLContainer<?> postgres,
            String databaseName,
            String tableName) throws Exception {
        try (var connection = DriverManager.getConnection(
                FlywayTestSupport.jdbcUrl(postgres, databaseName), postgres.getUsername(), postgres.getPassword());
             var statement = connection.prepareStatement("""
                     SELECT EXISTS (
                         SELECT 1
                           FROM information_schema.columns
                          WHERE table_schema = 'auth'
                            AND table_name = ?
                            AND column_name = 'disabled'
                     )
                     """)) {
            statement.setString(1, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getBoolean(1);
            }
        }
    }

    private static int creditPackYearlyAmount(PostgreSQLContainer<?> postgres, String databaseName)
            throws Exception {
        try (var connection = DriverManager.getConnection(
                FlywayTestSupport.jdbcUrl(postgres, databaseName), postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT price.amount_cents
                       FROM auth.price price
                       JOIN auth.plan plan ON plan.id = price.plan_id
                      WHERE plan.code = 'CREDIT_PACK'
                        AND price.cadence = 'yearly'
                     """)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static String creditPackYearlyProviderPriceId(PostgreSQLContainer<?> postgres, String databaseName)
            throws Exception {
        try (var connection = DriverManager.getConnection(
                FlywayTestSupport.jdbcUrl(postgres, databaseName), postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT price.provider_price_id
                       FROM auth.price price
                       JOIN auth.plan plan ON plan.id = price.plan_id
                      WHERE plan.code = 'CREDIT_PACK'
                        AND price.cadence = 'yearly'
                     """)) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    private static int creditPackTeamRowsWithoutProvider(PostgreSQLContainer<?> postgres, String databaseName)
            throws Exception {
        try (var connection = DriverManager.getConnection(
                FlywayTestSupport.jdbcUrl(postgres, databaseName), postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT COUNT(*)
                       FROM auth.price price
                       JOIN auth.plan plan ON plan.id = price.plan_id
                      WHERE plan.code = 'CREDIT_PACK_TEAM'
                        AND price.cadence IN ('monthly', 'yearly')
                        AND price.provider_price_id IS NULL
                     """)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }
}

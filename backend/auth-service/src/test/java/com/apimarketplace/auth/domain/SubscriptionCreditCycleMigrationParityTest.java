package com.apimarketplace.auth.domain;

import jakarta.persistence.Column;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binds {@link Subscription#getCreditCycleIndex()} to the migration that adds its column.
 *
 * <p>Same shape and the same reason as {@code PasswordResetTokenMigrationParityTest}: cloud
 * auth-service runs {@code ddl-auto: validate}, and every persistence test of this entity runs
 * on {@code create-drop} where the schema comes from the ENTITY and V498 is never read. A column
 * the entity declares and the DDL does not have is not a failing test, it is every auth pod
 * crash-looping behind an {@code --atomic} rollback. Read from the real migration file, never
 * from a hand-written mirror.
 */
class SubscriptionCreditCycleMigrationParityTest {

    private static final String MIGRATION =
            "migration-service/src/main/resources/db/migration/V498__yearly_subscription_monthly_credit_cycle.sql";

    private static String migrationSql() throws Exception {
        Path here = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path candidate = here; candidate != null; candidate = candidate.getParent()) {
            Path file = candidate.resolve(MIGRATION);
            if (Files.isRegularFile(file)) {
                return Files.readString(file);
            }
        }
        throw new IllegalStateException(
                "could not locate " + MIGRATION + " from " + here
                        + " - this test must fail loudly rather than silently skip, since its whole "
                        + "job is to notice that the entity and the DDL disagree");
    }

    private static Column creditCycleIndexColumn() throws Exception {
        Field field = Subscription.class.getDeclaredField("creditCycleIndex");
        Column column = field.getAnnotation(Column.class);
        assertThat(column).as("creditCycleIndex must be an explicitly mapped @Column").isNotNull();
        return column;
    }

    @Test
    @DisplayName("V498 adds the column the entity maps, on auth.subscription")
    void migrationAddsTheMappedColumn() throws Exception {
        String sql = migrationSql().toLowerCase(Locale.ROOT);
        Column column = creditCycleIndexColumn();

        assertThat(column.name()).isEqualTo("credit_cycle_index");
        assertThat(sql).contains("alter table auth.subscription");
        assertThat(sql).contains("add column if not exists " + column.name());
    }

    @Test
    @DisplayName("the entity's nullability and the DDL agree: NOT NULL with a DEFAULT, so existing rows need no backfill")
    void nullabilityAndDefaultAgree() throws Exception {
        String sql = migrationSql().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        Column column = creditCycleIndexColumn();

        // The entity refuses null, so the DDL must both forbid it and default it: a NOT NULL
        // column without a default would fail the ALTER on the very rows this fix exists for.
        assertThat(column.nullable()).isFalse();
        assertThat(sql).contains(column.name() + " integer not null default 0");
    }
}

package com.apimarketplace.auth.lifecycle;

import com.apimarketplace.auth.domain.User;
import jakarta.persistence.Column;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binds the V529 {@code auth.users} columns to the code that reads and writes them. Cloud
 * auth-service runs {@code ddl-auto: validate}, so a mapped column missing from the DDL
 * crash-loops every auth pod; and the throttle columns are written by raw SQL, so a missing
 * one fails every claim (closed) with no boot error at all. Reads the real migration file.
 */
@DisplayName("V529 - lifecycle user columns match the entity and the throttle")
class UserLifecycleV529MigrationParityTest {

    private static final String MIGRATION =
            "migration-service/src/main/resources/db/migration/V529__lifecycle_trophies_recap_checkout_throttle.sql";

    private static String migrationSql() throws Exception {
        Path here = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path candidate = here; candidate != null; candidate = candidate.getParent()) {
            Path file = candidate.resolve(MIGRATION);
            if (Files.isRegularFile(file)) {
                return Files.readString(file).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            }
        }
        throw new IllegalStateException("could not locate " + MIGRATION + " from " + here);
    }

    private static Optional<Field> mappedField(String column) {
        return Arrays.stream(User.class.getDeclaredFields())
                .filter(f -> {
                    Column c = f.getAnnotation(Column.class);
                    return c != null && column.equals(c.name());
                })
                .findFirst();
    }

    @Test
    @DisplayName("lifecycle_signup_emitted_at is added as timestamptz and mapped READ-ONLY as an Instant")
    void signupStampColumn() throws Exception {
        assertThat(migrationSql()).contains("add column if not exists lifecycle_signup_emitted_at timestamptz");

        Field field = mappedField("lifecycle_signup_emitted_at").orElseThrow(
                () -> new AssertionError("User must map lifecycle_signup_emitted_at"));
        Column column = field.getAnnotation(Column.class);
        assertThat(field.getType()).isEqualTo(Instant.class);
        // Written only by the conditional UPDATE: a whole-row save must never rewind the stamp.
        assertThat(column.insertable()).isFalse();
        assertThat(column.updatable()).isFalse();
    }

    @Test
    @DisplayName("every throttle column is added as timestamptz and is NOT mapped on User")
    void throttleColumns() throws Exception {
        String sql = migrationSql();
        List<String> columns = List.copyOf(CheckoutStartedThrottle.COLUMN_BY_KIND.values());
        assertThat(columns).containsExactlyInAnyOrder("last_checkout_subscription_at", "last_checkout_credits_at");
        for (String column : columns) {
            assertThat(sql).as("auth.users.%s must be added by V529", column)
                    .contains("add column if not exists " + column + " timestamptz");
            assertThat(mappedField(column)).as("%s must stay unmapped so no save can rewind it", column).isEmpty();
        }
    }

    @Test
    @DisplayName("the V529 user columns are added to auth.users")
    void targetsAuthUsers() throws Exception {
        assertThat(migrationSql()).contains("alter table auth.users add column if not exists last_checkout_subscription_at");
    }
}

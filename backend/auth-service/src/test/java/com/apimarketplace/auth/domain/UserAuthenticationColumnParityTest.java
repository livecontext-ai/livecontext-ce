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
 * Binds {@link User#getLastAuthenticatedAt()} to the migration that adds its column.
 *
 * <p>Same shape and the same reason as {@code PasswordResetTokenMigrationParityTest}, which
 * exists because this failure mode has already happened once here: renaming a column in V487
 * left 99 tests green, since the only persistence test for that table ran on H2 with
 * {@code ddl-auto=create-drop}, where the schema comes from the ENTITY and the migration is
 * never read. This change reproduces that setup exactly:
 * {@code UserRepositoryAuthenticationAdvanceTest} builds its schema from the entity, and
 * {@code LoginAuthenticationInstantMigrationTest} reads the database the migration made.
 * Nothing else joins the two.
 *
 * <p>What a mismatch costs is not a red test: cloud auth-service runs
 * {@code ddl-auto: validate}, so a column the entity declares and the DDL does not have is
 * every auth pod refusing to boot behind an {@code --atomic} rollback.
 *
 * <p>Read from the real migration file rather than a hand-written mirror. A mirror is the
 * drift it is supposed to catch: it agrees with whatever it was copied from, forever.
 */
@DisplayName("User.lastAuthenticatedAt matches the column V495 creates")
class UserAuthenticationColumnParityTest {

    private static final String MIGRATION = "migration-service/src/main/resources/db/migration/"
            + "V495__login_counts_an_authentication_not_a_request.sql";

    private static String migrationSql() throws Exception {
        Path here = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path candidate = here; candidate != null; candidate = candidate.getParent()) {
            Path file = candidate.resolve(MIGRATION);
            if (Files.isRegularFile(file)) {
                return Files.readString(file).toLowerCase(Locale.ROOT);
            }
        }
        throw new IllegalStateException("migration not found from " + here + ": " + MIGRATION);
    }

    @Test
    @DisplayName("the entity's column name is the one the migration adds")
    void columnNameMatches() throws Exception {
        Field field = User.class.getDeclaredField("lastAuthenticatedAt");
        String column = field.getAnnotation(Column.class).name();

        assertThat(column)
                .as("the field must map to an explicit column, not an inferred one")
                .isNotBlank();
        assertThat(migrationSql())
                .as("auth-service runs ddl-auto: validate, so a name the DDL does not create "
                        + "is not a red test, it is auth pods that will not boot")
                .contains("add column if not exists " + column);
    }

    @Test
    @DisplayName("the column is timestamptz, matching how last_login_at is already mapped")
    void columnTypeMatches() throws Exception {
        // LocalDateTime against TIMESTAMPTZ is the convention here (V196 converted
        // last_login_at, V201 added last_default_flip_at), and it validates in prod today.
        // A plain TIMESTAMP would still validate but would drop the offset, so two pods in
        // different zones would write instants that do not compare.
        assertThat(migrationSql()).contains("last_authenticated_at timestamptz");
    }

    @Test
    @DisplayName("the column stays nullable, because NULL is what lets a first sign-in count")
    void columnStaysNullable() throws Exception {
        String sql = migrationSql();
        int add = sql.indexOf("add column if not exists last_authenticated_at");
        assertThat(add).as("V495 must still add the column").isGreaterThan(-1);

        // NOT NULL would need a default, would rewrite the hottest table in the schema under
        // an exclusive lock, and would erase the difference between "never authenticated"
        // and "authenticated at the epoch", which is what the conditional advance reads.
        assertThat(sql.substring(add, sql.indexOf(';', add)))
                .doesNotContain("not null");
    }
}

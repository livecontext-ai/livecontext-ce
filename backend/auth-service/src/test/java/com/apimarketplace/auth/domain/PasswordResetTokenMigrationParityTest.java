package com.apimarketplace.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binds {@link PasswordResetToken} to the migration that creates its table.
 *
 * <p>Same shape and the same reason as {@code CeReleaseMigrationParityTest}: the
 * entity is unconditional (the table ships to both editions so the schema stays
 * identical), cloud auth-service runs {@code ddl-auto: validate}, and a column
 * the entity declares and the DDL does not have is not a failing test, it is
 * every auth pod crash-looping behind an {@code --atomic} rollback.
 *
 * <p>This was measured missing: renaming {@code created_ip} to {@code client_ip}
 * in V487 left 99 tests green, because the only persistence test for this table
 * runs on H2 with {@code ddl-auto=create-drop}, where the schema comes from the
 * ENTITY and the migration is never read.
 *
 * <p>Read from the real migration file rather than a hand-written mirror. A
 * mirror is the drift it is supposed to catch: it agrees with whatever it was
 * copied from, forever.
 */
class PasswordResetTokenMigrationParityTest {

    private static final String MIGRATION =
            "migration-service/src/main/resources/db/migration/V487__ce_password_reset_tokens.sql";

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

    private static List<String> entityColumns() {
        List<String> columns = new ArrayList<>();
        for (Field field : PasswordResetToken.class.getDeclaredFields()) {
            Column column = field.getAnnotation(Column.class);
            if (column != null && !column.name().isBlank()) {
                columns.add(column.name());
            }
        }
        return columns;
    }

    @Test
    @DisplayName("every column the entity maps exists in V487")
    void everyMappedColumnExistsInTheMigration() throws Exception {
        String sql = migrationSql().toLowerCase(Locale.ROOT);
        List<String> columns = entityColumns();

        // Guards against the test going vacuous if the annotations are ever
        // restructured: an entity that declares no explicit @Column names would
        // otherwise make the loop below compare nothing.
        assertThat(columns)
                .as("the entity must declare explicit @Column names for this comparison to mean anything")
                .contains("user_id", "token_hash", "expires_at", "used_at", "created_at", "created_ip");

        for (String column : columns) {
            assertThat(sql)
                    .as("column %s is mapped by PasswordResetToken but absent from V487; cloud "
                            + "auth-service runs ddl-auto: validate, so this crash-loops every pod",
                            column)
                    .contains(column);
        }
    }

    @Test
    @DisplayName("the entity's table is the one V487 creates, in the auth schema")
    void tableAndSchemaMatchTheMigration() throws Exception {
        String sql = migrationSql().toLowerCase(Locale.ROOT);
        Table table = PasswordResetToken.class.getAnnotation(Table.class);

        assertThat(table).isNotNull();
        assertThat(table.name()).isEqualTo("password_reset_tokens");
        // The @Table carries no schema on purpose, matching User / RefreshToken:
        // auth-service sets hibernate.default_schema and the CE monolith resolves
        // it through the Hikari search_path. The DDL qualifies it explicitly.
        assertThat(sql).contains("auth." + table.name());
    }

    @Test
    @DisplayName("V487 keeps the three things the flow depends on: the unique hash, the cascade, and "
            + "the two supporting indexes")
    void migrationKeepsTheLoadBearingConstraints() throws Exception {
        String sql = migrationSql().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");

        // A hash collision would let one token redeem another account.
        assertThat(sql)
                .as("the unique index on token_hash is what makes a redemption lookup unambiguous")
                .contains("create unique index")
                .contains("(token_hash)");

        // Without the cascade, a purged account leaves live tokens whose
        // redemption reaches "User not found"; the service folds that into the
        // uniform refusal, but the row should not survive the account at all.
        assertThat(sql)
                .as("ON DELETE CASCADE is what makes an account purge take its reset links with it")
                .contains("references auth.users(id) on delete cascade");

        // The rate-limit scan and the daily cleanup each read one of these.
        assertThat(sql).contains("(user_id, created_at desc)");
        assertThat(sql).contains("(expires_at)");
    }

    @Test
    @DisplayName("created_ip is wide enough for an IPv6 address, which is what the service truncates to")
    void createdIpWidthMatchesTheTruncation() throws Exception {
        String sql = migrationSql().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");

        // PasswordResetService truncates to 45 characters before the insert. If
        // the column ever narrows, that truncation stops being enough and an
        // over-long X-Forwarded-For rolls the whole request back while the
        // requester is told a mail is on its way.
        assertThat(sql).contains("created_ip varchar(45)");
    }
}

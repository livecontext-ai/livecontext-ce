package com.apimarketplace.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binds the V527 lifecycle columns of {@link User} and the {@link UserAcquisition} entity to
 * the migration. Cloud auth-service runs {@code ddl-auto: validate}: a column mapped here and
 * missing from the DDL crash-loops every auth pod. Reads the real migration file.
 */
@DisplayName("V527 - lifecycle columns match the entities")
class UserLifecycleV527MigrationParityTest {

    private static final String MIGRATION =
            "migration-service/src/main/resources/db/migration/V527__user_lifecycle_email_context.sql";

    private static final List<String> USER_COLUMNS = List.of(
            "locale", "locale_explicit", "time_zone", "signup_country", "signup_ip",
            "signup_ip_captured_at", "marketing_consent", "marketing_consent_at", "activated_at");

    private static String migrationSql() throws Exception {
        Path here = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path candidate = here; candidate != null; candidate = candidate.getParent()) {
            Path file = candidate.resolve(MIGRATION);
            if (Files.isRegularFile(file)) {
                return Files.readString(file).toLowerCase(Locale.ROOT);
            }
        }
        throw new IllegalStateException("could not locate " + MIGRATION + " from " + here);
    }

    private static List<String> columnsOf(Class<?> type) {
        return java.util.Arrays.stream(type.getDeclaredFields())
                .map((Field f) -> f.getAnnotation(Column.class))
                .filter(c -> c != null && !c.name().isBlank())
                .map(Column::name)
                .toList();
    }

    @Test
    @DisplayName("every V527 user column is mapped by User and added by the migration")
    void userColumns() throws Exception {
        String sql = migrationSql();
        assertThat(columnsOf(User.class)).containsAll(USER_COLUMNS);
        for (String column : USER_COLUMNS) {
            assertThat(sql).as("auth.users.%s must be added by V527", column).contains("add column if not exists " + column + " ");
        }
    }

    @Test
    @DisplayName("every UserAcquisition column exists in the V527 table")
    void acquisitionColumns() throws Exception {
        String sql = migrationSql();
        List<String> columns = columnsOf(UserAcquisition.class);
        assertThat(columns).hasSize(10);
        for (String column : columns) {
            assertThat(sql).as("user_acquisition.%s must exist in V527", column).contains(column);
        }
        Table table = UserAcquisition.class.getAnnotation(Table.class);
        assertThat(sql).contains("create table if not exists " + table.schema() + "." + table.name());
    }

    @Test
    @DisplayName("the acquisition row is deleted with its user")
    void acquisitionCascades() throws Exception {
        assertThat(migrationSql().replaceAll("\\s+", " "))
                .contains("user_id bigint primary key references auth.users (id) on delete cascade");
    }
}

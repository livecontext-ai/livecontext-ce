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
 * Binds {@link UserChangelogSeen} to the migration that creates its table.
 *
 * <p>Cloud auth-service runs {@code ddl-auto: validate}, so a column the entity maps and the DDL
 * does not have is not a failing test, it is every auth pod crash-looping behind an
 * {@code --atomic} rollback. Nothing else in the build compares the two: the entity is only ever
 * exercised against a mocked repository.
 *
 * <p>Reads the real migration file rather than a hand-written mirror, which would agree with
 * whatever it was copied from forever.
 */
class UserChangelogSeenMigrationParityTest {

    private static final String MIGRATION =
            "migration-service/src/main/resources/db/migration/V480__user_changelog_seen.sql";

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
        for (Field field : UserChangelogSeen.class.getDeclaredFields()) {
            Column column = field.getAnnotation(Column.class);
            if (column != null && !column.name().isBlank()) {
                columns.add(column.name());
            }
        }
        return columns;
    }

    @Test
    @DisplayName("every column the entity maps exists in V480")
    void everyMappedColumnExistsInTheMigration() throws Exception {
        String sql = migrationSql().toLowerCase(Locale.ROOT);
        List<String> columns = entityColumns();

        // Guard against the test going vacuous if the annotations are ever restructured.
        assertThat(columns)
                .as("the entity must declare explicit @Column names for this comparison to mean anything")
                .hasSize(3)
                .contains("user_id", "entry_key", "seen_at");

        for (String column : columns) {
            assertThat(sql)
                    .as("column %s is mapped by UserChangelogSeen but absent from V480; cloud "
                            + "auth-service runs ddl-auto: validate, so this crash-loops every pod", column)
                    .contains(column);
        }
    }

    @Test
    @DisplayName("the entity's table and schema are the ones V480 creates")
    void tableAndSchemaMatchTheMigration() throws Exception {
        String sql = migrationSql().toLowerCase(Locale.ROOT);
        Table table = UserChangelogSeen.class.getAnnotation(Table.class);

        assertThat(table).isNotNull();
        assertThat(table.name()).isEqualTo("user_changelog_seen");
        assertThat(table.schema()).isEqualTo("auth");
        assertThat(sql).contains(table.schema() + "." + table.name());
    }

    @Test
    @DisplayName("V480 keys the table by user, so an acknowledgement replaces the previous one")
    void oneRowPerUser() throws Exception {
        String sql = migrationSql().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");

        // The service reads and writes by primary key. Without the PK a second row could exist and
        // the read would keep finding the older acknowledgement, reopening the panel forever.
        assertThat(sql)
                .as("the primary key on user_id is what makes an acknowledgement overwrite in place")
                .contains("user_id bigint primary key references auth.users(id) on delete cascade");
    }

    @Test
    @DisplayName("V480 stores no changelog CONTENT, only the acknowledgement")
    void migrationStoresNoContent() throws Exception {
        // Comments stripped first: this file explains at length WHY the content is not stored,
        // and matching on prose would fail on its own rationale.
        String sql = migrationSql().lines()
                .filter(line -> !line.strip().startsWith("--"))
                .reduce("", (a, b) -> a + " " + b)
                .toLowerCase(Locale.ROOT);

        // The entry (copy, media, date) ships with the build so cloud and CE each announce what
        // they are running. A content column here would be a second source of truth, and the two
        // would drift the first time a deploy and a release did not coincide.
        assertThat(sql).doesNotContain("title");
        assertThat(sql).doesNotContain("body");
        assertThat(sql).doesNotContain("media");
    }

    @Test
    @DisplayName("account deletion removes the acknowledgement")
    void accountPurgeDeletesTheRow() throws Exception {
        Path here = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path purge = null;
        for (Path candidate = here; candidate != null && purge == null; candidate = candidate.getParent()) {
            Path file = candidate.resolve(
                    "auth-service/src/main/java/com/apimarketplace/auth/service/AccountPurgeService.java");
            if (Files.isRegularFile(file)) {
                purge = file;
            }
        }
        assertThat(purge).as("AccountPurgeService must be locatable for this check to mean anything").isNotNull();

        // There is no FK to auth.users (like the other user-keyed side tables), so nothing deletes
        // this row for us. A forgotten account would leave its acknowledgement behind.
        assertThat(Files.readString(purge))
                .as("deleting an account must delete its changelog acknowledgement")
                .contains("DELETE FROM auth.user_changelog_seen WHERE user_id = ?");
    }
}

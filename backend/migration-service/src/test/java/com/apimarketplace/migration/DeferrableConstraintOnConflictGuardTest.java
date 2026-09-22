package com.apimarketplace.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * No migration may use {@code ON CONFLICT} against a table whose UNIQUE or EXCLUDE
 * constraint is declared {@code DEFERRABLE}.
 *
 * <h2>Why this exists</h2>
 * <p>Postgres refuses a deferrable constraint as an {@code ON CONFLICT} arbiter outright:
 * <em>"ON CONFLICT does not support deferrable unique constraints/exclusion constraints as
 * arbiters"</em>, SQLSTATE 55000. The refusal happens when the statement is planned, so it
 * does not depend on the data: such a migration cannot apply to ANY database, ever.
 *
 * <p>That is worse than it sounds, because Flyway stops at the first failure. One
 * impossible statement blocks every migration behind it, on every install, until someone
 * edits it. It happened for real: V490 shipped with a belt-and-braces
 * {@code ON CONFLICT DO NOTHING} on {@code publication.publication_highlights}, whose
 * {@code pub_highlights_rank_unique} has been {@code DEFERRABLE INITIALLY DEFERRED} since
 * V164. The production deploy of 2026-09-16 17:13 failed on it, rolled back atomically,
 * and left prod pinned at V489 with a later, unrelated billing migration stuck behind it.
 *
 * <h2>Why a text scan and not a replay</h2>
 * <p>Replaying the whole migration set against a real Postgres would catch this and much
 * more, and it is the better test, but it needs the {@code vector} extension, the
 * {@code lc.migration.source_timezone} GUC and a schema list that CI's plain
 * {@code services: postgres} does not have. A guard that SKIPS is a green build that
 * verified nothing, which is the failure mode this repository keeps paying for. This scan
 * needs no database, so it runs everywhere, always.
 *
 * <p>It is deliberately narrow: it knows only the constraint declarations the migrations
 * themselves carry, so it cannot drift from the schema. A false positive is cheap to
 * resolve (name the conflict target explicitly on a non-deferrable index, or guard with
 * NOT EXISTS); the defect it prevents costs a failed production deploy.
 */
@DisplayName("A migration never uses ON CONFLICT on a table with a deferrable constraint")
class DeferrableConstraintOnConflictGuardTest {

    private static final Path MIGRATIONS =
            Path.of("src", "main", "resources", "db", "migration");

    /** {@code CONSTRAINT <name> UNIQUE (...) ... DEFERRABLE}, across newlines. */
    private static final Pattern DEFERRABLE_CONSTRAINT = Pattern.compile(
            "CONSTRAINT\\s+\\w+\\s+(?:UNIQUE|EXCLUDE)\\b[^;]*?DEFERRABLE",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** The table a {@code CREATE TABLE [IF NOT EXISTS] [schema.]name} statement declares. */
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([\\w.]+)",
            Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("every table carrying a deferrable UNIQUE or EXCLUDE is free of ON CONFLICT")
    void noOnConflictAgainstADeferrableConstraint() throws IOException {
        List<Path> migrations = migrationFiles();
        assertThat(migrations).as("the migration directory must be found from this module").isNotEmpty();

        Set<String> deferrableTables = tablesWithADeferrableConstraint(migrations);
        assertThat(deferrableTables)
                .as("V164 declares pub_highlights_rank_unique DEFERRABLE, so the scan must see at "
                        + "least that one. An empty set would mean the pattern stopped matching and "
                        + "the guard had quietly become a no-op")
                .contains("publication_highlights");

        List<String> offences = new ArrayList<>();
        for (Path migration : migrations) {
            String sql = statementsOf(migration);
            if (!sql.toUpperCase(Locale.ROOT).contains("ON CONFLICT")) {
                continue;
            }
            for (String table : deferrableTables) {
                if (mentions(sql, table)) {
                    offences.add(migration.getFileName() + " uses ON CONFLICT on " + table);
                }
            }
        }

        assertThat(offences)
                .as("Postgres rejects a deferrable constraint as an ON CONFLICT arbiter "
                        + "(SQLSTATE 55000) when the statement is PLANNED, so such a migration "
                        + "cannot apply to any database and blocks every migration behind it. "
                        + "Name the conflict target explicitly on a non-deferrable index, or "
                        + "guard with NOT EXISTS instead")
                .isEmpty();
    }

    /** Constraint declarations are read from CREATE TABLE bodies, which is where they live. */
    private static Set<String> tablesWithADeferrableConstraint(List<Path> migrations) throws IOException {
        Set<String> tables = new LinkedHashSet<>();
        for (Path migration : migrations) {
            String sql = statementsOf(migration);
            Matcher creates = CREATE_TABLE.matcher(sql);
            while (creates.find()) {
                String table = creates.group(1);
                int bodyStart = creates.end();
                int bodyEnd = sql.indexOf(';', bodyStart);
                String body = sql.substring(bodyStart, bodyEnd < 0 ? sql.length() : bodyEnd);
                if (DEFERRABLE_CONSTRAINT.matcher(body).find()) {
                    tables.add(bare(table));
                }
            }
        }
        return tables;
    }

    /** Matches the table written bare or schema-qualified, never as a prefix of a longer name. */
    private static boolean mentions(String sql, String table) {
        return Pattern.compile("\\b" + Pattern.quote(table) + "\\b", Pattern.CASE_INSENSITIVE)
                .matcher(sql).find();
    }

    private static String bare(String qualified) {
        int dot = qualified.lastIndexOf('.');
        return dot < 0 ? qualified : qualified.substring(dot + 1);
    }

    /** The file with its {@code --} comments removed: prose about ON CONFLICT is not SQL. */
    private static String statementsOf(Path migration) throws IOException {
        StringBuilder stripped = new StringBuilder();
        for (String line : Files.readString(migration, StandardCharsets.UTF_8).split("\n", -1)) {
            int comment = line.indexOf("--");
            stripped.append(comment < 0 ? line : line.substring(0, comment)).append('\n');
        }
        return stripped.toString();
    }

    private static List<Path> migrationFiles() throws IOException {
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".sql")).sorted().toList();
        }
    }
}

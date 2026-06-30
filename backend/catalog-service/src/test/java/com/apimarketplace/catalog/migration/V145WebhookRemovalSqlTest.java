package com.apimarketplace.catalog.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Static verification of {@code V145__remove_webhook_mode.sql}.
 *
 * <p>The runtime Postgres regression for the new CHECK constraint lives in
 * {@code TypedExecutionPostgresE2ETest#checkConstraintBlocksWebhookInsert}, but that whole
 * class is {@code @Disabled} due to a pre-existing pgvector/V75 infrastructure issue.
 * Until the Postgres harness comes back online, this lightweight SQL-level assertion
 * guards the V145 migration against accidental regression: a future edit that removes the
 * defensive {@code UPDATE} or re-adds {@code 'webhook'} to the enum will fail this test
 * and block the merge.
 */
@DisplayName("V145 SQL - static contract check")
class V145WebhookRemovalSqlTest {

    private static final Path V145 = Paths.get(
            "..", "migration-service", "src", "main", "resources", "db", "migration",
            "V145__remove_webhook_mode.sql");

    @Test
    @DisplayName("CHECK constraint enum must NOT mention 'webhook'")
    void checkConstraintEnumNoLongerMentionsWebhook() throws IOException {
        String sql = Files.readString(V145, StandardCharsets.UTF_8);
        String executableSql = stripSqlComments(sql);

        assertThat(executableSql)
                .as("V145 must redefine the CHECK enum")
                .contains("ADD CONSTRAINT check_api_tools_execution_mode")
                .contains("'sync'")
                .contains("'async_poll'")
                .contains("'upload'")
                .contains("'streaming'");

        // Extract just the IN (...) tuple of the new CHECK constraint and assert it
        // doesn't contain 'webhook'. The defensive UPDATE elsewhere in the file is
        // legitimately allowed to mention 'webhook' (that's the whole point - coerce
        // legacy rows). What we want to guarantee is that no future enum-literal edit
        // re-adds it as a valid value.
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("execution_mode\\s+IN\\s*\\(([^)]*)\\)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(executableSql);
        assertThat(m.find())
                .as("V145 must contain a `execution_mode IN (...)` clause")
                .isTrue();
        String enumTuple = m.group(1);
        assertThat(enumTuple)
                .as("CHECK enum tuple must not include 'webhook'")
                .doesNotContain("webhook");
    }

    @Test
    @DisplayName("Defensive UPDATE coerces legacy webhook rows to 'sync' before the CHECK swap")
    void defensiveUpdateIsPresentBeforeConstraint() throws IOException {
        String sql = Files.readString(V145, StandardCharsets.UTF_8);
        int updateIdx = sql.indexOf("UPDATE api_tools SET execution_mode = 'sync' WHERE execution_mode = 'webhook'");
        int addCheckIdx = sql.indexOf("ADD CONSTRAINT check_api_tools_execution_mode");

        assertThat(updateIdx)
                .as("Defensive UPDATE must be present (dev safety net)")
                .isGreaterThanOrEqualTo(0);
        assertThat(addCheckIdx)
                .as("ADD CONSTRAINT must be present")
                .isGreaterThanOrEqualTo(0);
        assertThat(updateIdx)
                .as("UPDATE must execute BEFORE the new CHECK is added (otherwise the swap would fail)")
                .isLessThan(addCheckIdx);
    }

    private static String stripSqlComments(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        for (String line : sql.split("\\R")) {
            int idx = line.indexOf("--");
            out.append(idx >= 0 ? line.substring(0, idx) : line).append('\n');
        }
        return out.toString();
    }
}

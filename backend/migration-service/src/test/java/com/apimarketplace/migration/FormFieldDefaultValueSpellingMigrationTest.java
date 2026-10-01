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

/**
 * Replay test for V550: the public form's copy of a form trigger's fields spells a default
 * {@code defaultValue}.
 *
 * <p>Agents wrote {@code default} / {@code default_value}, which the builder stored and the public
 * form never read (2026-09-29, a Gemini chat on prod). V550 rewrites
 * {@code "trigger".standalone_form_endpoints.form_config}, the copy the public form renders, and
 * deliberately leaves the plans alone. Every rule of the rewrite, what it must not touch, and a
 * second pass that changes nothing are pinned here on a real Postgres: the CI's own server
 * through MIGRATION_TEST_PG_URL (no skip possible there), a container on a developer machine.
 */
@DisplayName("form field default spelling in the public form copy (V550)")
class FormFieldDefaultValueSpellingMigrationTest {

    private static final String DB = "form_default_spelling";
    private static final String RULES = "11111111-1111-1111-1111-111111111111";
    private static final String NOT_AN_ARRAY = "22222222-2222-2222-2222-222222222222";
    private static final String NULL_CONFIG = "33333333-3333-3333-3333-333333333333";
    private static final String EMPTY = "44444444-4444-4444-4444-444444444444";
    private static final String CLEAN = "55555555-5555-5555-5555-555555555555";
    private static final String MIXED = "77777777-7777-7777-7777-777777777777";

    @Test
    @DisplayName("V550 moves each alias onto defaultValue in the form copy, leaves plans and odd shapes alone, and a re-run changes nothing")
    void v550RewritesTheFormCopyOnly(@TempDir Path tempDir) throws Exception {
        writeFixture(tempDir);

        try (FlywayTestSupport.PostgresTarget postgres = FlywayTestSupport.openPostgres()) {
            postgres.createDatabase(DB);
            assertThatCode(() -> postgres.runFlyway(DB, tempDir)).doesNotThrowAnyException();

            String fields = "(SELECT form_config FROM \"trigger\".standalone_form_endpoints WHERE id = '" + RULES + "')";
            // `default` alone -> defaultValue, the alias removed; other keys kept.
            assertThat(scalar(postgres, "SELECT " + fields + "->0->>'defaultValue'")).isEqualTo("A");
            assertThat(scalar(postgres, "SELECT (" + fields + "->0) ? 'default'")).isEqualTo("f");
            assertThat(scalar(postgres, "SELECT " + fields + "->0->>'required'")).isEqualTo("true");
            // A non-blank defaultValue wins; the alias is dropped.
            assertThat(scalar(postgres, "SELECT " + fields + "->1->>'defaultValue'")).isEqualTo("keep");
            assertThat(scalar(postgres, "SELECT (" + fields + "->1) ? 'default'")).isEqualTo("f");
            // A whitespace-only defaultValue (tab, newline) counts as absent: the alias fills it.
            assertThat(scalar(postgres, "SELECT " + fields + "->2->>'defaultValue'")).isEqualTo("C");
            // default_value is an alias too.
            assertThat(scalar(postgres, "SELECT " + fields + "->3->>'defaultValue'")).isEqualTo("D");
            assertThat(scalar(postgres, "SELECT (" + fields + "->3) ? 'default_value'")).isEqualTo("f");
            // A blank alias (spaces, tab) is dropped without inventing a default.
            assertThat(scalar(postgres, "SELECT (" + fields + "->4) ?| array['default','defaultValue']"))
                    .isEqualTo("f");
            // A non-string default keeps its JSON type.
            assertThat(scalar(postgres, "SELECT jsonb_typeof(" + fields + "->5->'defaultValue')")).isEqualTo("number");
            // A field with no default, and field order, are untouched.
            assertThat(scalar(postgres, "SELECT (" + fields + "->6) ?| array['default','defaultValue']"))
                    .isEqualTo("f");
            assertThat(scalar(postgres, "SELECT string_agg(f->>'name', ',' ORDER BY o) "
                    + "FROM jsonb_array_elements(" + fields + ") WITH ORDINALITY AS e(f, o)"))
                    .isEqualTo("a,b,c,d,e,n,f,z");
            // A JSON-null defaultValue counts as absent: the alias fills it.
            assertThat(scalar(postgres, "SELECT " + fields + "->7->>'defaultValue'")).isEqualTo("Z");

            // An array mixing a non-object entry with an aliased field: the field is rewritten,
            // the other entry kept as is.
            assertThat(configOf(postgres, MIXED))
                    .isEqualTo("[\"email\", {\"name\": \"q\", \"defaultValue\": \"Q\"}]");

            // Odd shapes are never rewritten and never fail the migration.
            assertThat(configOf(postgres, NOT_AN_ARRAY)).isEqualTo(markerOf(postgres, NOT_AN_ARRAY));
            assertThat(configOf(postgres, NULL_CONFIG)).isNull();
            assertThat(configOf(postgres, EMPTY)).isEqualTo("[]");
            // A copy with no alias is left byte for byte.
            assertThat(configOf(postgres, CLEAN)).isEqualTo(markerOf(postgres, CLEAN));

            // Plans are deliberately left as written: readers tolerate the alias.
            assertThat(scalar(postgres, "SELECT plan->'triggers'->0->'params'->'fields'->0->>'default' "
                    + "FROM orchestrator.workflows")).isEqualTo("P");

            // No helper function is left behind, and the V3 re-run found nothing left to rewrite.
            assertThat(scalar(postgres, "SELECT count(*) FROM pg_proc WHERE proname LIKE 'v550_%'"))
                    .isEqualTo("0");
            assertThat(scalar(postgres, "SELECT count(*) FROM \"trigger\".standalone_form_endpoints e, "
                    + "jsonb_array_elements(e.form_config) f WHERE jsonb_typeof(e.form_config) = 'array' "
                    + "AND jsonb_typeof(f) = 'object' AND f ?| array['default','default_value']"))
                    .isEqualTo("0");
        }
    }

    /** The REAL V550 is replayed as V2, then again as V3 to prove it is idempotent. */
    private static void writeFixture(Path directory) throws Exception {
        Files.writeString(directory.resolve("V1__seed.sql"), """
                CREATE SCHEMA orchestrator;
                CREATE SCHEMA "trigger";

                CREATE TABLE "trigger".standalone_form_endpoints (
                    id          UUID PRIMARY KEY,
                    form_config JSONB,
                    marker      JSONB
                );
                CREATE TABLE orchestrator.workflows (
                    id   UUID PRIMARY KEY,
                    plan JSONB
                );

                INSERT INTO "trigger".standalone_form_endpoints (id, form_config) VALUES
                  ('11111111-1111-1111-1111-111111111111', '[
                     {"name":"a","type":"text","required":true,"default":"A"},
                     {"name":"b","type":"text","defaultValue":"keep","default":"drop"},
                     {"name":"c","type":"text","defaultValue":"\\t\\n","default":"C"},
                     {"name":"d","type":"text","default_value":"D"},
                     {"name":"e","type":"text","default":" \\t "},
                     {"name":"n","type":"number","default":2000},
                     {"name":"f","type":"text"},
                     {"name":"z","type":"text","defaultValue":null,"default":"Z"}]'::jsonb);

                INSERT INTO "trigger".standalone_form_endpoints (id, form_config, marker) VALUES
                  ('22222222-2222-2222-2222-222222222222', '{"default":"not a list"}'::jsonb, '{"default":"not a list"}'::jsonb),
                  ('33333333-3333-3333-3333-333333333333', NULL, NULL),
                  ('44444444-4444-4444-4444-444444444444', '[]'::jsonb, '[]'::jsonb),
                  ('55555555-5555-5555-5555-555555555555',
                   '[{"name":"a","type":"text","defaultValue":"clean"}, "not a field"]'::jsonb,
                   '[{"name":"a","type":"text","defaultValue":"clean"}, "not a field"]'::jsonb),
                  ('77777777-7777-7777-7777-777777777777', '["email", {"name":"q","default":"Q"}]'::jsonb, NULL);

                INSERT INTO orchestrator.workflows (id, plan) VALUES
                  ('66666666-6666-6666-6666-666666666666',
                   '{"triggers":[{"label":"F","type":"form","params":{"fields":[
                      {"name":"a","type":"text","default":"P"}]}}]}'::jsonb);
                """);
        String v550 = Files.readString(Path.of(
                "src/main/resources/db/migration/V550__form_field_default_value_spelling.sql"));
        Files.writeString(directory.resolve("V2__form_default_spelling.sql"), v550);
        Files.writeString(directory.resolve("V3__reapply_form_default_spelling.sql"), v550);
    }

    private static String configOf(FlywayTestSupport.PostgresTarget postgres, String id) throws Exception {
        return scalar(postgres, "SELECT form_config::text FROM \"trigger\".standalone_form_endpoints WHERE id = '" + id + "'");
    }

    private static String markerOf(FlywayTestSupport.PostgresTarget postgres, String id) throws Exception {
        return scalar(postgres, "SELECT marker::text FROM \"trigger\".standalone_form_endpoints WHERE id = '" + id + "'");
    }

    private static String scalar(FlywayTestSupport.PostgresTarget postgres, String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                postgres.jdbcUrl(DB), postgres.username(), postgres.password());
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }
}

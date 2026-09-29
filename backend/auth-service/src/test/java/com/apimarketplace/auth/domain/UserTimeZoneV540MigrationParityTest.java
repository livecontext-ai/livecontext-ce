package com.apimarketplace.auth.domain;

import jakarta.persistence.Column;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binds {@code User.timeZoneExplicit} to the V540 migration that adds its column.
 *
 * <p>The module already keeps one of these per lifecycle migration (V527, V529) for a reason that
 * is specific and unforgiving: cloud auth-service runs {@code ddl-auto: validate}, so a column
 * that is mapped by the entity and missing from the DDL does not degrade, it crash-loops every
 * auth pod, which is every sign-in in the product. Nothing else catches it before deploy, because
 * a unit suite with an in-memory or mocked repository never validates the mapping against the real
 * schema.
 *
 * <p>Reads the migration FILE rather than a database, so it runs in any environment and fails on
 * the pull request that forgets the migration rather than on the rollout.
 */
@DisplayName("V540 - the explicit-time-zone column matches the entity")
class UserTimeZoneV540MigrationParityTest {

    private static final String MIGRATION =
            "migration-service/src/main/resources/db/migration/V540__user_time_zone_explicit.sql";

    private static final String COLUMN = "time_zone_explicit";

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

    private static Column columnAnnotation(String field) throws Exception {
        return User.class.getDeclaredField(field).getAnnotation(Column.class);
    }

    @Test
    @DisplayName("the entity maps the column the migration adds")
    void entityAndMigrationAgree() throws Exception {
        assertThat(columnAnnotation("timeZoneExplicit").name()).isEqualTo(COLUMN);
        assertThat(migrationSql())
                .as("auth.users.%s must be added by V540", COLUMN)
                .contains("add column if not exists " + COLUMN + " ");
    }

    @Test
    @DisplayName("the column is NOT NULL with a false default, so every row that predates the "
            + "release reads as 'never picked'")
    void notNullWithAFalseDefault() throws Exception {
        // Both halves matter and they are easy to get half-right. Without NOT NULL the entity's
        // primitive boolean unboxes a null; without DEFAULT false the ALTER cannot add a NOT NULL
        // column to a populated table at all. And false is the accurate backfill: nobody could
        // pick a zone before this migration existed, so every stored value came from a browser.
        assertThat(migrationSql().replaceAll("\\s+", " "))
                .contains("add column if not exists " + COLUMN + " boolean not null default false");
    }

    @Test
    @DisplayName("the entity never writes the column, so the conditional UPDATEs stay the only "
            + "path that can set it")
    void entityIsReadOnlyForThisColumn() throws Exception {
        // The pin is decided by three conditional UPDATEs in UserRepository, which only touch the
        // row when the value actually changes. A writable mapping would let any unrelated save of
        // a stale User silently un-pin somebody's zone - the exact bug the column exists to
        // prevent, reintroduced one layer up. locale_explicit is mapped the same way for the same
        // reason.
        Column column = columnAnnotation("timeZoneExplicit");
        assertThat(column.insertable()).isFalse();
        assertThat(column.updatable()).isFalse();
        assertThat(column.nullable()).isFalse();
    }

    @Test
    @DisplayName("it mirrors locale_explicit, so the two preferences cannot drift apart")
    void mirrorsTheLocaleFlag() throws Exception {
        // They are the same idea applied to two fields: "observed by the app" versus "chosen by
        // the person". A difference in how they are mapped is a difference in how they behave,
        // and it would show up only as one of the two quietly forgetting a setting.
        Column locale = columnAnnotation("localeExplicit");
        Column zone = columnAnnotation("timeZoneExplicit");

        assertThat(zone.insertable()).isEqualTo(locale.insertable());
        assertThat(zone.updatable()).isEqualTo(locale.updatable());
        assertThat(zone.nullable()).isEqualTo(locale.nullable());
    }

    @Test
    @DisplayName("it carries a generated DEFAULT, without which every save of any User fails")
    void carriesAColumnDefault() throws Exception {
        // This looks like decoration and is not. The column is NOT NULL and insertable = false, so
        // every INSERT omits it; anything that builds the schema from this entity instead of from
        // the migration - the test profile does - then creates a NOT NULL column with no default,
        // and the first save of ANY User violates it. Removing the annotation on a reviewer's
        // suggestion broke 202 tests across the module at once, none of them about time zones,
        // which is why it is pinned here next to the reason.
        for (String field : java.util.List.of("timeZoneExplicit", "localeExplicit")) {
            org.hibernate.annotations.ColumnDefault generated = User.class
                    .getDeclaredField(field)
                    .getAnnotation(org.hibernate.annotations.ColumnDefault.class);

            assertThat(generated).as("%s must declare a generated default", field).isNotNull();
            assertThat(generated.value()).as("%s default", field).isEqualTo("false");
        }
    }
}

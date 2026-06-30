package com.apimarketplace.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("User onboarding list migration")
class UserOnboardingListsJsonbMigrationTest {

    @Test
    @DisplayName("converts text array onboarding lists to jsonb for Hibernate List mapping")
    void convertsTextArrayOnboardingListsToJsonbForHibernateListMapping() throws Exception {
        String sql = Files.readString(Path.of(
            "src/main/resources/db/migration/V241__user_onboarding_lists_jsonb.sql"));

        assertThat(sql).contains("ALTER COLUMN interests TYPE jsonb");
        assertThat(sql).contains("ALTER COLUMN use_cases TYPE jsonb");
        assertThat(sql).contains("to_jsonb(COALESCE(interests, ARRAY[]::text[]))");
        assertThat(sql).contains("to_jsonb(COALESCE(use_cases, ARRAY[]::text[]))");
        assertThat(sql).contains("SET DEFAULT '[]'::jsonb");
    }
}

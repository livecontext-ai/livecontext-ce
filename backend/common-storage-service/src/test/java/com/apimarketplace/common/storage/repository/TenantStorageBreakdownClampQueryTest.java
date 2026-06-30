package com.apimarketplace.common.storage.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the GREATEST(...) clamp inside TenantStorageBreakdownRepository.incrementUsage
 * at the SQL-string level - a cheap belt that runs in every environment regardless
 * of Docker availability.
 *
 * Authoritative behavioural coverage lives in
 * {@code orchestrator-service / TenantStorageBreakdownClampPostgresIT}, which runs
 * the actual @Query SQL against a real Postgres container and asserts the row
 * never goes negative. That IT is skipped silently without Docker, so this
 * pattern-level test exists as the always-runnable fallback.
 *
 * Pre-fix prod evidence (2026-05-11): tenant 1 had EXECUTION_DATA used_bytes
 * drifting to -10,677,252 over 15 days because the ON CONFLICT branch lacked
 * GREATEST(...) and accepted negative cumulative sums.
 */
@DisplayName("TenantStorageBreakdownRepository SQL clamp invariant")
class TenantStorageBreakdownClampQueryTest {

    @Test
    @DisplayName("incrementUsage SQL clamps both used_bytes and item_count via GREATEST(..., 0)")
    void incrementUsageQueryContainsGreatestClamp() throws NoSuchMethodException {
        Method method = TenantStorageBreakdownRepository.class.getMethod(
                "incrementUsage", String.class, String.class, long.class, int.class);

        Query annotation = method.getAnnotation(Query.class);
        assertThat(annotation).as("@Query annotation on incrementUsage").isNotNull();

        String sql = annotation.value().replaceAll("\\s+", " ");

        // ON CONFLICT branch: both columns must be wrapped in GREATEST(..., 0).
        assertThat(sql)
                .as("used_bytes must be clamped at zero on update")
                .containsPattern("used_bytes\\s*=\\s*GREATEST\\(.*used_bytes.*\\+\\s*:deltaBytes.*,\\s*0\\)");
        assertThat(sql)
                .as("item_count must be clamped at zero on update")
                .containsPattern("item_count\\s*=\\s*GREATEST\\(.*item_count.*\\+\\s*:deltaCount.*,\\s*0\\)");

        // INSERT branch: first write of a negative delta (no row yet) must also clamp.
        assertThat(sql)
                .as("INSERT branch must clamp the seeding deltaBytes")
                .containsPattern("VALUES\\s*\\(.*GREATEST\\(:deltaBytes,\\s*0\\).*GREATEST\\(:deltaCount,\\s*0\\)");
    }
}

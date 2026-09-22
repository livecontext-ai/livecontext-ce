package com.apimarketplace.common.storage.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the GREATEST(...) clamp inside {@code OrgStorageBreakdownRepository.incrementUsage} at the
 * SQL-string level, the always-runnable belt that needs no database.
 *
 * <p>The org table shipped without this clamp AND without the
 * {@code CHECK (used_bytes >= 0)} its tenant twin got in V184, so it was the one counter in this
 * area with no guard at all. Reaching a negative was not theoretical: until 2026-09-18, deleting an
 * S3-backed step output debited {@code STEP_OUTPUTS} at org scope while the save had credited
 * {@code FILES}, so the debited bucket was one nothing had ever credited.
 *
 * <p>Authoritative behavioural coverage is
 * {@code orchestrator-service / StorageAccountingQueriesPostgresTest}, which runs this exact
 * {@code @Query} against a real engine. This one exists because a pattern test costs nothing and
 * still fails if someone strips the clamp while editing the SQL for another reason.
 */
@DisplayName("OrgStorageBreakdownRepository SQL clamp invariant")
class OrgStorageBreakdownClampQueryTest {

    @Test
    @DisplayName("incrementUsage SQL clamps both used_bytes and item_count via GREATEST(..., 0)")
    void incrementUsageQueryContainsGreatestClamp() throws NoSuchMethodException {
        Method method = OrgStorageBreakdownRepository.class.getMethod(
                "incrementUsage", String.class, String.class, long.class, int.class);

        Query annotation = method.getAnnotation(Query.class);
        assertThat(annotation).as("@Query annotation on incrementUsage").isNotNull();

        String sql = annotation.value().replaceAll("\\s+", " ");

        assertThat(sql)
                .as("used_bytes must be clamped at zero on update")
                .containsPattern("used_bytes\\s*=\\s*GREATEST\\(.*used_bytes.*\\+\\s*:deltaBytes.*,\\s*0\\)");
        assertThat(sql)
                .as("item_count must be clamped at zero on update")
                .containsPattern("item_count\\s*=\\s*GREATEST\\(.*item_count.*\\+\\s*:deltaCount.*,\\s*0\\)");
        assertThat(sql)
                .as("INSERT branch must clamp too: the first touch of a never-credited bucket is a debit")
                .containsPattern("VALUES\\s*\\(.*GREATEST\\(:deltaBytes,\\s*0\\).*GREATEST\\(:deltaCount,\\s*0\\)");
    }
}

package com.apimarketplace.agent.domain;

import com.apimarketplace.agent.repository.CatalogBundleSyncStatusRepository;
import com.apimarketplace.agent.repository.SkillBundleSyncStatusRepository;
import jakarta.persistence.Column;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The backoff columns must be READ-ONLY on the entity. The applier and the failure bookkeeping
 * save the whole status row; if these columns were updatable, that save would write back whatever
 * the in-memory copy held and silently undo the backoff the scheduler had just armed. The real-JPA
 * proof of that invariant lives in catalog-service (ApiCatalogBundleSyncStatusBackoffJpaTest,
 * same mapping); this pins the two agent-service copies to it.
 */
@DisplayName("Bundle sync-status entities - backoff columns are written only by updateBackoff")
class BundleSyncStatusBackoffMappingTest {

    static Stream<Arguments> entities() {
        return Stream.of(
                Arguments.of(CatalogBundleSyncStatusEntity.class, CatalogBundleSyncStatusRepository.class,
                        "CatalogBundleSyncStatusEntity"),
                Arguments.of(SkillBundleSyncStatusEntity.class, SkillBundleSyncStatusRepository.class,
                        "SkillBundleSyncStatusEntity"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("entities")
    @DisplayName("backoff_level and next_attempt_at are neither insertable nor updatable")
    void columnsAreReadOnly(Class<?> entity, Class<?> repository, String table) throws Exception {
        for (String field : new String[]{"backoffLevel", "nextAttemptAt"}) {
            Column column = entity.getDeclaredField(field).getAnnotation(Column.class);
            assertThat(column).as(field).isNotNull();
            assertThat(column.insertable()).as(field + " insertable").isFalse();
            assertThat(column.updatable()).as(field + " updatable").isFalse();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("entities")
    @DisplayName("updateBackoff is JPQL on this entity (typed Instant bind) and touches only the two backoff attributes")
    void updateBackoffTargetsTheRightEntity(Class<?> entity, Class<?> repository, String entityName) throws Exception {
        Method m = repository.getMethod("updateBackoff", int.class, Instant.class);
        Query query = m.getAnnotation(Query.class);
        // JPQL: the Instant goes through the attribute's mapping like every other timestamp of
        // the entity (the real-JPA round trip is ApiCatalogBundleSyncStatusBackoffJpaTest).
        assertThat(query.nativeQuery()).isFalse();
        assertThat(query.value()).startsWith("UPDATE " + entityName + " s SET ")
                .contains("s.backoffLevel = :level")
                .contains("s.nextAttemptAt = :nextAttemptAt")
                .endsWith("WHERE s.id = 1")
                .doesNotContain("lastFetch").doesNotContain("consecutiveFailures");
    }
}

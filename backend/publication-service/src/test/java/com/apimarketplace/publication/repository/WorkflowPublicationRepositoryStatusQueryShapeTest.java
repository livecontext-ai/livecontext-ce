package com.apimarketplace.publication.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural guard for {@code findPublicationStatusesByWorkflowIds}, which feeds
 * the workflow list's moderation badge.
 *
 * <p>Two load-bearing invariants the JPQL must keep:
 * <ul>
 *   <li>It selects BOTH {@code p.workflowId} and {@code p.status} - the
 *       controller folds the rows into a {@code workflowId → status} map; a
 *       single-column projection would NPE the mapping.</li>
 *   <li>It excludes {@code INACTIVE} (unpublished / soft-deleted) rows. Without
 *       the {@code <> 'INACTIVE'} guard, an unpublished workflow would resurface
 *       as "shared · in review" / "shared" in the list - exactly the stale-state
 *       bug the ACTIVE-only {@code findPublishedWorkflowIds} never had.</li>
 * </ul>
 */
@DisplayName("WorkflowPublicationRepository.findPublicationStatusesByWorkflowIds - query shape")
class WorkflowPublicationRepositoryStatusQueryShapeTest {

    @Test
    @DisplayName("selects (workflowId, status) and excludes INACTIVE publications")
    void selectsWorkflowIdAndStatusExcludingInactive() throws NoSuchMethodException {
        Method m = WorkflowPublicationRepository.class.getMethod(
                "findPublicationStatusesByWorkflowIds", Collection.class);
        Query q = m.getAnnotation(Query.class);
        assertThat(q).as("@Query annotation must be present").isNotNull();

        String value = q.value().replaceAll("\\s+", " ");

        assertThat(value)
                .as("must project both workflowId and status for the (id → status) map")
                .contains("SELECT p.workflowId, p.status");
        assertThat(value)
                .as("must scope to the requested workflow ids")
                .contains("p.workflowId IN :workflowIds");
        assertThat(value)
                .as("must exclude INACTIVE rows so unpublished workflows never read as shared")
                .contains("p.status <> 'INACTIVE'");
    }
}

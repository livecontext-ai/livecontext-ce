package com.apimarketplace.publication.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural guard for {@code findStatusesByPublicationIds}, which the applications board uses
 * to drop APPLICATION rows whose source publication is INACTIVE (unpublished).
 *
 * <p>Two load-bearing invariants - and one deliberate DIFFERENCE from its sibling
 * {@code findPublicationStatusesByWorkflowIds}:
 * <ul>
 *   <li>It selects BOTH {@code p.id} and {@code p.status} - the controller folds the rows into a
 *       {@code publicationId → status} map; a single-column projection would NPE the mapping.</li>
 *   <li>It keys on the publication's OWN {@code p.id} (not {@code p.workflowId}), because the board
 *       rows carry a {@code sourcePublicationId}, not the source workflow id.</li>
 *   <li>It must NOT exclude INACTIVE - the whole point is to DETECT INACTIVE so the board can drop
 *       those apps. A stray {@code <> 'INACTIVE'} guard would hide the very rows we need to see,
 *       silently defeating the filter (unpublished apps would linger on the board).</li>
 * </ul>
 */
@DisplayName("WorkflowPublicationRepository.findStatusesByPublicationIds - query shape")
class WorkflowPublicationRepositoryPublicationStatusQueryShapeTest {

    @Test
    @DisplayName("selects (id, status) by publication id and does NOT exclude INACTIVE")
    void selectsIdAndStatusIncludingInactive() throws NoSuchMethodException {
        Method m = WorkflowPublicationRepository.class.getMethod(
                "findStatusesByPublicationIds", Collection.class);
        Query q = m.getAnnotation(Query.class);
        assertThat(q).as("@Query annotation must be present").isNotNull();

        String value = q.value().replaceAll("\\s+", " ");

        assertThat(value)
                .as("must project both id and status for the (publicationId → status) map")
                .contains("SELECT p.id, p.status");
        assertThat(value)
                .as("must scope to the requested publication ids (keyed by the publication's own id)")
                .contains("p.id IN :publicationIds");
        assertThat(value)
                .as("must NOT filter INACTIVE - detecting INACTIVE is the entire purpose")
                .doesNotContain("INACTIVE");
    }
}

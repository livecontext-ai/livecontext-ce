package com.apimarketplace.publication.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural guard for {@code findPublicationVisibilitiesByWorkflowIds}, the source of the workflow
 * + applications boards' public / private indicator and visibility filter.
 *
 * <p>Load-bearing invariants:
 * <ul>
 *   <li>It selects BOTH {@code p.workflowId} and {@code p.visibility} - the controller folds the
 *       rows into a {@code workflowId → visibility} map; a single-column projection would NPE it.</li>
 *   <li>It keys on the SOURCE {@code p.workflowId} (not {@code p.id}), so a board card looking up by
 *       its workflow id resolves its own publication's visibility.</li>
 *   <li>It MUST exclude INACTIVE - an unpublished workflow has no visibility to surface; a stray
 *       INACTIVE row would mark a withdrawn app public/private and let it slip past the filter.</li>
 * </ul>
 */
@DisplayName("WorkflowPublicationRepository.findPublicationVisibilitiesByWorkflowIds - query shape")
class WorkflowPublicationRepositoryVisibilityQueryShapeTest {

    @Test
    @DisplayName("selects (workflowId, visibility) by workflow id and excludes INACTIVE")
    void selectsWorkflowIdAndVisibilityExcludingInactive() throws NoSuchMethodException {
        Method m = WorkflowPublicationRepository.class.getMethod(
                "findPublicationVisibilitiesByWorkflowIds", Collection.class);
        Query q = m.getAnnotation(Query.class);
        assertThat(q).as("@Query annotation must be present").isNotNull();

        String value = q.value().replaceAll("\\s+", " ");

        assertThat(value)
                .as("must project both workflowId and visibility for the (workflowId → visibility) map")
                .contains("SELECT p.workflowId, p.visibility");
        assertThat(value)
                .as("must scope to the requested workflow ids (keyed by the source workflow id)")
                .contains("p.workflowId IN :workflowIds");
        assertThat(value)
                .as("must exclude INACTIVE - an unpublished workflow has no visibility to surface")
                .contains("p.status <> 'INACTIVE'");
    }
}

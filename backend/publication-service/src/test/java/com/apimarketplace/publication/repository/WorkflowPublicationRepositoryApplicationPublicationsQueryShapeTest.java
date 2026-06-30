package com.apimarketplace.publication.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural guard for {@code findApplicationPublicationsByWorkflowIds}, which feeds the
 * orchestrator applications board the publisher's OWN published-as-application workflows.
 *
 * <p>This query MUST stay in lockstep with the canonical "application" predicate used by
 * {@code /app/applications} ({@link com.apimarketplace.publication.service.PublicationListQueryService#findByScope}
 * with {@code applicationOnly=true}: {@code publication_type='WORKFLOW' AND showcase_interface_id IS NOT NULL},
 * status {@code != 'INACTIVE'}). If it drifts, the board silently disagrees with the page about what
 * an "application" is - the exact inconsistency this feature set out to fix. Load-bearing invariants:
 * <ul>
 *   <li>projects {@code (workflowId, id, status)} - the controller folds rows into a
 *       {@code workflowId → {publicationId, status}} map; a narrower projection breaks the fold;</li>
 *   <li>requires {@code publicationType = 'WORKFLOW'} AND {@code showcaseInterfaceId IS NOT NULL} -
 *       the exact "is an application" definition (drop either and standalone/non-app publications leak
 *       onto the applications board);</li>
 *   <li>excludes {@code INACTIVE} - an unpublished app must never resurface on the board.</li>
 * </ul>
 */
@DisplayName("WorkflowPublicationRepository.findApplicationPublicationsByWorkflowIds - query shape")
class WorkflowPublicationRepositoryApplicationPublicationsQueryShapeTest {

    @Test
    @DisplayName("projects (workflowId, id, status) and applies the exact application predicate (WORKFLOW + showcase, non-INACTIVE)")
    void appliesApplicationPredicate() throws NoSuchMethodException {
        Method m = WorkflowPublicationRepository.class.getMethod(
                "findApplicationPublicationsByWorkflowIds", Collection.class);
        Query q = m.getAnnotation(Query.class);
        assertThat(q).as("@Query annotation must be present").isNotNull();

        String value = q.value().replaceAll("\\s+", " ");

        assertThat(value)
                .as("must project workflowId, publication id and status for the (id → {pubId, status}) map")
                .contains("SELECT p.workflowId, p.id, p.status");
        assertThat(value)
                .as("must scope to the requested workflow ids")
                .contains("p.workflowId IN :workflowIds");
        assertThat(value)
                .as("must restrict to WORKFLOW publications (not standalone AGENT/TABLE/INTERFACE/SKILL)")
                .contains("p.publicationType = 'WORKFLOW'");
        assertThat(value)
                .as("must require a showcase interface - the canonical 'isApplication' definition")
                .contains("p.showcaseInterfaceId IS NOT NULL");
        assertThat(value)
                .as("must exclude INACTIVE rows so an unpublished app never resurfaces on the board")
                .contains("p.status <> 'INACTIVE'");
    }
}

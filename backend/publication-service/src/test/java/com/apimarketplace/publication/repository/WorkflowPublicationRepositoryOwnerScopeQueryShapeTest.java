package com.apimarketplace.publication.repository;

import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.beans.Introspector;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural guard for {@code findOwnerScopeById}, read on EVERY share-token resolution (it decides
 * whether an APPLICATION link still names a publication its owner holds). No test in this module
 * boots JPA, so a typo here would only surface as a failed startup: this pins what can be pinned
 * without a database.
 * <ul>
 *   <li>every alias matches a getter of {@link WorkflowPublicationRepository.OwnerScopeView} (an alias
 *       that matches none leaves that getter null, and a null owner scope reads as "legacy row",
 *       deciding ownership on publisher_id alone);</li>
 *   <li>every selected path is a real field of the entity;</li>
 *   <li>no status filter: an unpublished (INACTIVE) publication still has its owner, and its links
 *       must keep resolving as they did before.</li>
 * </ul>
 */
@DisplayName("WorkflowPublicationRepository.findOwnerScopeById - query shape")
class WorkflowPublicationRepositoryOwnerScopeQueryShapeTest {

    @Test
    @DisplayName("selects exactly the three owner-scope columns, aliased to the projection's getters, by id, with no status filter")
    void queryMatchesProjectionAndEntity() throws Exception {
        Method m = WorkflowPublicationRepository.class.getMethod("findOwnerScopeById", UUID.class);
        String jpql = m.getAnnotation(Query.class).value().replaceAll("\\s+", " ");

        Method[] getters = Arrays.stream(WorkflowPublicationRepository.OwnerScopeView.class.getDeclaredMethods())
                .filter(g -> !g.isSynthetic() && g.getName().startsWith("get")).toArray(Method[]::new);
        for (Method getter : getters) {
            String property = Introspector.decapitalize(getter.getName().substring(3));
            assertThat(jpql).as("alias for %s", getter.getName()).contains("p." + property + " AS " + property);
            assertThat(Arrays.stream(WorkflowPublicationEntity.class.getDeclaredFields()).map(f -> f.getName()))
                    .as("entity field %s", property).contains(property);
        }
        assertThat(getters).hasSize(3);
        assertThat(jpql).contains("FROM WorkflowPublicationEntity p WHERE p.id = :id");
        assertThat(jpql).doesNotContainIgnoringCase("status");
    }
}

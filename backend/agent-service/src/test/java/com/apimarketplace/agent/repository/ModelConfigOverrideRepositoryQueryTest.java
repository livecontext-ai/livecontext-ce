package com.apimarketplace.agent.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guardrail on the query the model replacement resolver reads (same reflection approach as
 * {@link AgentRepositoryResetQueryTest}: agent-service has no JPA test slice). On a CE a bundle
 * DEPRECATES a model the cloud stopped shipping without touching {@code enabled}; if this query
 * lost its deprecated branch, stored agents on that model would keep sending it and the cloud
 * relay would refuse every run instead of the resolver swapping it (V533).
 */
@DisplayName("ModelConfigOverrideRepository - resolver query")
class ModelConfigOverrideRepositoryQueryTest {

    @Test
    @DisplayName("findDisabledOrDeprecated selects disabled rows AND deprecated rows")
    void selectsDisabledAndDeprecated() throws NoSuchMethodException {
        Query q = ModelConfigOverrideRepository.class.getMethod("findDisabledOrDeprecated")
                .getAnnotation(Query.class);

        assertThat(q).isNotNull();
        assertThat(q.value())
                .contains("m.enabled = false")
                .contains("OR m.deprecatedAt IS NOT NULL");
    }
}

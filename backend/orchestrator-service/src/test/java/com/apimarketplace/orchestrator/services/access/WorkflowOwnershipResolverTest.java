package com.apimarketplace.orchestrator.services.access;

import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("WorkflowOwnershipResolver")
class WorkflowOwnershipResolverTest {

    private WorkflowRepository repo;
    private WorkflowOwnershipResolver resolver;

    @BeforeEach
    void setUp() {
        repo = mock(WorkflowRepository.class);
        resolver = new WorkflowOwnershipResolver(repo);
    }

    @Test
    @DisplayName("handledTypes contains exactly 'workflow'")
    void handledTypesIsWorkflowOnly() {
        assertThat(resolver.handledTypes()).containsExactly("workflow");
    }

    @Test
    @DisplayName("Resolves to org id when workflow exists and has organization_id")
    void resolvesExistingOrgWorkflow() {
        UUID id = UUID.randomUUID();
        when(repo.findOrganizationIdById(id)).thenReturn(Optional.of("org-42"));

        Optional<String> result = resolver.resolveOwnerOrgId("workflow", id.toString());

        assertThat(result).contains("org-42");
    }

    @Test
    @DisplayName("Returns empty when repository returns empty (personal workflow or not found)")
    void returnsEmptyWhenPersonalOrMissing() {
        UUID id = UUID.randomUUID();
        when(repo.findOrganizationIdById(id)).thenReturn(Optional.empty());

        assertThat(resolver.resolveOwnerOrgId("workflow", id.toString())).isEmpty();
    }

    @Test
    @DisplayName("Returns empty for non-handled resource type without touching repo")
    void returnsEmptyForOtherTypes() {
        assertThat(resolver.resolveOwnerOrgId("agent", UUID.randomUUID().toString())).isEmpty();
        assertThat(resolver.resolveOwnerOrgId("datasource", UUID.randomUUID().toString())).isEmpty();
        assertThat(resolver.resolveOwnerOrgId("", "anything")).isEmpty();
        verifyNoInteractions(repo);
    }

    @Test
    @DisplayName("Returns empty for null arguments without touching repo")
    void returnsEmptyForNulls() {
        assertThat(resolver.resolveOwnerOrgId(null, "x")).isEmpty();
        assertThat(resolver.resolveOwnerOrgId("workflow", null)).isEmpty();
        verifyNoInteractions(repo);
    }

    @Test
    @DisplayName("Returns empty for malformed UUID without touching repo")
    void returnsEmptyForMalformedUuid() {
        assertThat(resolver.resolveOwnerOrgId("workflow", "not-a-uuid")).isEmpty();
        verifyNoInteractions(repo);
    }
}

package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.AgentExecutionEntity;
import com.apimarketplace.agent.repository.AgentExecutionIterationRepository;
import com.apimarketplace.agent.repository.AgentExecutionMessageRepository;
import com.apimarketplace.agent.repository.AgentExecutionRepository;
import com.apimarketplace.agent.repository.AgentExecutionToolCallRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Round-8 regression guard for the deleted 2-arg {@code getAgentExecutions}
 * overload. Post-V263 the 2-arg form (defaulting orgId to null) was deleted
 * because it routed through the now-removed personal-scope IS NULL branch.
 *
 * <p>The legacy {@code AgentMetricsQueryServiceOrgScopeTest} is @Disabled +
 * body-commented because it exercised the deleted finders; this new class
 * carries the live regression coverage for the surviving 3-arg signature.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentMetricsQueryService.getAgentExecutions - post-V263 strict-org")
class AgentMetricsQueryServiceGetAgentExecutionsTest {

    private static final UUID AGENT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final String TENANT_ID = "tenant-42";
    private static final String ORG_ID = "org-acme";

    @Mock private AgentExecutionRepository executionRepository;
    @Mock private AgentExecutionMessageRepository messageRepository;
    @Mock private AgentExecutionToolCallRepository toolCallRepository;
    @Mock private AgentExecutionIterationRepository iterationRepository;
    @Mock private EntityManager entityManager;

    private AgentMetricsQueryService service;

    @BeforeEach
    void setUp() {
        service = new AgentMetricsQueryService(
            executionRepository, messageRepository, toolCallRepository,
            iterationRepository, entityManager);
    }

    @Test
    @DisplayName("non-null orgId → strict-org finder")
    void orgIdRoutesToStrictFinder() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<AgentExecutionEntity> orgPage = new PageImpl<>(List.of(new AgentExecutionEntity()));
        when(executionRepository.findByAgentEntityIdAndOrganizationIdStrict(AGENT_ID, ORG_ID, pageable))
            .thenReturn(orgPage);

        Page<AgentExecutionEntity> result = service.getAgentExecutions(AGENT_ID, TENANT_ID, ORG_ID, pageable);

        assertThat(result).isSameAs(orgPage);
        verify(executionRepository).findByAgentEntityIdAndOrganizationIdStrict(AGENT_ID, ORG_ID, pageable);
    }

    @Test
    @DisplayName("null orgId → IllegalArgumentException, no repository call")
    void nullOrgIdRejected() {
        Pageable pageable = PageRequest.of(0, 20);

        assertThatThrownBy(() -> service.getAgentExecutions(AGENT_ID, TENANT_ID, null, pageable))
            .isInstanceOf(IllegalArgumentException.class);

        verify(executionRepository, never())
            .findByAgentEntityIdAndOrganizationIdStrict(any(), any(), any());
    }

    @Test
    @DisplayName("blank orgId → IllegalArgumentException, no repository call")
    void blankOrgIdRejected() {
        Pageable pageable = PageRequest.of(0, 20);

        assertThatThrownBy(() -> service.getAgentExecutions(AGENT_ID, TENANT_ID, "   ", pageable))
            .isInstanceOf(IllegalArgumentException.class);

        verify(executionRepository, never())
            .findByAgentEntityIdAndOrganizationIdStrict(any(), any(), any());
    }
}

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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Read-side coverage for {@link AgentMetricsQueryService#getSubAgentExecutionsForConversation}.
 *
 * <p>Sub-agent executions are persisted under their own conversationId but carry
 * {@code parent_conversation_id} = the spawning conversation. This reader is what
 * lets a conversation observability panel surface those spawned executions. Like
 * every other observability read post-V261 it MUST be org-strict: a non-blank
 * organizationId routes to the strict finder; a null/blank one is rejected so a
 * caller can never read across workspaces.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentMetricsQueryService.getSubAgentExecutionsForConversation - org-strict")
class AgentMetricsQueryServiceSubAgentExecutionsTest {

    private static final String PARENT_CONV = "parent-conv-42";
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
    @DisplayName("non-blank orgId → org-strict finder with the parent conversation id")
    void orgScopeRoutesToStrictFinder() {
        List<AgentExecutionEntity> orgRows = List.of(new AgentExecutionEntity());
        when(executionRepository
                .findByParentConversationIdAndOrganizationIdStrictOrderByStartedAtDesc(PARENT_CONV, ORG_ID))
            .thenReturn(orgRows);

        List<AgentExecutionEntity> result =
            service.getSubAgentExecutionsForConversation(PARENT_CONV, ORG_ID);

        assertThat(result).isSameAs(orgRows);
        verify(executionRepository)
            .findByParentConversationIdAndOrganizationIdStrictOrderByStartedAtDesc(PARENT_CONV, ORG_ID);
    }

    @Test
    @DisplayName("null orgId → IllegalArgumentException, no repository call")
    void nullOrgIdRejected() {
        assertThatThrownBy(() -> service.getSubAgentExecutionsForConversation(PARENT_CONV, null))
            .isInstanceOf(IllegalArgumentException.class);

        verify(executionRepository, never())
            .findByParentConversationIdAndOrganizationIdStrictOrderByStartedAtDesc(any(), any());
    }

    @Test
    @DisplayName("blank orgId → IllegalArgumentException, no repository call")
    void blankOrgIdRejected() {
        assertThatThrownBy(() -> service.getSubAgentExecutionsForConversation(PARENT_CONV, "   "))
            .isInstanceOf(IllegalArgumentException.class);

        verify(executionRepository, never())
            .findByParentConversationIdAndOrganizationIdStrictOrderByStartedAtDesc(any(), any());
    }
}

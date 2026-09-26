package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.domain.AgentExecutionEntity;
import com.apimarketplace.agent.domain.AgentExecutionMessageEntity;
import com.apimarketplace.agent.domain.AgentExecutionToolCallEntity;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.service.AgentMetricsQueryService;
import com.apimarketplace.agent.service.FleetStatsService;
import com.apimarketplace.agent.service.TraceContentLoader;
import com.apimarketplace.common.web.TenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.domain.PageImpl;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The trace endpoints read a long row's full text back AFTER the page query returned, never
 * from inside its transaction, and only for a caller the scope check let through.
 */
@DisplayName("AgentMetricsController: trace rows are read back after the query")
class AgentMetricsControllerTraceReadBackTest {

    private static final UUID EXEC = UUID.randomUUID();

    private final AgentMetricsQueryService queryService = mock(AgentMetricsQueryService.class);
    private final TenantResolver tenantResolver = mock(TenantResolver.class);
    private final TraceContentLoader loader = mock(TraceContentLoader.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private AgentMetricsController controller;

    @BeforeEach
    void setUp() {
        controller = new AgentMetricsController(queryService, mock(AgentRepository.class), tenantResolver,
            mock(FleetStatsService.class));
        controller.setTraceContentLoader(loader);
        when(tenantResolver.resolve(request)).thenReturn("tenant-1");
        when(tenantResolver.resolveOrgId(request)).thenReturn("org-1");
    }

    @Test
    @DisplayName("BUG: the conversation page is read back, after the query returned it")
    void conversationIsReadBackAfterTheQuery() {
        List<AgentExecutionMessageEntity> rows = List.of(new AgentExecutionMessageEntity());
        when(queryService.getExecutionForScope(EXEC, "tenant-1", "org-1")).thenReturn(Optional.of(new AgentExecutionEntity()));
        when(queryService.getConversationPaged(eq(EXEC), any())).thenReturn(new PageImpl<>(rows));

        controller.getConversation(request, EXEC, 0, 30);

        InOrder order = inOrder(queryService, loader);
        order.verify(queryService).getConversationPaged(eq(EXEC), any());
        order.verify(loader).readBackMessages(rows);
    }

    @Test
    @DisplayName("the tool-call page is read back the same way")
    void toolCallsAreReadBack() {
        List<AgentExecutionToolCallEntity> rows = List.of(new AgentExecutionToolCallEntity());
        when(queryService.getExecutionForScope(EXEC, "tenant-1", "org-1")).thenReturn(Optional.of(new AgentExecutionEntity()));
        when(queryService.getToolCallsPaged(eq(EXEC), any())).thenReturn(new PageImpl<>(rows));

        controller.getToolCalls(request, EXEC, 0, 30);

        verify(loader).readBackToolCalls(rows);
    }

    @Test
    @DisplayName("an execution outside the caller's scope reads nothing back from storage")
    void outOfScopeReadsNothing() {
        when(queryService.getExecutionForScope(EXEC, "tenant-1", "org-1")).thenReturn(Optional.empty());

        controller.getConversation(request, EXEC, 0, 30);

        verify(loader, never()).readBackMessages(any());
    }
}

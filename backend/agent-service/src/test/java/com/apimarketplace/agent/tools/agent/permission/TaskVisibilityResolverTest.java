package com.apimarketplace.agent.tools.agent.permission;

import com.apimarketplace.agent.domain.AgentTaskEntity;
import com.apimarketplace.agent.repository.AgentTaskRepository;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.agent.permission.TaskVisibilityResolver.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TaskVisibilityResolver")
class TaskVisibilityResolverTest {

    private static final String TENANT_ID = "tenant-1";
    private static final String ORG_ID = "org-1";
    private static final UUID CALLER_AGENT_ID = UUID.randomUUID();
    private static final UUID TASK_ID = UUID.randomUUID();

    @Mock private AgentTaskRepository taskRepository;

    private TaskVisibilityResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new TaskVisibilityResolver(taskRepository);
    }

    @Test
    @DisplayName("namespaced empty allowedAgentIds does not grant god task visibility")
    void namespacedEmptyAllowedAgentIdsDoesNotGrantGodTaskVisibility() {
        AgentTaskEntity task = taskOwnedByDifferentAgents();
        when(taskRepository.findByIdAndOrganizationIdStrict(TASK_ID, ORG_ID)).thenReturn(Optional.of(task));

        Role role = resolver.resolveRole(
            CALLER_AGENT_ID,
            TASK_ID,
            TENANT_ID,
            context(Map.of("__allowedAgentIds__", List.of())));

        assertThat(role).isEqualTo(Role.NONE);
    }

    private ToolExecutionContext context(Map<String, Object> credentials) {
        return new ToolExecutionContext(
            TENANT_ID, credentials, Map.of(), Set.of(), null, null, ORG_ID, "MEMBER");
    }

    private AgentTaskEntity taskOwnedByDifferentAgents() {
        AgentTaskEntity task = new AgentTaskEntity();
        task.setId(TASK_ID);
        task.setTenantId(TENANT_ID);
        task.setOrganizationId(ORG_ID);
        task.setCreatedByAgentId(UUID.randomUUID());
        task.setReviewerAgentId(UUID.randomUUID());
        return task;
    }
}

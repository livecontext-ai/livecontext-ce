package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.client.dto.execution.AgentExecutionRequestDto;
import com.apimarketplace.agent.client.dto.execution.AgentExecutionResponseDto;
import com.apimarketplace.orchestrator.domain.workflow.Agent;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.apimarketplace.orchestrator.services.agent.AgentConfigResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AgentNode access-mode credentials")
@ExtendWith(MockitoExtension.class)
class AgentNodeAccessModeCredentialsTest {

    @Mock private WorkflowPlan plan;
    @Mock private AgentClient agentClient;
    @Mock private AgentConfigResolver agentConfigResolver;

    @Test
    @DisplayName("toolsConfig access modes are forwarded in remote agent credentials")
    void toolsConfigAccessModesAreForwardedInCredentials() {
        String entityId = UUID.randomUUID().toString();
        Map<String, Object> toolsConfig = new HashMap<>();
        toolsConfig.put("tables", List.of("table-1"));
        toolsConfig.put("agents", List.of("agent-uuid"));
        toolsConfig.put("tableAccessMode", "read");
        toolsConfig.put("workflowAccessMode", "read");
        toolsConfig.put("interfaceAccessMode", "write");
        toolsConfig.put("agentAccessMode", "read");
        toolsConfig.put("applicationAccessMode", "write");
        toolsConfig.put("skillAccessMode", "read");
        toolsConfig.put("fileAccessMode", "read");
        when(agentConfigResolver.getToolsConfig(eq(entityId), eq("tenant-1"), isNull()))
            .thenReturn(toolsConfig);
        when(agentClient.executeAgent(any(AgentExecutionRequestDto.class)))
            .thenReturn(successResponse());

        AgentNode node = new AgentNode("agent:test", createAgent(entityId));
        node.acceptServices(ServiceRegistry.builder()
            .agentClient(agentClient)
            .agentConfigResolver(agentConfigResolver)
            .build());

        node.execute(context());

        ArgumentCaptor<AgentExecutionRequestDto> captor = ArgumentCaptor.forClass(AgentExecutionRequestDto.class);
        verify(agentClient).executeAgent(captor.capture());

        Map<String, Object> credentials = captor.getValue().credentials();
        assertThat(credentials).containsEntry("allowedTableIds", List.of("table-1"));
        assertThat(credentials).containsEntry("allowedAgentIds", List.of("agent-uuid"));
        assertThat(credentials).containsEntry("tableAccessMode", "read");
        assertThat(credentials).containsEntry("workflowAccessMode", "read");
        assertThat(credentials).containsEntry("interfaceAccessMode", "write");
        assertThat(credentials).containsEntry("agentAccessMode", "read");
        assertThat(credentials).containsEntry("applicationAccessMode", "write");
        assertThat(credentials).containsEntry("skillAccessMode", "read");
        assertThat(credentials).containsEntry("fileAccessMode", "read");
    }

    @Test
    @DisplayName("grant='all' + empty list → __allowedWorkflowIds__ OMITTED (unrestricted) on the workflow-node path")
    void grantAllOmitsCredentialSoAgentIsUnrestricted() {
        // The bug: a grant:'all' + empty-list agent was BLOCKED on every family when run
        // as a workflow node, because the emit wrote allowed<Family>Ids=[] (deny-all)
        // ignoring the grant. With the omission fix, grant='all' OMITS the credential
        // entirely → the CRUD modules' absent-key branch = unrestricted access.
        String entityId = UUID.randomUUID().toString();
        Map<String, Object> toolsConfig = new HashMap<>();
        // All five families granted 'all' with EMPTY lists (the V163 self-describing shape).
        toolsConfig.put("workflows", List.of());
        toolsConfig.put("workflowsGrant", "all");
        toolsConfig.put("tables", List.of());
        toolsConfig.put("tablesGrant", "all");
        when(agentConfigResolver.getToolsConfig(eq(entityId), eq("tenant-1"), isNull()))
            .thenReturn(toolsConfig);
        when(agentClient.executeAgent(any(AgentExecutionRequestDto.class)))
            .thenReturn(successResponse());

        AgentNode node = new AgentNode("agent:test", createAgent(entityId));
        node.acceptServices(ServiceRegistry.builder()
            .agentClient(agentClient)
            .agentConfigResolver(agentConfigResolver)
            .build());

        node.execute(context());

        ArgumentCaptor<AgentExecutionRequestDto> captor = ArgumentCaptor.forClass(AgentExecutionRequestDto.class);
        verify(agentClient).executeAgent(captor.capture());
        Map<String, Object> credentials = captor.getValue().credentials();

        // grant='all' families: the credential MUST be absent (omitted → unrestricted).
        assertThat(credentials).doesNotContainKey("allowedWorkflowIds");
        assertThat(credentials).doesNotContainKey("allowedTableIds");
    }

    @Test
    @DisplayName("grant='none' → [], grant='custom'+list → the list, on the workflow-node path")
    void grantNoneEmitsEmptyAndCustomEmitsList() {
        String entityId = UUID.randomUUID().toString();
        Map<String, Object> toolsConfig = new HashMap<>();
        // workflows: none → []
        toolsConfig.put("workflows", List.of());
        toolsConfig.put("workflowsGrant", "none");
        // tables: custom with a list → that list
        toolsConfig.put("tables", List.of("table-1", "table-2"));
        toolsConfig.put("tablesGrant", "custom");
        when(agentConfigResolver.getToolsConfig(eq(entityId), eq("tenant-1"), isNull()))
            .thenReturn(toolsConfig);
        when(agentClient.executeAgent(any(AgentExecutionRequestDto.class)))
            .thenReturn(successResponse());

        AgentNode node = new AgentNode("agent:test", createAgent(entityId));
        node.acceptServices(ServiceRegistry.builder()
            .agentClient(agentClient)
            .agentConfigResolver(agentConfigResolver)
            .build());

        node.execute(context());

        ArgumentCaptor<AgentExecutionRequestDto> captor = ArgumentCaptor.forClass(AgentExecutionRequestDto.class);
        verify(agentClient).executeAgent(captor.capture());
        Map<String, Object> credentials = captor.getValue().credentials();

        // grant='none' → deny-all empty list (present, not omitted).
        assertThat(credentials).containsEntry("allowedWorkflowIds", List.of());
        // grant='custom' → exactly the configured list.
        assertThat(credentials).containsEntry("allowedTableIds", List.of("table-1", "table-2"));
    }

    @Test
    @DisplayName("grant='bogus' (unknown) → [] deny-by-default even with a stale list - must NOT fail OPEN")
    void unknownGrantFailsClosedOnWorkflowNodePath() {
        // Defense-in-depth: a junk grant that somehow escaped normalize must NOT trust the id
        // list behind it. Pre-fix the emit wrote the raw list (granting those ids) for any
        // non-'all' grant; post-fix an unrecognised grant → [] (deny), like 'none'.
        String entityId = UUID.randomUUID().toString();
        Map<String, Object> toolsConfig = new HashMap<>();
        toolsConfig.put("workflows", List.of("wf-stale")); // stale list behind a junk grant
        toolsConfig.put("workflowsGrant", "bogus");
        when(agentConfigResolver.getToolsConfig(eq(entityId), eq("tenant-1"), isNull()))
            .thenReturn(toolsConfig);
        when(agentClient.executeAgent(any(AgentExecutionRequestDto.class)))
            .thenReturn(successResponse());

        AgentNode node = new AgentNode("agent:test", createAgent(entityId));
        node.acceptServices(ServiceRegistry.builder()
            .agentClient(agentClient)
            .agentConfigResolver(agentConfigResolver)
            .build());

        node.execute(context());

        ArgumentCaptor<AgentExecutionRequestDto> captor = ArgumentCaptor.forClass(AgentExecutionRequestDto.class);
        verify(agentClient).executeAgent(captor.capture());
        Map<String, Object> credentials = captor.getValue().credentials();

        // Deny: [] (present, not omitted → not unrestricted), and NOT the stale ['wf-stale'].
        assertThat(credentials).containsEntry("allowedWorkflowIds", List.of());
    }

    @Test
    @DisplayName("numeric table allow-list (tables:[209]) is stringified into remote agent credentials")
    void numericTableAllowlistIsStringifiedInCredentials() {
        // Regression: an agent created via MCP stores its table allow-list with the
        // native JSON type, so `tables:[209]` is a List<Integer>. Tool modules compare
        // with `.contains(String.valueOf(id))`, and a List<Integer> never contains a
        // String → silent "This table is not in your approved table list." The workflow
        // agent-node credential path must normalize every element to String.
        String entityId = UUID.randomUUID().toString();
        Map<String, Object> toolsConfig = new HashMap<>();
        toolsConfig.put("tables", List.of(209, 42));
        when(agentConfigResolver.getToolsConfig(eq(entityId), eq("tenant-1"), isNull()))
            .thenReturn(toolsConfig);
        when(agentClient.executeAgent(any(AgentExecutionRequestDto.class)))
            .thenReturn(successResponse());

        AgentNode node = new AgentNode("agent:test", createAgent(entityId));
        node.acceptServices(ServiceRegistry.builder()
            .agentClient(agentClient)
            .agentConfigResolver(agentConfigResolver)
            .build());

        node.execute(context());

        ArgumentCaptor<AgentExecutionRequestDto> captor = ArgumentCaptor.forClass(AgentExecutionRequestDto.class);
        verify(agentClient).executeAgent(captor.capture());

        Map<String, Object> credentials = captor.getValue().credentials();
        // Stringified, not the raw List<Integer>.
        assertThat(credentials).containsEntry("allowedTableIds", List.of("209", "42"));
    }

    private ExecutionContext context() {
        return ExecutionContext.create(
            "run-1",
            "workflow-run-1",
            "tenant-1",
            "item-1",
            0,
            Map.of("user_input", "Hello"),
            plan
        );
    }

    private AgentExecutionResponseDto successResponse() {
        return new AgentExecutionResponseDto(
            true,
            "OK",
            "OK",
            List.of(),
            1,
            Map.of(),
            null,
            100L,
            "openai",
            "gpt-4o",
            List.of(),
            null,
            Map.of(),
            List.of(),
            List.of(),
            List.of(),
            null,
            null,
            null
        );
    }

    private Agent createAgent(String agentConfigId) {
        return new Agent(
            "agent-1",
            "agent",
            "Test Agent",
            agentConfigId,
            false,
            "openai",
            "gpt-4o",
            null,
            "Do something",
            0.7,
            4096,
            10,
            5,
            List.of(),
            null,
            Map.of(),
            List.of(),
            null,
            List.of(),
            null,
            null
        );
    }
}

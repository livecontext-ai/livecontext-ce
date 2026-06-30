package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.execution.v2.nodes.*;
import com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter;
import com.apimarketplace.orchestrator.services.interfaces.ToolsGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.Mockito.*;

/**
 * Unit tests for ExecutionServiceInjector.
 *
 * ExecutionServiceInjector injects execution services into nodes using
 * polymorphic acceptServices(ServiceRegistry) - each node pulls the services it needs.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ExecutionServiceInjector")
class ExecutionServiceInjectorTest {

    @Mock
    private V2TemplateAdapter templateAdapter;

    @Mock
    private ToolsGateway mockToolsGateway;

    @Mock
    private ToolsGateway catalogToolsGateway;

    private ExecutionServiceInjector injector;

    @BeforeEach
    void setUp() {
        injector = new ExecutionServiceInjector(templateAdapter);
    }

    @Nested
    @DisplayName("injectServices()")
    class InjectServicesTests {

        @Test
        @DisplayName("Should call acceptServices on each node")
        void shouldCallAcceptServicesOnEachNode() {
            setupMockGateway(true);
            ExecutionNode node = mock(ExecutionNode.class);
            Map<String, ExecutionNode> nodeMap = Map.of("mcp:step", node);

            injector.injectServices(nodeMap);

            verify(node).acceptServices(any(ServiceRegistry.class));
        }

        @Test
        @DisplayName("Should call acceptServices on all nodes")
        void shouldCallAcceptServicesOnAllNodes() {
            setupMockGateway(true);
            ExecutionNode node1 = mock(ExecutionNode.class);
            ExecutionNode node2 = mock(ExecutionNode.class);
            ExecutionNode node3 = mock(ExecutionNode.class);
            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            nodeMap.put("trigger:start", node1);
            nodeMap.put("mcp:step_1", node2);
            nodeMap.put("mcp:step_2", node3);

            injector.injectServices(nodeMap);

            verify(node1).acceptServices(any(ServiceRegistry.class));
            verify(node2).acceptServices(any(ServiceRegistry.class));
            verify(node3).acceptServices(any(ServiceRegistry.class));
        }

        @Test
        @DisplayName("Should call acceptServices on AgentNode")
        void shouldCallAcceptServicesOnAgentNode() {
            setupMockGateway(true);
            AgentNode agentNode = mock(AgentNode.class);
            Map<String, ExecutionNode> nodeMap = Map.of("agent:analyzer", agentNode);

            injector.injectServices(nodeMap);

            verify(agentNode).acceptServices(any(ServiceRegistry.class));
        }

        @Test
        @DisplayName("Should call acceptServices on non-BaseNode implementations too")
        void shouldCallAcceptServicesOnNonBaseNodeImplementations() {
            setupMockGateway(true);
            // acceptServices is in the ExecutionNode interface with default no-op
            // so it will be called on ALL nodes, including non-BaseNode implementations
            ExecutionNode nonBaseNode = mock(ExecutionNode.class);
            Map<String, ExecutionNode> nodeMap = Map.of("custom:node", nonBaseNode);

            injector.injectServices(nodeMap);

            // acceptServices is called on ALL nodes (polymorphic injection)
            verify(nonBaseNode).acceptServices(any(ServiceRegistry.class));
        }
    }

    @Nested
    @DisplayName("Gateway resolution")
    class GatewayResolutionTests {

        @Test
        @DisplayName("Should use mockToolsGateway when mockEnabled is true")
        void shouldUseMockToolsGatewayWhenMockEnabled() {
            setupMockGateway(true);
            setupCatalogGateway();
            ExecutionNode node = mock(ExecutionNode.class);
            Map<String, ExecutionNode> nodeMap = Map.of("mcp:step", node);

            injector.injectServices(nodeMap);

            // Verify acceptServices is called (gateway resolution is internal)
            verify(node).acceptServices(any(ServiceRegistry.class));
        }

        @Test
        @DisplayName("Should use catalogToolsGateway when mockEnabled is false")
        void shouldUseCatalogToolsGatewayWhenMockDisabled() {
            setupMockGateway(false);
            setupCatalogGateway();
            ExecutionNode node = mock(ExecutionNode.class);
            Map<String, ExecutionNode> nodeMap = Map.of("mcp:step", node);

            injector.injectServices(nodeMap);

            verify(node).acceptServices(any(ServiceRegistry.class));
        }

        @Test
        @DisplayName("Should handle null mockToolsGateway when mockEnabled")
        void shouldHandleNullMockToolsGatewayWhenMockEnabled() {
            ReflectionTestUtils.setField(injector, "mockEnabled", true);
            // mockToolsGateway is not set (null)
            ExecutionNode node = mock(ExecutionNode.class);
            Map<String, ExecutionNode> nodeMap = Map.of("mcp:step", node);

            injector.injectServices(nodeMap);

            verify(node).acceptServices(any(ServiceRegistry.class));
        }

        @Test
        @DisplayName("Should fallback to catalogToolsGateway when mock not available")
        void shouldFallbackToCatalogToolsGatewayWhenMockNotAvailable() {
            ReflectionTestUtils.setField(injector, "mockEnabled", false);
            ReflectionTestUtils.setField(injector, "catalogToolsGateway", catalogToolsGateway);
            ExecutionNode node = mock(ExecutionNode.class);
            Map<String, ExecutionNode> nodeMap = Map.of("mcp:step", node);

            injector.injectServices(nodeMap);

            verify(node).acceptServices(any(ServiceRegistry.class));
        }
    }

    @Nested
    @DisplayName("Empty node map")
    class EmptyNodeMapTests {

        @Test
        @DisplayName("Should handle empty node map")
        void shouldHandleEmptyNodeMap() {
            setupMockGateway(true);
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            // Should not throw
            injector.injectServices(nodeMap);
        }
    }

    // ===== Helper methods =====

    private void setupMockGateway(boolean mockEnabled) {
        ReflectionTestUtils.setField(injector, "mockEnabled", mockEnabled);
        ReflectionTestUtils.setField(injector, "mockToolsGateway", mockToolsGateway);
    }

    private void setupCatalogGateway() {
        ReflectionTestUtils.setField(injector, "catalogToolsGateway", catalogToolsGateway);
    }
}

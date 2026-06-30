package com.apimarketplace.orchestrator.controllers.agent;

import com.apimarketplace.agent.registry.AgentToolRegistry;
import com.apimarketplace.agent.tools.ToolsRegistrationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Gating contract for the orchestrator-side {@link AgentToolsController}.
 *
 * <p>The bean carries
 * {@code @ConditionalOnProperty(name = "deployment.mode", havingValue = "microservice", matchIfMissing = true)}.
 * conversation-service hits these orchestrator endpoints ONLY in the split (microservice)
 * topology; in the monolith the agent-service controller handles tool discovery/execution
 * directly, so the orchestrator copy MUST stay out of the context to avoid a duplicate
 * {@code /api/agent-tools} mapping. This test pins both branches of the condition.
 *
 * <p>The two constructor dependencies are mocked so only the class-level {@code @Conditional}
 * decides whether the bean is registered.
 */
@DisplayName("AgentToolsController (orchestrator) deployment.mode gating")
class AgentToolsControllerDeploymentModeGatingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(AgentToolRegistry.class, () -> mock(AgentToolRegistry.class))
            .withBean(ToolsRegistrationService.class, () -> mock(ToolsRegistrationService.class))
            .withUserConfiguration(AgentToolsController.class);

    @Test
    @DisplayName("Wired when deployment.mode=microservice (the split topology)")
    void wiredInMicroserviceMode() {
        runner.withPropertyValues("deployment.mode=microservice")
                .run(ctx -> assertThat(ctx).hasSingleBean(AgentToolsController.class));
    }

    @Test
    @DisplayName("NOT wired when deployment.mode=monolith - agent-service controller owns the path")
    void notWiredInMonolithMode() {
        runner.withPropertyValues("deployment.mode=monolith")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(AgentToolsController.class));
    }

    @Test
    @DisplayName("Wired when deployment.mode is absent (matchIfMissing=true default)")
    void wiredWhenPropertyMissing() {
        runner.run(ctx -> assertThat(ctx).hasSingleBean(AgentToolsController.class));
    }
}

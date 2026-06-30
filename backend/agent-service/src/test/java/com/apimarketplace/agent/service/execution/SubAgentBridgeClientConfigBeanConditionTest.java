package com.apimarketplace.agent.service.execution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gating contract for {@link SubAgentBridgeClientConfig#subAgentBridgeClient(String)}.
 *
 * <p>The bean is guarded by
 * {@code @ConditionalOnProperty(name = "conversation.bridge.enabled", havingValue = "true")}.
 * It must only be wired when the bridge feature flag is explicitly {@code true}; any other
 * value, including the property being absent entirely, must leave the bean out of the context
 * so a service without a bridge never dispatches sub-agents over HTTP.
 */
@DisplayName("SubAgentBridgeClientConfig bean gating")
class SubAgentBridgeClientConfigBeanConditionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SubAgentBridgeClientConfig.class);

    @Test
    @DisplayName("bean is absent when conversation.bridge.enabled is not set")
    void beanAbsentWhenPropertyMissing() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(SubAgentBridgeClient.class));
    }

    @Test
    @DisplayName("bean is absent when conversation.bridge.enabled=false")
    void beanAbsentWhenDisabled() {
        runner.withPropertyValues("conversation.bridge.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(SubAgentBridgeClient.class));
    }

    @Test
    @DisplayName("bean is absent when conversation.bridge.enabled has a non-true value")
    void beanAbsentWhenNonTrueValue() {
        runner.withPropertyValues("conversation.bridge.enabled=yes")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(SubAgentBridgeClient.class));
    }

    @Test
    @DisplayName("bean is wired when conversation.bridge.enabled=true")
    void beanPresentWhenEnabled() {
        runner.withPropertyValues("conversation.bridge.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(SubAgentBridgeClient.class));
    }
}

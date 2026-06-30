package com.apimarketplace.agent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link AgentScalingConfig} - verifies default values match the specification.
 */
@DisplayName("AgentScalingConfig")
class AgentScalingConfigTest {

    @Test
    @DisplayName("Queue is disabled by default")
    void queueDisabledByDefault() {
        AgentScalingConfig config = new AgentScalingConfig();
        assertThat(config.getQueue().isEnabled()).isFalse();
    }

    @Test
    @DisplayName("Agent pool size defaults to 8")
    void agentPoolSizeDefault() {
        AgentScalingConfig config = new AgentScalingConfig();
        assertThat(config.getWorker().getAgentPoolSize()).isEqualTo(8);
    }

    @Test
    @DisplayName("Classify pool size defaults to 4")
    void classifyPoolSizeDefault() {
        AgentScalingConfig config = new AgentScalingConfig();
        assertThat(config.getWorker().getClassifyPoolSize()).isEqualTo(4);
    }

    @Test
    @DisplayName("Guardrail pool size defaults to 4")
    void guardrailPoolSizeDefault() {
        AgentScalingConfig config = new AgentScalingConfig();
        assertThat(config.getWorker().getGuardrailPoolSize()).isEqualTo(4);
    }

    @Test
    @DisplayName("Poll interval defaults to 100ms")
    void pollIntervalDefault() {
        AgentScalingConfig config = new AgentScalingConfig();
        assertThat(config.getConsumer().getPollIntervalMs()).isEqualTo(100);
    }

    @Test
    @DisplayName("Worker pool sizes are configurable via setters")
    void workerPoolSizesConfigurable() {
        AgentScalingConfig config = new AgentScalingConfig();
        config.getWorker().setAgentPoolSize(16);
        config.getWorker().setClassifyPoolSize(32);
        config.getWorker().setGuardrailPoolSize(32);

        assertThat(config.getWorker().getAgentPoolSize()).isEqualTo(16);
        assertThat(config.getWorker().getClassifyPoolSize()).isEqualTo(32);
        assertThat(config.getWorker().getGuardrailPoolSize()).isEqualTo(32);
    }

    @Test
    @DisplayName("Queue enabled is configurable via setter")
    void queueEnabledConfigurable() {
        AgentScalingConfig config = new AgentScalingConfig();
        config.getQueue().setEnabled(true);
        assertThat(config.getQueue().isEnabled()).isTrue();
    }

    @Test
    @DisplayName("Poll interval is configurable via setter")
    void pollIntervalConfigurable() {
        AgentScalingConfig config = new AgentScalingConfig();
        config.getConsumer().setPollIntervalMs(500);
        assertThat(config.getConsumer().getPollIntervalMs()).isEqualTo(500);
    }
}

package com.apimarketplace.agent.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for agent-service horizontal scaling.
 * Controls queue-based async execution and worker pool sizing.
 *
 * <p>All properties are under the {@code scaling.agent} prefix and are disabled by default.
 * Enable with {@code scaling.agent.queue.enabled=true}.
 *
 * <p>Config path: scaling.agent
 */
@Configuration
@ConfigurationProperties(prefix = "scaling.agent")
public class AgentScalingConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentScalingConfig.class);

    private final Queue queue = new Queue();
    private final Worker worker = new Worker();
    private final Consumer consumer = new Consumer();

    public Queue getQueue() { return queue; }
    public Worker getWorker() { return worker; }
    public Consumer getConsumer() { return consumer; }

    @PostConstruct
    void validate() {
        if (worker.agentPoolSize < 1 || worker.agentPoolSize > 64) {
            throw new IllegalStateException("scaling.agent.worker.agent-pool-size must be 1-64, got: " + worker.agentPoolSize);
        }
        if (worker.classifyPoolSize < 1 || worker.classifyPoolSize > 64) {
            throw new IllegalStateException("scaling.agent.worker.classify-pool-size must be 1-64, got: " + worker.classifyPoolSize);
        }
        if (worker.guardrailPoolSize < 1 || worker.guardrailPoolSize > 64) {
            throw new IllegalStateException("scaling.agent.worker.guardrail-pool-size must be 1-64, got: " + worker.guardrailPoolSize);
        }
        if (consumer.pollIntervalMs < 10 || consumer.pollIntervalMs > 10_000) {
            throw new IllegalStateException("scaling.agent.consumer.poll-interval-ms must be 10-10000, got: " + consumer.pollIntervalMs);
        }
        log.info("[AgentScalingConfig] queue.enabled={}, workers: agent={}, classify={}, guardrail={}, poll={}ms",
                queue.enabled, worker.agentPoolSize, worker.classifyPoolSize, worker.guardrailPoolSize, consumer.pollIntervalMs);
    }

    /**
     * Queue configuration - controls whether async queue-based execution is active.
     */
    public static class Queue {
        /** Enable queue-based async agent execution. Default: false. */
        private boolean enabled = false;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    /**
     * Worker pool sizes - independently configurable per agent type.
     * Agent loops are long-running (1-65 min), classify/guardrail are fast (1-5s).
     */
    public static class Worker {
        /** Thread pool size for full agent loop execution. Default: 8. */
        private int agentPoolSize = 8;

        /** Thread pool size for classify execution. Default: 4. */
        private int classifyPoolSize = 4;

        /** Thread pool size for guardrail execution. Default: 4. */
        private int guardrailPoolSize = 4;

        public int getAgentPoolSize() { return agentPoolSize; }
        public void setAgentPoolSize(int agentPoolSize) { this.agentPoolSize = agentPoolSize; }

        public int getClassifyPoolSize() { return classifyPoolSize; }
        public void setClassifyPoolSize(int classifyPoolSize) { this.classifyPoolSize = classifyPoolSize; }

        public int getGuardrailPoolSize() { return guardrailPoolSize; }
        public void setGuardrailPoolSize(int guardrailPoolSize) { this.guardrailPoolSize = guardrailPoolSize; }
    }

    /**
     * Consumer polling configuration.
     */
    public static class Consumer {
        /** Poll interval in milliseconds when no tasks are available. Default: 100. */
        private long pollIntervalMs = 100;

        public long getPollIntervalMs() { return pollIntervalMs; }
        public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }
    }
}

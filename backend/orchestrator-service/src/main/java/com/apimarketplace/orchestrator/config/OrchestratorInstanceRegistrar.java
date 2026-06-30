package com.apimarketplace.orchestrator.config;

import com.apimarketplace.common.scaling.registry.RedisServiceRegistry;
import com.apimarketplace.common.scaling.registry.ServiceInstance;
import com.apimarketplace.orchestrator.services.streaming.context.RunContextRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.UUID;

/**
 * Registers this orchestrator instance in the Redis service registry on startup,
 * sends heartbeats every 10 seconds, and deregisters on shutdown.
 *
 * <p>Plan v4 E2E5/SF1 - guard via {@code @ConditionalOnProperty} (same predicate
 * as {@code RedisScalingConfiguration}) instead of {@code @ConditionalOnBean}.
 * User {@code @Component} scan runs BEFORE Spring Boot auto-config processing,
 * so when {@code @ConditionalOnBean(RedisServiceRegistry.class)} is evaluated
 * here the bean doesn't yet exist - the condition silently fails, the registrar
 * never registers, and the Redis service registry stays empty (multi-instance
 * gateway routing broken). Property-based condition aligns with the auto-config's
 * own activation key, so both fire deterministically when
 * {@code scaling.backend=redis}.
 *
 * <p>Only active when {@code scaling.backend=redis}.
 */
@Component
@ConditionalOnProperty(name = "scaling.backend", havingValue = "redis")
public class OrchestratorInstanceRegistrar {

    private static final Logger logger = LoggerFactory.getLogger(OrchestratorInstanceRegistrar.class);
    public static final String SERVICE_NAME = "orchestrator-service";

    private final RedisServiceRegistry registry;
    private final RunContextRegistry runContextRegistry;
    private final ServiceInstance self;

    public OrchestratorInstanceRegistrar(
            RedisServiceRegistry registry,
            RunContextRegistry runContextRegistry,
            @Value("${server.port:8099}") int port,
            @Value("${scaling.instance-id:}") String configuredId) {

        String instanceId = configuredId.isBlank()
                ? "orch-" + UUID.randomUUID().toString().substring(0, 8)
                : configuredId;

        String host = resolveHost();
        this.registry = registry;
        this.runContextRegistry = runContextRegistry;
        this.self = new ServiceInstance(instanceId, host, port);
    }

    @EventListener(ContextRefreshedEvent.class)
    public void register() {
        registry.heartbeat(SERVICE_NAME, self);
        runContextRegistry.setInstanceId(self.instanceId());
        logger.info("[InstanceRegistrar] Registered as {} ({}:{})",
                self.instanceId(), self.host(), self.port());
    }

    @Scheduled(fixedDelay = 10_000)
    public void heartbeat() {
        try {
            registry.heartbeat(SERVICE_NAME, self);
            runContextRegistry.refreshActiveRunsTtl();
        } catch (Exception e) {
            logger.warn("[InstanceRegistrar] Heartbeat failed: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void deregister() {
        try {
            // 1. Clear active runs tracking FIRST (prevents failover service from
            //    marking our runs as FAILED while we're still draining)
            runContextRegistry.clearActiveRuns();

            // 2. Deregister from service registry (gateway stops routing to this instance)
            registry.deregister(SERVICE_NAME, self);
            logger.info("[InstanceRegistrar] Deregistered {} from service registry", self.instanceId());

            // 3. Wait briefly for in-flight requests to complete
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            logger.info("[InstanceRegistrar] Graceful shutdown complete for {}", self.instanceId());
        } catch (Exception e) {
            logger.warn("[InstanceRegistrar] Deregister failed: {}", e.getMessage());
        }
    }

    public ServiceInstance getSelf() {
        return self;
    }

    public String getInstanceId() {
        return self.instanceId();
    }

    private static String resolveHost() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }
}

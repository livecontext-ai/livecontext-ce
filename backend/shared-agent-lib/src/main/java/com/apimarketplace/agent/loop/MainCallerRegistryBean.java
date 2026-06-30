package com.apimarketplace.agent.loop;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Startup assertion bean - logs the authoritative list of MAIN-purpose entry points
 * whose context-optimization pipeline must stay centralized via
 * {@link AgentLoopExecutor#processIteration}.
 *
 * <p>This bean exists so a) the enumeration surfaces in every service start-up log
 * (easy grep: {@code MAIN_CALLER_REGISTRY}), and b) integration tests can depend on
 * {@link MainCallerRegistry#MAIN_CALLERS} being the single source of truth.
 *
 * <p>No runtime behaviour beyond logging - enforcement is done by the downstream
 * tests ({@code MainCallerCentralizationTest}) that replay each caller through the
 * chokepoint.
 */
@Slf4j
@Component
public class MainCallerRegistryBean {

    @PostConstruct
    void logRegistry() {
        log.info("[MAIN_CALLER_REGISTRY] Centralized MAIN-pipeline callers ({} total): {}",
            MainCallerRegistry.MAIN_CALLERS.size(),
            MainCallerRegistry.MAIN_CALLERS);
    }
}

package com.apimarketplace.orchestrator.execution.v2.nodes.merge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Factory for creating MergeStrategy instances.
 *
 * Strategies are cached and reused since they are stateless.
 */
@Component
public class MergeStrategyFactory {

    private static final Logger logger = LoggerFactory.getLogger(MergeStrategyFactory.class);

    private final Map<String, MergeStrategy> strategyCache = new ConcurrentHashMap<>();

    // Default strategy
    private static final String DEFAULT_STRATEGY = "QUEUE_1_TO_1";

    public MergeStrategyFactory() {
        // Pre-populate cache with all strategies
        strategyCache.put("QUEUE_1_TO_1", new Queue1To1Strategy());
        strategyCache.put("COMBINE_ALL", new CombineAllStrategy());
        strategyCache.put("FIRST_AVAILABLE", new FirstAvailableStrategy());
        // LATEST strategy can be added when needed
    }

    /**
     * Gets a strategy by name.
     *
     * @param strategyName Name of the strategy (case-insensitive)
     * @return The strategy, or default if not found
     */
    public MergeStrategy getStrategy(String strategyName) {
        if (strategyName == null || strategyName.isBlank()) {
            return getDefaultStrategy();
        }

        String normalizedName = strategyName.toUpperCase().replace("-", "_");
        MergeStrategy strategy = strategyCache.get(normalizedName);

        if (strategy == null) {
            logger.warn("Unknown merge strategy '{}', using default: {}",
                strategyName, DEFAULT_STRATEGY);
            return getDefaultStrategy();
        }

        return strategy;
    }

    /**
     * Gets the default strategy (QUEUE_1_TO_1).
     */
    public MergeStrategy getDefaultStrategy() {
        return strategyCache.get(DEFAULT_STRATEGY);
    }

    /**
     * Registers a custom strategy.
     */
    public void registerStrategy(MergeStrategy strategy) {
        String name = strategy.name().toUpperCase();
        strategyCache.put(name, strategy);
        logger.info("Registered merge strategy: {}", name);
    }

    /**
     * Lists all available strategy names.
     */
    public java.util.Set<String> getAvailableStrategies() {
        return java.util.Set.copyOf(strategyCache.keySet());
    }
}

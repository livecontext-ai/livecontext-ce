package com.apimarketplace.common.scaling.queue;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Configuration for priority tier weights used by the weighted fair queue.
 * Supports 8 priority tiers (0-7), where lower number = higher priority.
 * Weights determine relative scheduling proportion in deficit round-robin.
 */
public class PriorityTierWeights {

    private final Map<Integer, Integer> weights;

    /**
     * Create with default weights: tier 0 gets weight 8, tier 7 gets weight 1.
     */
    public PriorityTierWeights() {
        Map<Integer, Integer> defaults = new HashMap<>();
        for (int i = 0; i < 8; i++) {
            defaults.put(i, 8 - i);
        }
        this.weights = Collections.unmodifiableMap(defaults);
    }

    /**
     * Create with custom weights.
     *
     * @param weights map from priority tier (0-7) to weight
     */
    public PriorityTierWeights(Map<Integer, Integer> weights) {
        Map<Integer, Integer> copy = new HashMap<>();
        for (int i = 0; i < 8; i++) {
            copy.put(i, weights.getOrDefault(i, 8 - i));
        }
        this.weights = Collections.unmodifiableMap(copy);
    }

    /**
     * Get the weight for a given priority tier.
     *
     * @param priority the priority tier (0-7)
     * @return the weight (higher = more scheduling share)
     */
    public int getWeight(int priority) {
        return weights.getOrDefault(priority, 1);
    }

    /**
     * Get all weights as an unmodifiable map.
     */
    public Map<Integer, Integer> getWeights() {
        return weights;
    }
}

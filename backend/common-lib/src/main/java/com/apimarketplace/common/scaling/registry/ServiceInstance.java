package com.apimarketplace.common.scaling.registry;

/**
 * Represents a single service instance in the distributed registry.
 * Stored in a Redis sorted set for heartbeat-based discovery.
 */
public record ServiceInstance(
        String instanceId,
        String host,
        int port
) {
    /**
     * Encode as a compact string for Redis storage: "instanceId|host|port"
     */
    public String encode() {
        return instanceId + "|" + host + "|" + port;
    }

    /**
     * Decode from the compact string format.
     */
    public static ServiceInstance decode(String encoded) {
        String[] parts = encoded.split("\\|", 3);
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid ServiceInstance encoding: " + encoded);
        }
        return new ServiceInstance(parts[0], parts[1], Integer.parseInt(parts[2]));
    }
}

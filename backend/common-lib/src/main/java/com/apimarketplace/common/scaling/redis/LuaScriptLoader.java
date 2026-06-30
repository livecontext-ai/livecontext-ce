package com.apimarketplace.common.scaling.redis;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Utility for loading Lua scripts as Spring {@link DefaultRedisScript} instances.
 * Uses {@code EVALSHA} with automatic fallback to {@code EVAL} on cache miss
 * (e.g., after Redis Sentinel failover).
 */
final class LuaScriptLoader {

    private LuaScriptLoader() {}

    /**
     * Load a Lua script from the classpath {@code lua/} directory.
     *
     * @param filename    script filename (e.g., "semaphore_acquire.lua")
     * @param resultType  expected return type
     * @param <T>         return type
     * @return a reusable DefaultRedisScript instance
     */
    static <T> DefaultRedisScript<T> load(String filename, Class<T> resultType) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/" + filename));
        script.setResultType(resultType);
        return script;
    }
}

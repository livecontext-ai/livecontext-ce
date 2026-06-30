package com.apimarketplace.orchestrator.services.streaming;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Wires the {@link RunSeqBackend} strategy for {@link WsEventSequencer}.
 *
 * <ul>
 *   <li>{@code scaling.backend=redis} (prod, multi-replica) -> {@link RedisRunSeqBackend}:
 *       one shared Redis counter per run, globally monotonic across pods.</li>
 *   <li>anything else (local dev, CE monolith, single pod) -> {@link InMemoryRunSeqBackend}
 *       via {@link ConditionalOnMissingBean}, preserving the original behavior.</li>
 * </ul>
 *
 * <p>Mirrors the {@code RedisScalingConfiguration} pattern (Redis bean gated on the
 * property, in-memory fallback via {@code @ConditionalOnMissingBean}). The Redis
 * bean is declared first so the fallback's missing-bean check sees it.
 */
@Configuration(proxyBeanMethods = false)
public class RunSeqBackendConfig {

    @Bean
    @ConditionalOnProperty(name = "scaling.backend", havingValue = "redis")
    RunSeqBackend redisRunSeqBackend(
            StringRedisTemplate stringRedisTemplate,
            @Value("${ws-event-sequencer.redis-seq-ttl-ms:604800000}") long seqTtlMs) {
        return new RedisRunSeqBackend(stringRedisTemplate, seqTtlMs);
    }

    @Bean
    @ConditionalOnMissingBean(RunSeqBackend.class)
    RunSeqBackend inMemoryRunSeqBackend() {
        return new InMemoryRunSeqBackend();
    }
}

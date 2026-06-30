package com.apimarketplace.monolith.config;

import com.apimarketplace.agent.service.execution.RemoteToolExecutionService;
import com.apimarketplace.agent.tool.ToolExecutionService;
import com.apimarketplace.conversation.service.ConversationHistoryService;
import com.apimarketplace.conversation.service.ToolResultService;
import com.apimarketplace.conversation.streaming.DmEventPublisher;
import com.apimarketplace.conversation.service.ai.ConversationToolExecutionService;
import com.apimarketplace.conversation.service.ai.ToolServiceRouter;
import com.apimarketplace.conversation.streaming.StreamPubSubService;
import com.apimarketplace.conversation.streaming.StreamStateService;
import com.apimarketplace.conversation.streaming.RedisStreamStateService;
import com.apimarketplace.common.event.EventBus;
import com.apimarketplace.common.event.RedisEventBus;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;

/**
 * Centralized adapter configuration for CE monolith mode.
 *
 * Resolves all bean conflicts between microservices when running in a single JVM:
 * - ToolExecutionService: composing adapter that handles conversation tools locally
 * - StreamStateService: Redis-backed stream state reused from the cloud path
 *
 * This is the SINGLE place where monolith-specific bean overrides are defined.
 * Cloud code is reused as-is - only the wiring changes.
 */
@Configuration
@ConditionalOnProperty(name = "deployment.mode", havingValue = "monolith")
public class MonolithAdapterConfig {

    private static final String CATALOG_TOOLS_GATEWAY_BEAN =
        "com.apimarketplace.orchestrator.services.impl.CatalogToolsGateway";

    /**
     * Monolith ToolExecutionService - wraps RemoteToolExecutionService and adds
     * local handling for conversation-specific tools (set_conversation_title, etc.).
     * Eliminates the HTTP callback loop and the dual-@Primary conflict.
     */
    @Bean
    @Primary
    public ToolExecutionService monolithToolExecutionService(
            RemoteToolExecutionService delegate,
            ConversationHistoryService conversationHistoryService,
            ToolResultService toolResultService,
            StreamPubSubService streamPubSubService,
            ToolServiceRouter toolServiceRouter,
            AutowireCapableBeanFactory beanFactory) {
        ConversationToolExecutionService conversationLocalTools = new ConversationToolExecutionService(
                conversationHistoryService,
                toolResultService,
                streamPubSubService,
                toolServiceRouter
        );
        beanFactory.autowireBean(conversationLocalTools);
        return new MonolithToolExecutionService(delegate, conversationLocalTools);
    }

    /**
     * Alias the fully-qualified monolith bean name back to the qualifier used by
     * orchestrator-service's ExecutionServiceInjector.
     */
    @Bean
    public static BeanDefinitionRegistryPostProcessor monolithCatalogToolsGatewayAlias() {
        return new BeanDefinitionRegistryPostProcessor() {
            @Override
            public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
                if (registry.containsBeanDefinition(CATALOG_TOOLS_GATEWAY_BEAN)
                    && !registry.isAlias("catalogToolsGateway")) {
                    registry.registerAlias(CATALOG_TOOLS_GATEWAY_BEAN, "catalogToolsGateway");
                }
            }

            @Override
            public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
                // No-op: the alias must be available before bean instantiation.
            }
        };
    }

    /**
     * Reactive Redis listener container for the monolith - the ONE bean the cloud
     * {@link RedisStreamStateService} needs that isn't already present. Catalog's
     * {@code RedisConfig} already exposes a {@code @Primary ReactiveRedisTemplate<String,String>}
     * (reactive Lettuce runs fine in this servlet JVM), but no reactive listener container; add it
     * here so we can reuse the cloud stream-state service verbatim.
     */
    @Bean
    public ReactiveRedisMessageListenerContainer monolithReactiveRedisMessageListenerContainer(
            ReactiveRedisConnectionFactory connectionFactory) {
        return new ReactiveRedisMessageListenerContainer(connectionFactory);
    }

    /**
     * Stream state for monolith mode - the SAME {@link RedisStreamStateService} the cloud uses,
     * not a no-op. Previously a no-op stub (returning empty content / inactive) on the false premise
     * that "reactive Redis isn't available in servlet mode" - but reactive Lettuce already runs here.
     *
     * <p>Why this matters: a NEW chat from Home subscribes to the WS channel AFTER the POST resolves,
     * and {@code sendMessage} requests a snapshot replay to recover any events published before the
     * subscription landed. The shared {@code ConversationRedisStreamingCallback} already writes the
     * stream content/tool-event/conversation-index keys (via {@code StreamRedisKeys}) in the exact
     * format this service reads - but the no-op returned empty, so the replay found nothing and the
     * conversation rendered empty until a manual reload. Reusing the real service exposes that
     * already-persisted state, giving CE the same snapshot/recovery the cloud has.
     *
     * <p>{@code RedisStreamStateService} lives in the {@code conversation.streaming} package which is
     * excluded from component scan ({@code MonolithApplication}); instantiate it explicitly here so
     * there is exactly one {@code StreamStateService} bean (no scan duplicate). Reuses catalog's
     * {@code @Primary} reactive template; the conversation {@code RedisConfig} stays excluded to avoid
     * a dual-{@code @Primary} template collision.
     */
    @Bean
    public StreamStateService streamStateService(
            ReactiveRedisTemplate<String, String> reactiveRedisTemplate,
            ReactiveRedisMessageListenerContainer reactiveRedisMessageListenerContainer) {
        return new RedisStreamStateService(reactiveRedisTemplate, reactiveRedisMessageListenerContainer);
    }

    /**
     * DM WebSocket publisher for CE monolith mode.
     *
     * The conversation.streaming package stays scan-excluded because most of it
     * is cloud WebFlux infrastructure, but DmEventPublisher is just a Redis
     * publisher that writes ws:dm:* and ws:dm-inbox:* channels. Reuse it so CE
     * has the same live DM event path as cloud.
     */
    @Bean
    public DmEventPublisher dmEventPublisher(
            ReactiveRedisTemplate<String, String> reactiveRedisTemplate,
            ObjectMapper objectMapper) {
        return new DmEventPublisher(reactiveRedisTemplate, objectMapper);
    }

    /**
     * Monolith StreamPubSubService adapter. Most reactive stream methods are
     * no-ops, while title updates are bridged to the non-reactive Redis WS
     * channel so reused cloud conversation tools still update the live CE UI.
     */
    @Bean
    public StreamPubSubService noOpStreamPubSubService(ObjectMapper objectMapper, StringRedisTemplate redisTemplate) {
        return new NoOpStreamPubSubService(objectMapper, redisTemplate);
    }

    /**
     * Monolith RedisMessageListenerContainer.
     *
     * Each per-service Redis config gates this bean on
     * {@code deployment.mode=microservice}, so in monolith mode none of them
     * fire. The common-lib EventBus config registers one too but only when
     * {@code @ConditionalOnBean(RedisConnectionFactory.class)} is satisfied
     * during the wiring pass - which is not reliable across the cross-service
     * bean graph. Provide an unconditional container here so consumers
     * (AgentResultSubscriber, SignalResumeRedisListener, …) always have a bean.
     */
    @Bean
    @Primary
    public RedisMessageListenerContainer monolithRedisMessageListenerContainer(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        return container;
    }

    /**
     * Redis-backed EventBus for CE monolith WebSocket events.
     *
     * The CE WebSocket handler bridges Redis {@code ws:*} channels to browser
     * sessions. Publishers such as AgentActivityPublisher must therefore write
     * to Redis too, otherwise snapshot replay can emit into the in-memory
     * fallback and never reach the bridge.
     */
    @Bean
    @Primary
    public EventBus monolithRedisEventBus(
            StringRedisTemplate stringRedisTemplate,
            RedisMessageListenerContainer redisMessageListenerContainer,
            MeterRegistry meterRegistry) {
        return new RedisEventBus(stringRedisTemplate, redisMessageListenerContainer, meterRegistry);
    }
}

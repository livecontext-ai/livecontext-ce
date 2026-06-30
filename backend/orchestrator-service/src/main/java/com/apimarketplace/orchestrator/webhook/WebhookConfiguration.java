package com.apimarketplace.orchestrator.webhook;

import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.trigger.client.TriggerClient;
import com.apimarketplace.trigger.client.webhook.WebhookAuthService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Configuration for webhook services.
 */
@Configuration
public class WebhookConfiguration {

    /**
     * WebhookAuthService bean - lives in trigger-client JAR (no @Service),
     * so we declare it explicitly here.
     */
    @Bean
    public WebhookAuthService webhookAuthService() {
        return new WebhookAuthService();
    }

    @Bean
    @ConditionalOnClass(StringRedisTemplate.class)
    public WebhookIndexService webhookIndexService(
            StringRedisTemplate redisTemplate,
            WorkflowRepository workflowRepository,
            TriggerClient triggerClient) {
        return new WebhookIndexService(redisTemplate, workflowRepository, triggerClient);
    }
}

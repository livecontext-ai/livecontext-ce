package com.apimarketplace.conversation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Main application class for Conversation Service
 */
@SpringBootApplication
@ComponentScan(basePackages = {
    "com.apimarketplace.conversation",
    "com.apimarketplace.common.storage",  // Scan common-storage-service beans
    "com.apimarketplace.agent.loop",      // MainCallerRegistryBean - centralization invariant startup log (test-scope only)
    "com.apimarketplace.agent.client.queue" // AgentQueueProducer + RedisResultWaiter (PR2 chat-on-queue), @ConditionalOnProperty(scaling.agent.queue.enabled)
})
@EnableJpaRepositories(basePackages = {
    "com.apimarketplace.conversation.repository",
    "com.apimarketplace.common.storage.repository"
})
@EntityScan(basePackages = {
    "com.apimarketplace.conversation.entity",
    "com.apimarketplace.conversation.domain",
    "com.apimarketplace.common.storage.domain"
})
@EnableSpringDataWebSupport(pageSerializationMode = EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO)
@EnableScheduling
@EnableAsync
public class ConversationServiceApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(ConversationServiceApplication.class, args);
    }
}

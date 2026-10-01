package com.apimarketplace.conversation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Main application class for Conversation Service
 */
@SpringBootApplication
@ComponentScan(
    basePackages = {
        "com.apimarketplace.conversation",
        "com.apimarketplace.common.storage",  // Scan common-storage-service beans
        "com.apimarketplace.common.security", // CredentialEncryptionService + TokenAtRestBootstrap (share_token at rest)
        "com.apimarketplace.agent.loop",      // MainCallerRegistryBean - centralization invariant startup log (test-scope only)
        "com.apimarketplace.agent.client.queue" // AgentQueueProducer + RedisResultWaiter (PR2 chat-on-queue), @ConditionalOnProperty(scaling.agent.queue.enabled)
    },
    // A @ComponentScan declared here replaces the one @SpringBootApplication carries, and with it
    // Boot's TypeExcludeFilter. Without the filter a @SpringBootTest context scans the test classes
    // too and registers every nested @Configuration of a plain Spring test as a bean source (on
    // 2026-09-25 one such config's hand-built entityManagerFactory, bound to a two-entity test
    // persistence unit, replaced the real one and no integration test context could boot).
    // Same filter as orchestrator-service and datasource-service. Inert at runtime: it only
    // delegates to TypeExcludeFilter beans, which only the Spring test framework registers.
    excludeFilters = {
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class)
    }
)
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
